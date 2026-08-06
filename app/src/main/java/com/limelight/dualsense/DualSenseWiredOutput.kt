package com.limelight.dualsense

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.example.usbbtonandroid.DualSenseOutputConfig
import com.limelight.LimeLog
import com.limelight.binding.input.driver.DualSenseController
import java.util.concurrent.Executors

/** Native HID feedback transport for a DualSense connected directly over USB. */
object DualSenseWiredOutput {
    private const val ACTION_USB_PERMISSION = "com.limelight.DUALSENSE_WIRED_OUTPUT_PERMISSION"
    private const val SONY_VENDOR_ID = 0x054c
    private val PRODUCT_IDS = setOf(0x0ce6, 0x0df2)

    private lateinit var appContext: Context
    private lateinit var usbManager: UsbManager
    private val outputExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "DualSenseWiredOutput").apply { isDaemon = true }
    }
    private val stateLock = Any()
    private var state = DualSenseOutputConfig(red = 0, green = 0, blue = 0, playerLeds = 0)
    private var initialized = false
    @Volatile private var permissionRequestPending = false
    private var headphonesConnected = false
    @Volatile private var nativeAudioRouteActive = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    permissionRequestPending = false
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        sendCurrentState()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> requestUsbPermissionIfNeeded()
            }
        }
    }

    @JvmStatic fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
            val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION") appContext.registerReceiver(permissionReceiver, filter)
            }
            initialized = true
            requestUsbPermissionIfNeeded()
        }
    }

    @JvmStatic fun isPermissionRequestPending(): Boolean = permissionRequestPending

    @JvmStatic fun requestUsbPermissionIfNeeded() {
        if (!initialized || permissionRequestPending) return
        val device = findDevice() ?: return
        if (usbManager.hasPermission(device)) return
        permissionRequestPending = true
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val pending = PendingIntent.getBroadcast(appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        try {
            usbManager.requestPermission(device, pending)
        } catch (e: Throwable) {
            permissionRequestPending = false
            LimeLog.warning("DualSense USB permission request failed: ${e.message}")
        }
    }

    @JvmStatic fun isDirectDualSense(vendorId: Int, productId: Int): Boolean =
        vendorId == SONY_VENDOR_ID && productId in PRODUCT_IDS

    @JvmStatic fun sendRumble(lowFrequency: Short, highFrequency: Short): Boolean = update {
        it.copy(leftRumble = (lowFrequency.toInt() ushr 8) and 0xff,
            rightRumble = (highFrequency.toInt() ushr 8) and 0xff)
    }

    @JvmStatic fun sendLed(red: Byte, green: Byte, blue: Byte): Boolean = update {
        it.copy(red = red.toInt() and 0xff, green = green.toInt() and 0xff,
            blue = blue.toInt() and 0xff)
    }

    @JvmStatic fun setPlayerLeds(mask: Int, micLed: Boolean): Boolean = update {
        it.copy(playerLeds = mask and 0x1f, micLed = micLed)
    }

    @JvmStatic fun setHeadphonesConnected(connected: Boolean): Boolean {
        synchronized(stateLock) { headphonesConnected = connected }
        outputExecutor.execute {
            sendAudioRouteSequence(connected)
            val snapshot = synchronized(stateLock) { state.copy(
                leftTriggerEffect = state.leftTriggerEffect?.copyOf(),
                rightTriggerEffect = state.rightTriggerEffect?.copyOf()) }
            send(snapshot)
        }
        LimeLog.info("DualSense wired audio route: " + if (connected) "headset jack" else "controller speaker")
        return true
    }

    private fun sendAudioRouteSequence(jack: Boolean) {
        // Exact PS5CTBRO sequence. These must remain separate reports: physical
        // audio route -> music-rumble route -> DSP wake. Combining the valid
        // flags into one output report leaves channels 3/4 asleep on some pads.
        val route = ByteArray(63)
        route[0] = 0x02
        route[1] = 0xf3.toByte()
        route[5] = 0xff.toByte()
        if (!jack) {
            route[6] = 0xff.toByte()
            route[8] = 0xff.toByte()
        }
        val firstRoute = DualSenseController.sendActiveReport(route)
        if (!jack) {
            Thread.sleep(40)
            DualSenseController.sendActiveReport(route)
        }

        val musicRumbleRoute = ByteArray(63)
        musicRumbleRoute[0] = 0x02
        musicRumbleRoute[1] = 0xe0.toByte()
        musicRumbleRoute[5] = 0x7f
        if (jack) {
            musicRumbleRoute[6] = 0x00
            musicRumbleRoute[8] = 0x00
        } else {
            musicRumbleRoute[6] = 0xff.toByte()
            musicRumbleRoute[7] = 0x40
            musicRumbleRoute[8] = 0x30
        }
        val rumbleRoute = DualSenseController.sendActiveReport(musicRumbleRoute)
        Thread.sleep(35)

        val wake = ByteArray(63)
        wake[0] = 0x02
        wake[2] = 0x15
        wake[39] = 0x03
        wake[42] = 0x02
        wake[44] = 0x24
        val wakeSent = DualSenseController.sendActiveReport(wake)
        Thread.sleep(35)

        // The loud speaker route is the final PS5CTBRO confirmation. The jack
        // path intentionally remains muted on the internal speaker.
        if (!jack) DualSenseController.sendActiveReport(route)
        nativeAudioRouteActive = firstRoute && rumbleRoute && wakeSent
        LimeLog.info("DualSense native audio wake: route=$firstRoute rumble=$rumbleRoute " +
                "wake=$wakeSent jack=$jack")
    }

    private fun sendMusicRumbleWake(): Boolean {
        val wake = ByteArray(63)
        wake[0] = 0x02
        wake[2] = 0x15
        wake[39] = 0x03
        wake[42] = 0x02
        wake[44] = 0x24
        return DualSenseController.sendActiveReport(wake)
    }

    @JvmStatic fun reactivateNativeAudioRoute() {
        val jack = synchronized(stateLock) { headphonesConnected }
        sendAudioRouteSequence(jack)
    }

    @JvmStatic fun setAdaptiveTriggerEffects(eventFlags: Byte, leftType: Byte, rightType: Byte,
                                               left: ByteArray?, right: ByteArray?): Boolean = update {
        val leftEffect = it.leftTriggerEffect?.copyOf() ?: ByteArray(11)
        val rightEffect = it.rightTriggerEffect?.copyOf() ?: ByteArray(11)
        if ((eventFlags.toInt() and 0x08) != 0) {
            leftEffect.fill(0)
            leftEffect[0] = leftType
            left?.copyInto(leftEffect, 1, 0, minOf(10, left.size))
        }
        if ((eventFlags.toInt() and 0x04) != 0) {
            rightEffect.fill(0)
            rightEffect[0] = rightType
            right?.copyInto(rightEffect, 1, 0, minOf(10, right.size))
        }
        it.copy(leftTriggerEffect = leftEffect, rightTriggerEffect = rightEffect)
    }

    private fun update(transform: (DualSenseOutputConfig) -> DualSenseOutputConfig): Boolean {
        if (!initialized) return false
        synchronized(stateLock) { state = transform(state) }
        sendCurrentState()
        return findDevice() != null
    }

    private fun sendCurrentState() {
        if (!initialized) return
        val snapshot = synchronized(stateLock) { state.copy(
            leftTriggerEffect = state.leftTriggerEffect?.copyOf(),
            rightTriggerEffect = state.rightTriggerEffect?.copyOf()) }
        outputExecutor.execute { send(snapshot) }
    }

    private fun send(config: DualSenseOutputConfig) {
        val report = buildReport(config)
        if (DualSenseController.sendActiveReport(report)) {
            // PS5CTBRO keeps the native 3/4 music-rumble path alive with a
            // separate wake report after every merged HID output. LED, trigger,
            // and host feedback reports can otherwise leave the DSP path asleep.
            if (nativeAudioRouteActive) sendMusicRumbleWake()
            return
        }
        val device = findDevice() ?: return
        if (!usbManager.hasPermission(device)) {
            requestPermission(device)
            return
        }
        val hid = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull {
            it.interfaceClass == UsbConstants.USB_CLASS_HID
        } ?: return
        val connection = usbManager.openDevice(device) ?: return
        try {
            // Send the HID output report over endpoint zero without claiming the
            // interface. claimInterface(..., true) detaches Android's hid-playstation
            // driver and makes the controller disappear/reappear for every report.
            val sent = connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or 0x01,
                0x09, (0x02 shl 8) or 0x02, hid.id,
                report, report.size, 250)
            if (sent != report.size) {
                LimeLog.warning("DualSense wired HID output failed: $sent/${report.size}")
            }
        } catch (e: Throwable) {
            LimeLog.warning("DualSense wired HID output error: ${e.message}")
        } finally {
            connection.close()
        }
    }

    private fun buildReport(config: DualSenseOutputConfig): ByteArray {
        val report = ByteArray(63)
        report[0] = 0x02
        // Enable compatible rumble, both adaptive triggers, mic LED, audio routing,
        // lightbar and player LEDs. The state is merged so one host event never
        // clears an unrelated effect from a previous event.
        // Keep the native ISO/audio path enabled, but temporarily select the
        // compatible motors while a traditional rumble command is non-zero.
        // A zero-motor update restores the pure native HD haptics selection.
        val compatibleRumbleActive = config.leftRumble != 0 || config.rightRumble != 0
        report[1] = (if (compatibleRumbleActive) 0xff else 0xfe).toByte()
        report[2] = 0x15
        report[3] = config.rightRumble.coerceIn(0, 255).toByte()
        report[4] = config.leftRumble.coerceIn(0, 255).toByte()
        val jack = synchronized(stateLock) { headphonesConnected }
        report[5] = if (jack) 0x7f else 0x00
        report[6] = if (jack) 0x00 else 0xff.toByte()
        report[7] = 0x40
        report[8] = if (jack) 0x00 else 0xff.toByte()
        report[9] = if (config.micLed) 1 else 0
        writeTrigger(report, 11, config.rightTriggerEffect)
        writeTrigger(report, 22, config.leftTriggerEffect)
        report[39] = 0x03
        report[42] = 0x02
        report[43] = 0
        report[44] = ((config.playerLeds and 0x1f) or 0x20).toByte()
        report[45] = config.red.coerceIn(0, 255).toByte()
        report[46] = config.green.coerceIn(0, 255).toByte()
        report[47] = config.blue.coerceIn(0, 255).toByte()
        return report
    }

    private fun writeTrigger(report: ByteArray, offset: Int, effect: ByteArray?) {
        repeat(11) { report[offset + it] = 0 }
        effect?.copyInto(report, offset, 0, minOf(11, effect.size))
    }

    private fun findDevice(): UsbDevice? = usbManager.deviceList.values.firstOrNull {
        isDirectDualSense(it.vendorId, it.productId)
    }

    private fun requestPermission(device: UsbDevice) {
        requestUsbPermissionIfNeeded()
    }
}
