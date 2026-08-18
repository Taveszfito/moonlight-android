package com.limelight.dualsense

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import com.example.usbbtonandroid.DualSenseBtOutputBuilder
import com.example.usbbtonandroid.DualSenseInput
import com.example.usbbtonandroid.DualSenseInputParser
import com.example.usbbtonandroid.DualSenseOutputConfig
import com.example.usbbtonandroid.TriggerMode
import com.example.usbbtonandroid.hci.HciUsbController
import com.example.usbbtonandroid.hci.CsrHciUsbController
import com.limelight.R
import com.limelight.LimeLog
import com.limelight.Game
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Process-wide owner of the external USB Bluetooth adapter and DualSense link.
 * Activities only observe/control this object, so configuration changes and
 * stream/menu navigation cannot tear down the sensitive HCI connection.
 */
object DualSenseBridge {
    const val ACTION_USB_PERMISSION = "com.limelight.DUALSENSE_USB_PERMISSION"
    private const val PREF_BATTERY_LED_ENABLED = "battery_led_enabled"
    private const val PREF_LOW_BATTERY_BLINK_ENABLED = "low_battery_blink_enabled"
    private const val PREF_CONNECTION_OVERLAY_ENABLED = "connection_overlay_enabled"
    private const val PREF_HCI_PROFILE = "hci_profile"
    const val HCI_PROFILE_AUTO = 0
    const val HCI_PROFILE_GENERIC = 1
    const val HCI_PROFILE_CSR = 2
    private const val LOW_BATTERY_THRESHOLD_PERCENT = 15

    fun interface InputListener { fun onInput(input: DualSenseInput) }
    fun interface StateListener { fun onStateChanged() }

    private lateinit var appContext: Context
    private lateinit var usbManager: UsbManager
    private var initialized = false
    private var controller: HciUsbController? = null
    private var adapter: UsbDevice? = null
    private val inputListeners = CopyOnWriteArraySet<InputListener>()
    private val stateListeners = CopyOnWriteArraySet<StateListener>()
    private val devicesByAddress = linkedMapOf<String, HciUsbController.HciDevice>()
    private val deviceLastSeenAt = linkedMapOf<String, Long>()
    private val ignoredDeviceCallbacksUntil = mutableMapOf<String, Long>()
    private val logLines = ArrayDeque<String>()
    private val incidentHistoryLines = ArrayDeque<String>()
    private val outputLock = Any()
    private val controlExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "DualSenseBridge-control").apply { isDaemon = true }
    }
    private var outputConfig = DualSenseOutputConfig()
    @Volatile private var ledOverrideActive = false
    @Volatile private var batteryLedEnabled = true
    @Volatile private var lowBatteryBlinkEnabled = true
    @Volatile private var connectionOverlayEnabled = false
    @Volatile private var requestedHciProfile = HCI_PROFILE_AUTO
    @Volatile private var activeHciProfile = HCI_PROFILE_AUTO
    // Owned by the client's microphone forwarding state. Host feedback may set
    // its own mic LED bit, but it must not override this global mute indicator.
    @Volatile private var microphoneMuted = false
    @Volatile private var lastStreamRelayAtMs = 0L
    @Volatile private var streamActive = false
    @Volatile private var streamRecoveryArmed = false
    private var lastBatteryLedPercent = -1
    @Volatile private var lowBatteryBlinkActive = false
    @Volatile private var lowBatteryBlinkShowingRed = false
    private var lowBatteryBlinkPhase = 0
    private val watchdogHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastInputAtMs = 0L
    @Volatile private var lastBluetoothMicrophoneActivityMs = 0L
    @Volatile private var activeControllerAddress: String? = null
    @Volatile private var smartRecoveryInProgress = false
    @Volatile private var adapterRecoveryPerformed = false
    @Volatile private var recoveryOverlayStage = RECOVERY_OVERLAY_NONE
    @Volatile private var recoveryOverlayStageAtMs = 0L
    private const val INPUT_TIMEOUT_MS = 2500L
    private const val BLUETOOTH_MIC_ACTIVITY_UI_INTERVAL_MS = 250L
    // Preserve and rebuild the live Bluetooth link first. A complete adapter
    // restart is deliberately delayed as the final fallback.
    private const val ADAPTER_RECOVERY_DELAY_MS = 15_000L
    private const val RECOVERY_DISCONNECT_GRACE_MS = 750L
    private val adapterRecovery = Runnable {
        if (!smartRecoveryInProgress || controllerConnected) return@Runnable
        val device = adapter ?: return@Runnable
        adapterRecoveryPerformed = true
        setRecoveryOverlayStage(RECOVERY_OVERLAY_ADAPTER_RESET)
        updateStatus(s(R.string.dualsense_bridge_status_adapter_recovery))
        // Terminate the live radio link before resetting USB. Without this grace
        // period the dongle restarts, but DualSense can remain powered in a
        // ghost connection state because it never received HCI Disconnect.
        activeControllerAddress?.let { controller?.disconnect(it) }
        watchdogHandler.postDelayed({
            if (smartRecoveryInProgress && !controllerConnected) {
                startController(device, true)
            }
        }, RECOVERY_DISCONNECT_GRACE_MS)
    }
    private val connectionWatchdog = object : Runnable {
        override fun run() {
            if (controllerConnected && lastInputAtMs != 0L &&
                SystemClock.elapsedRealtime() - lastInputAtMs > INPUT_TIMEOUT_MS) {
                recordIncident(s(R.string.dualsense_diag_input_timeout_history,
                    SystemClock.elapsedRealtime() - lastInputAtMs))
                // The Bridge UI must always reflect a dead HID link. Only the
                // destructive recovery/reset path is limited to an active stream
                // that has actually consumed this controller's input.
                if (streamActive && streamRecoveryArmed) beginSmartRecovery()
                markControllerDisconnected(s(R.string.dualsense_bridge_status_connection_lost))
            }
            expireStaleDiscoveredDevices()
            watchdogHandler.postDelayed(this, 500L)
        }
    }
    private val lowBatteryBlink = object : Runnable {
        override fun run() {
            if (!shouldBlinkForLowBattery()) {
                stopLowBatteryBlink(true)
                return
            }
            when (lowBatteryBlinkPhase) {
                0, 2 -> {
                    lowBatteryBlinkShowingRed = true
                    sendOutputSnapshot(true)
                    lowBatteryBlinkPhase++
                    watchdogHandler.postDelayed(this, 170L)
                }
                1 -> {
                    lowBatteryBlinkShowingRed = false
                    sendOutputSnapshot(false)
                    lowBatteryBlinkPhase++
                    watchdogHandler.postDelayed(this, 170L)
                }
                else -> {
                    lowBatteryBlinkShowingRed = false
                    sendOutputSnapshot(false)
                    lowBatteryBlinkPhase = 0
                    watchdogHandler.postDelayed(this, 2500L)
                }
            }
        }
    }

    @get:JvmStatic
    @Volatile var status: String = "Not initialized"
        private set
    @get:JvmStatic
    @Volatile var latestInput: DualSenseInput = DualSenseInput.Empty
        private set
    @get:JvmStatic
    @Volatile var inputPacketCount: Long = 0
        private set
    @get:JvmStatic
    @Volatile var controllerConnected: Boolean = false
        private set
    @get:JvmStatic
    @Volatile var headphonesConnected: Boolean = false
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice()
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                        startController(device)
                    } else updateStatus(s(R.string.dualsense_bridge_status_permission_denied))
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scan(null)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = intent.usbDevice()
                    if (detached != null && detached.deviceId == adapter?.deviceId) {
                        closeController(s(R.string.dualsense_bridge_status_adapter_disconnected))
                    }
                }
            }
        }
    }

    @JvmStatic fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION") appContext.registerReceiver(receiver, filter)
            }
            initialized = true
            batteryLedEnabled = keyPrefs().getBoolean(PREF_BATTERY_LED_ENABLED, true)
            lowBatteryBlinkEnabled = keyPrefs().getBoolean(PREF_LOW_BATTERY_BLINK_ENABLED, true)
            connectionOverlayEnabled = keyPrefs().getBoolean(PREF_CONNECTION_OVERLAY_ENABLED, false)
            requestedHciProfile = keyPrefs().getInt(PREF_HCI_PROFILE, HCI_PROFILE_AUTO)
            watchdogHandler.post(connectionWatchdog)
            loadSavedDevices()
            updateStatus(s(R.string.dualsense_bridge_status_ready))
            scan(null)
        }
    }

    @JvmStatic fun scan(activity: Activity?) {
        if (!initialized) {
            if (activity == null) return
            initialize(activity)
        }
        val all = usbManager.deviceList.values.toList()
        val found = all.firstOrNull(HciUsbController::looksLikeBluetoothHci)
            ?: all.firstOrNull(HciUsbController::hasHciEndpointLayout)
        if (found == null) {
            updateStatus(s(R.string.dualsense_bridge_status_no_adapter))
            return
        }
        if (controller != null && found.deviceId == adapter?.deviceId) {
            if (controllerConnected) {
                updateStatus(s(R.string.dualsense_bridge_status_connected))
            } else {
                clearStaleAvailableDevices()
                updateStatus(s(R.string.dualsense_bridge_status_scanning))
                controller?.requestDiscovery()
            }
            return
        }
        if (usbManager.hasPermission(found)) {
            startController(found)
        } else if (activity != null) {
            updateStatus(s(R.string.dualsense_bridge_status_waiting_permission))
            val permission = PendingIntent.getBroadcast(
                appContext, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(found, permission)
        } else {
            updateStatus(s(R.string.dualsense_bridge_status_usb_permission))
        }
    }

    @JvmStatic fun reconnect(address: String, name: String) {
        controller?.connect(address, name)
            ?: updateStatus(s(R.string.dualsense_bridge_status_connect_adapter))
    }

    @JvmStatic fun reset(activity: Activity?) {
        // Closing the USB workers can wait for an in-flight bulk transfer. Publish
        // acknowledgement first and never make the UI thread wait for that join.
        updateStatus(s(R.string.dualsense_bridge_status_resetting))
        controlExecutor.execute {
            closeController(null)
            watchdogHandler.post { scan(activity) }
        }
    }
    private val prepareAdapterForReconnect = Runnable {
        val device = adapter ?: return@Runnable
        if (controllerConnected) return@Runnable
        appendLog("Controller disconnected; preparing clean adapter state for reconnect")
        setRecoveryOverlayStage(RECOVERY_OVERLAY_ADAPTER_RESET)
        startController(device, true)
    }

    @JvmStatic fun disconnectBridge() {
        updateStatus(s(R.string.dualsense_bridge_status_stopped))
        controlExecutor.execute { closeController(null) }
    }

    @JvmStatic fun isBatteryLedEnabled(context: Context): Boolean = batteryLedEnabled

    @JvmStatic fun setBatteryLedEnabled(enabled: Boolean) {
        batteryLedEnabled = enabled
        keyPrefs().edit().putBoolean(PREF_BATTERY_LED_ENABLED, enabled).apply()
        lastBatteryLedPercent = -1
        if (enabled) {
            updateBatteryLedIfNeeded(latestInput.batteryPercent)
        }
        else if (!ledOverrideActive) {
            updateOutput { it.copy(red = 0, green = 80, blue = 255) }
        }
    }

    @JvmStatic fun isLowBatteryBlinkEnabled(context: Context): Boolean = lowBatteryBlinkEnabled

    @JvmStatic fun setLowBatteryBlinkEnabled(enabled: Boolean) {
        lowBatteryBlinkEnabled = enabled
        keyPrefs().edit().putBoolean(PREF_LOW_BATTERY_BLINK_ENABLED, enabled).apply()
        updateLowBatteryBlinkState(latestInput.batteryPercent)
    }

    @JvmStatic fun isConnectionOverlayEnabled(): Boolean = connectionOverlayEnabled

    @JvmStatic fun setConnectionOverlayEnabled(enabled: Boolean) {
        connectionOverlayEnabled = enabled
        keyPrefs().edit().putBoolean(PREF_CONNECTION_OVERLAY_ENABLED, enabled).apply()
    }

    @JvmStatic fun markStreamInputForwarded() {
        lastStreamRelayAtMs = SystemClock.elapsedRealtime()
        // The first real relay proves that this particular stream has consumed
        // controller input. Only then may an input timeout trigger recovery.
        streamRecoveryArmed = true
    }

    /** Selects a transport profile for the next adapter open. Auto picks CSR only for 0A12:0001. */
    @JvmStatic fun setHciProfile(context: Context, profile: Int) {
        initialize(context)
        requestedHciProfile = when (profile) {
            HCI_PROFILE_GENERIC, HCI_PROFILE_CSR -> profile
            else -> HCI_PROFILE_AUTO
        }
        keyPrefs().edit().putInt(PREF_HCI_PROFILE, requestedHciProfile).apply()
        appendLog("Requested HCI profile: ${hciProfileName(requestedHciProfile)}")
        reset(context as? Activity)
    }

    @JvmStatic fun requestedHciProfile(): Int = requestedHciProfile
    @JvmStatic fun activeHciProfileName(): String = hciProfileName(activeHciProfile)

    private fun selectHciProfile(device: UsbDevice): Int = when (requestedHciProfile) {
        HCI_PROFILE_GENERIC, HCI_PROFILE_CSR -> requestedHciProfile
        else -> if (device.vendorId == CSR_VENDOR_ID && device.productId == CSR_PRODUCT_ID)
            HCI_PROFILE_CSR else HCI_PROFILE_GENERIC
    }

    private fun hciProfileName(profile: Int): String = when (profile) {
        HCI_PROFILE_GENERIC -> "Profile 1 · Generic HCI"
        HCI_PROFILE_CSR -> "Profile 2 · CSR HCI"
        else -> "Auto"
    }

    /** Called by the stream controller, not by the Bridge settings Activity. */
    @JvmStatic fun setStreamActive(active: Boolean) {
        streamActive = active
        streamRecoveryArmed = false
        if (!active) return

        // A controller that was already healthy before this stream began is not
        // a reconnect. Clear stale recovery work instead of resetting its adapter.
        if (controllerConnected) {
            smartRecoveryInProgress = false
            adapterRecoveryPerformed = false
            watchdogHandler.removeCallbacks(adapterRecovery)
            recoveryOverlayStage = RECOVERY_OVERLAY_NONE
            recoveryOverlayStageAtMs = 0L
        }
    }

    @JvmStatic fun getDiagnosticsSnapshot(): DiagnosticsSnapshot {
        val now = SystemClock.elapsedRealtime()
        val link = controller?.getDiagnostics()
        val hidAge = if (link == null || link.lastHidInputAtMs == 0L) Long.MAX_VALUE
            else now - link.lastHidInputAtMs
        val effectiveQuality = when {
            !controllerConnected || hidAge > 1_000L -> 0
            hidAge > 250L -> minOf(link?.linkQualityPercent ?: 0, 40)
            hidAge > 80L -> minOf(link?.linkQualityPercent ?: 0, 75)
            else -> link?.linkQualityPercent ?: 0
        }
        return DiagnosticsSnapshot(
            controllerConnected, latestInput.batteryPercent, effectiveQuality,
            if (hidAge == Long.MAX_VALUE) -1L else hidAge,
            link?.lastHidGapMs ?: 0L,
            link?.lastInputDispatchDurationMs ?: 0L,
            link?.droppedInputPackets ?: 0L,
            if (lastStreamRelayAtMs == 0L) -1L else now - lastStreamRelayAtMs,
            link?.lastOutputStallMs ?: 0L,
            link?.lastIncidentType ?: HciUsbController.INCIDENT_NONE,
            if (link == null || link.lastIncidentAtMs == 0L) -1L else now - link.lastIncidentAtMs,
            link?.lastIncidentDurationMs ?: 0L,
            smartRecoveryInProgress, adapterRecoveryPerformed,
            synchronized(incidentHistoryLines) { incidentHistoryLines.joinToString("\n") }
        )
    }

    @JvmStatic fun forgetDevice(address: String) {
        val normalized = address.uppercase()
        synchronized(devicesByAddress) {
            // Ignore the delayed disconnect/device callbacks generated by this
            // explicit forget operation. Otherwise they immediately recreate
            // the row which the user has just removed.
            ignoredDeviceCallbacksUntil[normalized] =
                SystemClock.elapsedRealtime() + FORGET_CALLBACK_GUARD_MS
            deviceLastSeenAt.remove(normalized)
            devicesByAddress.remove(normalized)
        }
        if (activeControllerAddress == normalized) activeControllerAddress = null
        controller?.forget(normalized)
        keyPrefs().edit()
            .remove(normalized)
            .remove("name_$normalized")
            .remove("class_$normalized")
            .apply()
        updateStatus(s(R.string.dualsense_bridge_status_forgotten))
    }

    @JvmStatic fun addInputListener(listener: InputListener) { inputListeners += listener }
    @JvmStatic fun removeInputListener(listener: InputListener) { inputListeners -= listener }
    @JvmStatic fun addStateListener(listener: StateListener) { stateListeners += listener }
    @JvmStatic fun removeStateListener(listener: StateListener) { stateListeners -= listener }

    @JvmStatic fun getDevices(): List<HciUsbController.HciDevice> =
        synchronized(devicesByAddress) { devicesByAddress.values.toList() }

    @JvmStatic fun getLog(): String = synchronized(logLines) { logLines.joinToString("\n") }

    @JvmStatic fun clearIncidentHistory() {
        synchronized(incidentHistoryLines) { incidentHistoryLines.clear() }
        notifyState()
    }

    /** Latest complete DualSense state for stream watchdog recovery. */
    @JvmStatic fun getLatestInputSnapshot(): DualSenseInput = latestInput

    @JvmStatic fun sendRumble(lowFrequency: Short, highFrequency: Short): Boolean {
        val left = (lowFrequency.toInt() ushr 8) and 0xFF
        val right = (highFrequency.toInt() ushr 8) and 0xFF
        return updateOutput { it.copy(leftRumble = left, rightRumble = right) }
    }

    @JvmStatic fun sendNativeBluetoothHaptics(haptics: ByteArray,
                                               speakerOpus: ByteArray?,
                                               speakerOnly: Boolean = false): Boolean =
        runCatching {
            controller?.sendNativeBluetoothHaptics(haptics, speakerOpus, speakerOnly) == true
        }.getOrDefault(false)

    @JvmStatic fun stopNativeBluetoothHaptics() {
        runCatching { controller?.stopNativeBluetoothHaptics() }
    }

    /** Short-lived stream UI state; recovery itself does not depend on this UI. */
    @JvmStatic fun getRecoveryOverlaySnapshot(): RecoveryOverlaySnapshot {
        val age = (SystemClock.elapsedRealtime() - recoveryOverlayStageAtMs).coerceAtLeast(0L)
        val stage = when {
            recoveryOverlayStage == RECOVERY_OVERLAY_RECONNECTED && !controllerConnected ->
                RECOVERY_OVERLAY_NONE
            recoveryOverlayStage == RECOVERY_OVERLAY_READY &&
                age > RECOVERY_OVERLAY_READY_TIMEOUT_MS -> RECOVERY_OVERLAY_NONE
            recoveryOverlayStage == RECOVERY_OVERLAY_RECONNECTED &&
                age > RECOVERY_OVERLAY_SUCCESS_TIMEOUT_MS -> RECOVERY_OVERLAY_NONE
            else -> recoveryOverlayStage
        }
        return RecoveryOverlaySnapshot(stage, age)
    }

    @JvmStatic fun setBluetoothMicrophoneCapture(enabled: Boolean): Boolean =
        runCatching { controller?.setNativeBluetoothMicrophoneCapture(enabled) == true }
            .getOrDefault(false)

    @JvmStatic fun setBluetoothHeadsetRoute(enabled: Boolean): Boolean =
        runCatching { controller?.setNativeBluetoothHeadsetRoute(enabled) == true }
            .getOrDefault(false)

    @JvmStatic fun sendLed(red: Byte, green: Byte, blue: Byte): Boolean {
        ledOverrideActive = true
        return updateOutput {
            it.copy(red = red.toInt() and 0xFF, green = green.toInt() and 0xFF,
                blue = blue.toInt() and 0xFF)
        }
    }

    @JvmStatic fun sendHostLed(red: Byte, green: Byte, blue: Byte): Boolean =
        sendLed(red, green, blue)

    @JvmStatic fun setAdaptiveTriggers(leftMode: Int, rightMode: Int, strength: Int): Boolean =
        updateOutput {
            it.copy(leftTriggerMode = triggerMode(leftMode),
                rightTriggerMode = triggerMode(rightMode), triggerStrength = strength.coerceIn(0, 100),
                leftTriggerEffect = null, rightTriggerEffect = null)
        }

    @JvmStatic fun setAdaptiveTriggerEffects(eventFlags: Byte, leftType: Byte, rightType: Byte,
                                              left: ByteArray?, right: ByteArray?): Boolean = updateOutput {
        val leftEffect = ByteArray(11)
        val rightEffect = ByteArray(11)
        if ((eventFlags.toInt() and 0x08) != 0) {
            leftEffect[0] = leftType
            left?.copyInto(leftEffect, 1, 0, minOf(10, left.size))
        }
        if ((eventFlags.toInt() and 0x04) != 0) {
            rightEffect[0] = rightType
            right?.copyInto(rightEffect, 1, 0, minOf(10, right.size))
        }
        it.copy(leftTriggerEffect = leftEffect, rightTriggerEffect = rightEffect)
    }

    @JvmStatic fun setPlayerLeds(mask: Int, micLed: Boolean): Boolean = updateOutput {
        it.copy(playerLeds = mask and 0x1F, micLed = microphoneMuted)
    }

    /** Updates the physical mute LED without changing host-owned LED state. */
    @JvmStatic fun setMicrophoneMuted(muted: Boolean): Boolean {
        microphoneMuted = muted
        return updateOutput { it.copy(micLed = muted) }
    }

    private fun updateOutput(transform: (DualSenseOutputConfig) -> DualSenseOutputConfig): Boolean =
        synchronized(outputLock) {
            outputConfig = transform(outputConfig)
            val sentConfig = if (lowBatteryBlinkShowingRed) {
                outputConfig.copy(red = 255, green = 0, blue = 0)
            } else outputConfig
            runCatching { controller?.sendOutput(sentConfig) == true }.getOrDefault(false)
        }

    private fun triggerMode(mode: Int) = when (mode) {
        1 -> TriggerMode.RESISTANCE
        2 -> TriggerMode.VIBRATION
        else -> TriggerMode.OFF
    }

    private fun startController(device: UsbDevice, fastRecovery: Boolean = false) {
        if (!fastRecovery) {
            smartRecoveryInProgress = false
            adapterRecoveryPerformed = false
            watchdogHandler.removeCallbacks(adapterRecovery)
        }
        closeController(null)
        adapter = device
        controllerConnected = false
        latestInput = DualSenseInput.Empty
        inputPacketCount = 0
        lastInputAtMs = 0L
        activeControllerAddress = null
        ledOverrideActive = false
        lastBatteryLedPercent = -1
        stopLowBatteryBlink(false)
        activeHciProfile = selectHciProfile(device)
        appendLog("Opening ${hciProfileName(activeHciProfile)} for USB %04X:%04X".format(
            device.vendorId, device.productId))
        updateStatus(s(R.string.dualsense_bridge_status_initializing))
        val createController = if (activeHciProfile == HCI_PROFILE_CSR)
            ::CsrHciUsbController else ::HciUsbController
        controller = createController(
            usbManager, device,
            { id, args -> s(id, *args) },
            { appendLog(it) },
            { value ->
                updateStatus(value)
            },
            onDeviceCallback@{ item ->
                val normalizedAddress = item.address.uppercase()
                // Inquiry results usually arrive before Remote Name Request has
                // completed. Reuse the last verified name for this MAC so the UI
                // never falls back to "Unknown device" on a later scan. A fresh
                // Remote Name result below still replaces this cached label.
                val rememberedName = rememberedDeviceName(normalizedAddress)
                val namedItem = if (isPlaceholderDeviceName(item.name) && rememberedName != null) {
                    item.copy(name = rememberedName)
                } else {
                    item
                }
                synchronized(devicesByAddress) {
                    val ignoreUntil = ignoredDeviceCallbacksUntil[normalizedAddress] ?: 0L
                    if (SystemClock.elapsedRealtime() < ignoreUntil) {
                        return@onDeviceCallback
                    }
                    ignoredDeviceCallbacksUntil.remove(normalizedAddress)
                }
                val active = namedItem.state == s(R.string.dualsense_bridge_state_connected_hid) ||
                    namedItem.state == s(R.string.dualsense_bridge_state_connected_live)
                val inactive = namedItem.state == s(R.string.dualsense_bridge_state_disconnected)
                val displayed = if (inactive) namedItem.copy(rssi = null) else namedItem
                synchronized(devicesByAddress) {
                    devicesByAddress[normalizedAddress] = displayed
                    if (active || namedItem.state == s(R.string.dualsense_bridge_state_available)) {
                        deviceLastSeenAt[normalizedAddress] = SystemClock.elapsedRealtime()
                    }
                }
                // Only a real Remote Name result may update the cache. Generic
                // pairing/unknown labels must never overwrite a useful name.
                if (!isPlaceholderDeviceName(item.name)) saveDevice(item)
                if (active) {
                    activeControllerAddress = namedItem.address
                }
                else if (inactive && namedItem.address == activeControllerAddress) {
                    markControllerDisconnected(namedItem.state)
                    // The dongle demonstrably reconnects reliably only after its
                    // host/page/L2CAP state has been reinitialized. Do that while
                    // the controller is off, before it can make the next incoming
                    // connection attempt, rather than repairing a failed attempt.
                    smartRecoveryInProgress = false
                    adapterRecoveryPerformed = false
                    setRecoveryOverlayStage(RECOVERY_OVERLAY_DISCONNECTED)
                    watchdogHandler.removeCallbacks(adapterRecovery)
                    watchdogHandler.removeCallbacks(prepareAdapterForReconnect)
                    watchdogHandler.postDelayed(
                        prepareAdapterForReconnect, RECONNECT_PREPARE_DELAY_MS)
                }
                notifyState()
            },
            { packet ->
                DualSenseInputParser.parse(packet.payload)?.let { input ->
                    val firstUsableInput = !controllerConnected
                    latestInput = input
                    inputPacketCount++
                    controllerConnected = true
                    if (headphonesConnected != input.headphonesConnected) {
                        headphonesConnected = input.headphonesConnected
                        setBluetoothHeadsetRoute(headphonesConnected)
                        appendLog(if (headphonesConnected) {
                            "DualSense BT headset connected"
                        } else {
                            "DualSense BT headset disconnected"
                        })
                    }
                    if (smartRecoveryInProgress) {
                        smartRecoveryInProgress = false
                        adapterRecoveryPerformed = false
                        watchdogHandler.removeCallbacks(adapterRecovery)
                        appendLog(s(R.string.dualsense_bridge_status_recovery_success))
                    }
                    if (recoveryOverlayStage != RECOVERY_OVERLAY_NONE &&
                        recoveryOverlayStage != RECOVERY_OVERLAY_RECONNECTED
                    ) {
                        setRecoveryOverlayStage(RECOVERY_OVERLAY_RECONNECTED)
                    }
                    lastInputAtMs = SystemClock.elapsedRealtime()
                    if (firstUsableInput) {
                        // A parsed input report is the only authoritative signal
                        // that the complete radio → ACL → L2CAP → HID path works.
                        // Replace any stale intermediate text such as
                        // "waiting for input reports" at this exact transition.
                        status = s(R.string.dualsense_bridge_status_connected)
                        appendLog(s(R.string.dualsense_bridge_state_connected_hid))
                        activeControllerAddress?.let { address ->
                            synchronized(devicesByAddress) {
                                devicesByAddress[address]?.let { device ->
                                    devicesByAddress[address] = device.copy(
                                        state = s(R.string.dualsense_bridge_state_connected_hid)
                                    )
                                }
                            }
                        }
                        notifyState()
                        // A bridge controller can connect after the stream has
                        // already started. Re-evaluate the selected microphone
                        // source then, rather than requiring a reconnect.
                        Game.instance?.refreshDualSenseMicrophoneCapture()
                    }
                    updateBatteryLedIfNeeded(input.batteryPercent)
                    updateLowBatteryBlinkState(input.batteryPercent)
                    inputListeners.forEach { it.onInput(input) }
                }
            },
            { opus, sequence ->
                noteBluetoothMicrophoneActivity()
                DualSenseMicrophoneBridge.onBluetoothOpusFrame(opus)
                DualSenseBluetoothMicrophoneTest.onBluetoothOpusFrame(opus, sequence)
            },
            { address -> keyPrefs().getString(address, null) },
            { address, key -> keyPrefs().edit().putString(address, key).apply() }
        ).also { it.start() }
    }

    private fun beginSmartRecovery() {
        // Never restart or rebuild the link while the first HID session is still
        // being negotiated. The standalone implementation simply waits here and
        // reliably reaches its first input report.
        if (!controllerConnected && lastInputAtMs == 0L) return
        if (smartRecoveryInProgress) return
        smartRecoveryInProgress = true
        adapterRecoveryPerformed = false
        setRecoveryOverlayStage(RECOVERY_OVERLAY_LINK_REPAIR)
        appendLog(s(R.string.dualsense_bridge_status_link_recovery))
        controller?.requestLinkRecovery()
        watchdogHandler.removeCallbacks(adapterRecovery)
        watchdogHandler.postDelayed(adapterRecovery, ADAPTER_RECOVERY_DELAY_MS)
    }

    private fun closeController(message: String?) {
        watchdogHandler.removeCallbacks(prepareAdapterForReconnect)
        stopLowBatteryBlink(false)
        runCatching { controller?.close() }
        controller = null
        adapter = null
        controllerConnected = false
        headphonesConnected = false
        latestInput = DualSenseInput.Empty
        lastInputAtMs = 0L
        activeControllerAddress = null
        message?.let(::updateStatus) ?: notifyState()
    }

    private fun updateStatus(value: String) {
        status = value
        // HciUsbController emits this only after Page Scan has been enabled, so
        // the controller can safely be turned back on at this point.
        if (recoveryOverlayStage == RECOVERY_OVERLAY_ADAPTER_RESET &&
            value == s(R.string.dualsense_bridge_status_scan_finished)
        ) {
            setRecoveryOverlayStage(RECOVERY_OVERLAY_READY)
        }
        appendLog(value)
        notifyState()
    }

    /**
     * BT microphone frames replace the regular DualSense state report while
     * capture is active. They are still authoritative HID traffic on the live
     * interrupt channel, so they must keep the connection watchdog alive.
     *
     * This is throttled because the microphone arrives at audio cadence; the
     * UI only needs a heartbeat, not 100 main-thread jobs per second.
     */
    private fun noteBluetoothMicrophoneActivity() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBluetoothMicrophoneActivityMs < BLUETOOTH_MIC_ACTIVITY_UI_INTERVAL_MS) return
        lastBluetoothMicrophoneActivityMs = now
        watchdogHandler.post {
            lastInputAtMs = now
            if (!controllerConnected) {
                controllerConnected = true
                status = s(R.string.dualsense_bridge_status_connected)
                activeControllerAddress?.let { address ->
                    synchronized(devicesByAddress) {
                        devicesByAddress[address]?.let { device ->
                            devicesByAddress[address] = device.copy(
                                state = s(R.string.dualsense_bridge_state_connected_hid)
                            )
                        }
                    }
                }
                notifyState()
            }
        }
    }

    private fun setRecoveryOverlayStage(stage: Int) {
        recoveryOverlayStage = stage
        recoveryOverlayStageAtMs = SystemClock.elapsedRealtime()
    }

    private fun updateBatteryLedIfNeeded(percent: Int) {
        if (!batteryLedEnabled ||
            ledOverrideActive || percent < 0 || percent == lastBatteryLedPercent) return
        lastBatteryLedPercent = percent
        val clamped = percent.coerceIn(0, 100)
        val red: Int
        val green: Int
        if (clamped <= 50) {
            red = 255
            green = clamped * 255 / 50
        }
        else {
            red = (100 - clamped) * 255 / 50
            green = 255
        }
        updateOutput { it.copy(red = red, green = green, blue = 0) }
    }

    private fun shouldBlinkForLowBattery(): Boolean =
        controllerConnected && latestInput.batteryPercent in 0..LOW_BATTERY_THRESHOLD_PERCENT &&
            lowBatteryBlinkEnabled

    private fun updateLowBatteryBlinkState(percent: Int) {
        val shouldBlink = controllerConnected && percent in 0..LOW_BATTERY_THRESHOLD_PERCENT &&
            lowBatteryBlinkEnabled
        if (shouldBlink && !lowBatteryBlinkActive) {
            lowBatteryBlinkActive = true
            lowBatteryBlinkPhase = 0
            watchdogHandler.removeCallbacks(lowBatteryBlink)
            watchdogHandler.post(lowBatteryBlink)
        }
        else if (!shouldBlink && lowBatteryBlinkActive) {
            stopLowBatteryBlink(true)
        }
    }

    private fun stopLowBatteryBlink(restoreLightbar: Boolean) {
        watchdogHandler.removeCallbacks(lowBatteryBlink)
        val wasShowingRed = lowBatteryBlinkShowingRed
        lowBatteryBlinkActive = false
        lowBatteryBlinkShowingRed = false
        lowBatteryBlinkPhase = 0
        if (restoreLightbar && wasShowingRed) sendOutputSnapshot(false)
    }

    private fun sendOutputSnapshot(forceRed: Boolean) {
        synchronized(outputLock) {
            val config = if (forceRed) outputConfig.copy(red = 255, green = 0, blue = 0)
                else outputConfig
            runCatching { controller?.sendOutput(config) }
        }
    }

    private fun markControllerDisconnected(reason: String) {
        if (!controllerConnected && lastInputAtMs == 0L) return
        controllerConnected = false
        headphonesConnected = false
        lastInputAtMs = 0L
        latestInput = DualSenseInput.Empty
        activeControllerAddress?.let { address ->
            synchronized(devicesByAddress) {
                devicesByAddress[address]?.let {
                    devicesByAddress[address] = it.copy(rssi = null,
                        state = s(R.string.dualsense_bridge_state_saved_disconnected))
                }
            }
        }
        status = reason
        appendLog(reason)
        notifyState()
    }

    private fun expireStaleDiscoveredDevices() {
        val now = SystemClock.elapsedRealtime()
        var changed = false
        synchronized(devicesByAddress) {
            val iterator = devicesByAddress.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val device = entry.value
                val lastSeen = deviceLastSeenAt[entry.key] ?: continue
                if (!device.paired &&
                    device.state == s(R.string.dualsense_bridge_state_available) &&
                    now - lastSeen >= DISCOVERED_DEVICE_EXPIRY_MS
                ) {
                    iterator.remove()
                    deviceLastSeenAt.remove(entry.key)
                    changed = true
                }
            }
        }
        if (changed) notifyState()
    }

    private fun clearStaleAvailableDevices() {
        synchronized(devicesByAddress) {
            val iterator = devicesByAddress.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!entry.value.paired) {
                    deviceLastSeenAt.remove(entry.key)
                    iterator.remove()
                }
            }
        }
        notifyState()
    }

    private fun appendLog(value: String) {
        LimeLog.info("DualSenseBridge: $value")
        synchronized(logLines) {
            logLines.addLast(value)
            while (logLines.size > 160) logLines.removeFirst()
        }
        if (isDiagnosticIncident(value)) recordIncident(value)
    }

    private fun isDiagnosticIncident(value: String): Boolean =
        value.contains("Disconnection Complete", ignoreCase = true) ||
            value.contains("HID radio/USB input gap", ignoreCase = true) ||
            value.contains("App input dispatch stall", ignoreCase = true) ||
            value.contains("Input queue overrun", ignoreCase = true) ||
            value.contains("DualSense output USB stall", ignoreCase = true) ||
            value.contains("DualSense output write failed", ignoreCase = true) ||
            value.contains("L2CAP signaling error", ignoreCase = true) ||
            value.contains("ACL carry buffer full", ignoreCase = true) ||
            value.contains("USB stream", ignoreCase = true) ||
            value.startsWith("HIBA:", ignoreCase = true)

    private fun recordIncident(value: String) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val timestamp = DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString()
        val link = controller?.getDiagnostics()
        val hidAge = if (link == null || link.lastHidInputAtMs == 0L) -1L
            else (nowElapsed - link.lastHidInputAtMs).coerceAtLeast(0L)
        val relayAge = if (lastStreamRelayAtMs == 0L) -1L
            else (nowElapsed - lastStreamRelayAtMs).coerceAtLeast(0L)
        val details = if (link == null) {
            "link unavailable"
        } else {
            "link ${link.linkQualityPercent}% · HID ${if (hidAge < 0) "n/a" else "${hidAge}ms"}" +
                " · gap ${link.lastHidGapMs}ms · dropped ${link.droppedInputPackets}" +
                " · dispatch ${link.lastInputDispatchDurationMs}ms" +
                " · relay ${if (relayAge < 0) "n/a" else "${relayAge}ms"}" +
                " · output ${link.lastOutputStallMs}ms"
        }
        synchronized(incidentHistoryLines) {
            val line = "$timestamp  $value\n   $details"
            if (incidentHistoryLines.lastOrNull() == line) return
            incidentHistoryLines.addLast(line)
            while (incidentHistoryLines.size > 4) incidentHistoryLines.removeFirst()
        }
    }

    private fun notifyState() = stateListeners.forEach { it.onStateChanged() }
    private fun s(id: Int, vararg args: Any): String = appContext.getString(id, *args)
    private fun ensureInitialized(context: Context) { if (!initialized) initialize(context) }
    private fun keyPrefs() = appContext.getSharedPreferences("dualsense_hci_keys", Context.MODE_PRIVATE)

    private fun rememberedDeviceName(address: String): String? =
        keyPrefs().getString("name_$address", null)?.takeUnless(::isPlaceholderDeviceName)

    private fun isPlaceholderDeviceName(name: String?): Boolean = name.isNullOrBlank() ||
        name == s(R.string.dualsense_bridge_unknown_device) ||
        name == s(R.string.dualsense_bridge_paired_name)

    private fun saveDevice(device: HciUsbController.HciDevice) {
        keyPrefs().edit().putString("name_${device.address}", device.name)
            .putString("class_${device.address}", device.deviceClass).apply()
    }

    private fun loadSavedDevices() {
        val prefs = keyPrefs()
        prefs.all.keys.filter { it.matches(Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")) }.forEach { address ->
            devicesByAddress[address] = HciUsbController.HciDevice(address,
                prefs.getString("name_$address", s(R.string.dualsense_bridge_paired_name))
                    ?: s(R.string.dualsense_bridge_paired_name),
                prefs.getString("class_$address", "2508ED") ?: "2508ED",
                null, true, s(R.string.dualsense_bridge_state_saved))
        }
    }

    data class DiagnosticsSnapshot(
        val connected: Boolean,
        val batteryPercent: Int,
        val linkQualityPercent: Int,
        val hidInputAgeMs: Long,
        val lastHidGapMs: Long,
        val appDispatchDurationMs: Long,
        val droppedInputPackets: Long,
        val streamRelayAgeMs: Long,
        val outputStallMs: Long,
        val lastIncidentType: String,
        val lastIncidentAgeMs: Long,
        val lastIncidentDurationMs: Long,
        val recoveryInProgress: Boolean,
        val adapterRecoveryPerformed: Boolean,
        val recentIncidentLog: String
    )

    data class RecoveryOverlaySnapshot(
        val stage: Int,
        val ageMs: Long
    )

    private const val DISCOVERED_DEVICE_EXPIRY_MS = 30_000L
    private const val CSR_VENDOR_ID = 0x0A12
    private const val CSR_PRODUCT_ID = 0x0001
    private const val FORGET_CALLBACK_GUARD_MS = 5_000L
    private const val RECONNECT_PREPARE_DELAY_MS = 350L
    private const val RECOVERY_OVERLAY_READY_TIMEOUT_MS = 20_000L
    private const val RECOVERY_OVERLAY_SUCCESS_TIMEOUT_MS = 1_100L
    const val RECOVERY_OVERLAY_NONE = 0
    const val RECOVERY_OVERLAY_DISCONNECTED = 1
    const val RECOVERY_OVERLAY_LINK_REPAIR = 2
    const val RECOVERY_OVERLAY_ADAPTER_RESET = 3
    const val RECOVERY_OVERLAY_READY = 4
    const val RECOVERY_OVERLAY_RECONNECTED = 5

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
