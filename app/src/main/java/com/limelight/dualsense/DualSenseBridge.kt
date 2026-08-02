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
import com.limelight.R
import java.util.concurrent.CopyOnWriteArraySet

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
    private var outputConfig = DualSenseOutputConfig()
    @Volatile private var ledOverrideActive = false
    @Volatile private var batteryLedEnabled = true
    @Volatile private var lowBatteryBlinkEnabled = true
    @Volatile private var connectionOverlayEnabled = false
    @Volatile private var lastStreamRelayAtMs = 0L
    private var lastBatteryLedPercent = -1
    @Volatile private var lowBatteryBlinkActive = false
    @Volatile private var lowBatteryBlinkShowingRed = false
    private var lowBatteryBlinkPhase = 0
    private val watchdogHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastInputAtMs = 0L
    @Volatile private var activeControllerAddress: String? = null
    @Volatile private var smartRecoveryInProgress = false
    @Volatile private var adapterRecoveryPerformed = false
    private const val INPUT_TIMEOUT_MS = 2500L
    // Preserve and rebuild the live Bluetooth link first. A complete adapter
    // restart is deliberately delayed as the final fallback.
    private const val ADAPTER_RECOVERY_DELAY_MS = 15_000L
    private val adapterRecovery = Runnable {
        if (!smartRecoveryInProgress || controllerConnected) return@Runnable
        val device = adapter ?: return@Runnable
        adapterRecoveryPerformed = true
        updateStatus(s(R.string.dualsense_bridge_status_adapter_recovery))
        startController(device, true)
    }
    private val connectionWatchdog = object : Runnable {
        override fun run() {
            if (controllerConnected && lastInputAtMs != 0L &&
                SystemClock.elapsedRealtime() - lastInputAtMs > INPUT_TIMEOUT_MS) {
                recordIncident(s(R.string.dualsense_diag_input_timeout_history,
                    SystemClock.elapsedRealtime() - lastInputAtMs))
                beginSmartRecovery()
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
        closeController(s(R.string.dualsense_bridge_status_resetting))
        scan(activity)
    }

    @JvmStatic fun disconnectBridge() = closeController(s(R.string.dualsense_bridge_status_stopped))

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
        controller?.disconnect(normalized)
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
        it.copy(playerLeds = mask and 0x1F, micLed = micLed)
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
        updateStatus(s(R.string.dualsense_bridge_status_initializing))
        controller = HciUsbController(
            usbManager, device,
            { id, args -> s(id, *args) },
            { appendLog(it) },
            { value ->
                updateStatus(value)
            },
            { item ->
                val normalizedAddress = item.address.uppercase()
                synchronized(devicesByAddress) {
                    val ignoreUntil = ignoredDeviceCallbacksUntil[normalizedAddress] ?: 0L
                    if (SystemClock.elapsedRealtime() < ignoreUntil) {
                        return@HciUsbController
                    }
                    ignoredDeviceCallbacksUntil.remove(normalizedAddress)
                }
                val active = item.state == s(R.string.dualsense_bridge_state_connected_hid) ||
                    item.state == s(R.string.dualsense_bridge_state_connected_live)
                val inactive = item.state == s(R.string.dualsense_bridge_state_disconnected)
                val displayed = if (inactive) item.copy(rssi = null) else item
                synchronized(devicesByAddress) {
                    devicesByAddress[normalizedAddress] = displayed
                    if (active || item.state == s(R.string.dualsense_bridge_state_available)) {
                        deviceLastSeenAt[normalizedAddress] = SystemClock.elapsedRealtime()
                    }
                }
                if (item.name != s(R.string.dualsense_bridge_unknown_device)) saveDevice(item)
                if (active) {
                    activeControllerAddress = item.address
                }
                else if (inactive && item.address == activeControllerAddress) {
                    beginSmartRecovery()
                    markControllerDisconnected(item.state)
                }
                notifyState()
            },
            { packet ->
                DualSenseInputParser.parse(packet.payload)?.let { input ->
                    val firstUsableInput = !controllerConnected
                    latestInput = input
                    inputPacketCount++
                    controllerConnected = true
                    if (smartRecoveryInProgress) {
                        smartRecoveryInProgress = false
                        adapterRecoveryPerformed = false
                        watchdogHandler.removeCallbacks(adapterRecovery)
                        appendLog(s(R.string.dualsense_bridge_status_recovery_success))
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
                    }
                    updateBatteryLedIfNeeded(input.batteryPercent)
                    updateLowBatteryBlinkState(input.batteryPercent)
                    inputListeners.forEach { it.onInput(input) }
                }
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
        appendLog(s(R.string.dualsense_bridge_status_link_recovery))
        controller?.requestLinkRecovery()
        watchdogHandler.removeCallbacks(adapterRecovery)
        watchdogHandler.postDelayed(adapterRecovery, ADAPTER_RECOVERY_DELAY_MS)
    }

    private fun closeController(message: String?) {
        stopLowBatteryBlink(false)
        runCatching { controller?.close() }
        controller = null
        adapter = null
        controllerConnected = false
        latestInput = DualSenseInput.Empty
        lastInputAtMs = 0L
        activeControllerAddress = null
        message?.let(::updateStatus) ?: notifyState()
    }

    private fun updateStatus(value: String) {
        status = value
        appendLog(value)
        notifyState()
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

    private const val DISCOVERED_DEVICE_EXPIRY_MS = 30_000L
    private const val FORGET_CALLBACK_GUARD_MS = 5_000L

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
