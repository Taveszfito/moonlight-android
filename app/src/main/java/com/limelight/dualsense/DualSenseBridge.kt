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
import com.example.usbbtonandroid.DualSenseBtOutputBuilder
import com.example.usbbtonandroid.DualSenseInput
import com.example.usbbtonandroid.DualSenseInputParser
import com.example.usbbtonandroid.DualSenseOutputConfig
import com.example.usbbtonandroid.TriggerMode
import com.example.usbbtonandroid.hci.HciUsbController
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide owner of the external USB Bluetooth adapter and DualSense link.
 * Activities only observe/control this object, so configuration changes and
 * stream/menu navigation cannot tear down the sensitive HCI connection.
 */
object DualSenseBridge {
    const val ACTION_USB_PERMISSION = "com.limelight.DUALSENSE_USB_PERMISSION"
    const val HOST_MODE_XBOX = "xbox"
    const val HOST_MODE_PLAYSTATION = "playstation"

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
    private val logLines = ArrayDeque<String>()
    private val outputLock = Any()
    private var outputConfig = DualSenseOutputConfig()
    private val watchdogHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastInputAtMs = 0L
    @Volatile private var activeControllerAddress: String? = null
    private const val INPUT_TIMEOUT_MS = 2500L
    private val connectionWatchdog = object : Runnable {
        override fun run() {
            if (controllerConnected && lastInputAtMs != 0L &&
                SystemClock.elapsedRealtime() - lastInputAtMs > INPUT_TIMEOUT_MS) {
                markControllerDisconnected("Controller connection lost")
            }
            watchdogHandler.postDelayed(this, 500L)
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
                    } else updateStatus("USB adapter permission denied")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scan(null)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = intent.usbDevice()
                    if (detached != null && detached.deviceId == adapter?.deviceId) {
                        closeController("USB Bluetooth adapter disconnected")
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
            watchdogHandler.post(connectionWatchdog)
            loadSavedDevices()
            updateStatus("Ready — connect an external USB Bluetooth adapter")
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
            updateStatus("No compatible USB Bluetooth adapter found")
            return
        }
        if (controller != null && found.deviceId == adapter?.deviceId) {
            updateStatus(if (controllerConnected) "DualSense connected" else "Adapter active — scanning")
            return
        }
        if (usbManager.hasPermission(found)) {
            startController(found)
        } else if (activity != null) {
            updateStatus("Waiting for USB adapter permission…")
            val permission = PendingIntent.getBroadcast(
                appContext, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(found, permission)
        } else {
            updateStatus("Open DualSense Bridge to grant USB permission")
        }
    }

    @JvmStatic fun reconnect(address: String, name: String) {
        controller?.connect(address, name)
            ?: updateStatus("Connect the USB Bluetooth adapter first")
    }

    @JvmStatic fun reset(activity: Activity?) {
        closeController("Resetting adapter…")
        scan(activity)
    }

    @JvmStatic fun disconnectBridge() = closeController("Bridge stopped")

    @JvmStatic fun getHostControllerMode(): String = keyPrefs().getString(
        "host_controller_mode", HOST_MODE_XBOX) ?: HOST_MODE_XBOX

    @JvmStatic fun setHostControllerMode(mode: String) {
        val normalized = if (mode == HOST_MODE_PLAYSTATION) HOST_MODE_PLAYSTATION else HOST_MODE_XBOX
        keyPrefs().edit().putString("host_controller_mode", normalized).apply()
        notifyState()
    }

    @JvmStatic fun forgetDevice(address: String) {
        val normalized = address.uppercase()
        controller?.disconnect(normalized)
        keyPrefs().edit()
            .remove(normalized)
            .remove("name_$normalized")
            .remove("class_$normalized")
            .apply()
        synchronized(devicesByAddress) { devicesByAddress.remove(normalized) }
        updateStatus("Controller forgotten")
    }

    @JvmStatic fun addInputListener(listener: InputListener) { inputListeners += listener }
    @JvmStatic fun removeInputListener(listener: InputListener) { inputListeners -= listener }
    @JvmStatic fun addStateListener(listener: StateListener) { stateListeners += listener }
    @JvmStatic fun removeStateListener(listener: StateListener) { stateListeners -= listener }

    @JvmStatic fun getDevices(): List<HciUsbController.HciDevice> =
        synchronized(devicesByAddress) { devicesByAddress.values.toList() }

    @JvmStatic fun getLog(): String = synchronized(logLines) { logLines.joinToString("\n") }

    @JvmStatic fun sendRumble(lowFrequency: Short, highFrequency: Short): Boolean {
        val left = (lowFrequency.toInt() ushr 8) and 0xFF
        val right = (highFrequency.toInt() ushr 8) and 0xFF
        return updateOutput { it.copy(leftRumble = left, rightRumble = right) }
    }

    @JvmStatic fun sendLed(red: Byte, green: Byte, blue: Byte): Boolean = updateOutput {
        it.copy(red = red.toInt() and 0xFF, green = green.toInt() and 0xFF,
            blue = blue.toInt() and 0xFF)
    }

    @JvmStatic fun setAdaptiveTriggers(leftMode: Int, rightMode: Int, strength: Int): Boolean =
        updateOutput {
            it.copy(leftTriggerMode = triggerMode(leftMode),
                rightTriggerMode = triggerMode(rightMode), triggerStrength = strength.coerceIn(0, 100))
        }

    @JvmStatic fun setPlayerLeds(mask: Int, micLed: Boolean): Boolean = updateOutput {
        it.copy(playerLeds = mask and 0x1F, micLed = micLed)
    }

    private fun updateOutput(transform: (DualSenseOutputConfig) -> DualSenseOutputConfig): Boolean =
        synchronized(outputLock) {
            outputConfig = transform(outputConfig)
            runCatching { controller?.sendOutput(outputConfig) == true }.getOrDefault(false)
        }

    private fun triggerMode(mode: Int) = when (mode) {
        1 -> TriggerMode.RESISTANCE
        2 -> TriggerMode.VIBRATION
        else -> TriggerMode.OFF
    }

    private fun startController(device: UsbDevice) {
        closeController(null)
        adapter = device
        controllerConnected = false
        latestInput = DualSenseInput.Empty
        inputPacketCount = 0
        lastInputAtMs = 0L
        activeControllerAddress = null
        updateStatus("Initializing USB Bluetooth adapter…")
        controller = HciUsbController(
            usbManager, device,
            { appendLog(it) },
            { value ->
                if (value.contains("szétkapcsol", true) ||
                    value.contains("nincs kapcsolat", true) ||
                    value.contains("sikertelen", true)) {
                    markControllerDisconnected(value)
                }
                updateStatus(value)
            },
            { item ->
                val active = item.state.contains("HID aktív", true) ||
                    item.state.contains("élő", true) ||
                    item.state.contains("stabil", true)
                val inactive = item.state.contains("Nincs kapcsolat", true) ||
                    item.state.contains("Sikertelen", true)
                val displayed = if (inactive) item.copy(rssi = null) else item
                synchronized(devicesByAddress) { devicesByAddress[item.address] = displayed }
                if (item.name != "Ismeretlen eszköz") saveDevice(item)
                if (active) {
                    activeControllerAddress = item.address
                }
                else if (inactive && item.address == activeControllerAddress) {
                    markControllerDisconnected(item.state)
                }
                notifyState()
            },
            { packet ->
                DualSenseInputParser.parse(packet.payload)?.let { input ->
                    latestInput = input
                    inputPacketCount++
                    controllerConnected = true
                    lastInputAtMs = SystemClock.elapsedRealtime()
                    inputListeners.forEach { it.onInput(input) }
                }
            },
            { address -> keyPrefs().getString(address, null) },
            { address, key -> keyPrefs().edit().putString(address, key).apply() }
        ).also { it.start() }
    }

    private fun closeController(message: String?) {
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

    private fun markControllerDisconnected(reason: String) {
        if (!controllerConnected && lastInputAtMs == 0L) return
        controllerConnected = false
        lastInputAtMs = 0L
        latestInput = DualSenseInput.Empty
        activeControllerAddress?.let { address ->
            synchronized(devicesByAddress) {
                devicesByAddress[address]?.let {
                    devicesByAddress[address] = it.copy(rssi = null, state = "Saved - disconnected")
                }
            }
        }
        status = reason
        appendLog(reason)
        notifyState()
    }

    private fun appendLog(value: String) {
        synchronized(logLines) {
            logLines.addLast(value)
            while (logLines.size > 160) logLines.removeFirst()
        }
    }

    private fun notifyState() = stateListeners.forEach { it.onStateChanged() }
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
                prefs.getString("name_$address", "Paired DualSense") ?: "Paired DualSense",
                prefs.getString("class_$address", "2508ED") ?: "2508ED",
                null, true, "Saved")
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
