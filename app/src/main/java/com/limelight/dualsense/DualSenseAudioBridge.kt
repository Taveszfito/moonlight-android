package com.limelight.dualsense

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import com.limelight.LimeLog
import kotlin.math.abs

/** Receives Apollo Extended's native DualSense four-channel audio stream. */
object DualSenseAudioBridge {
    private const val ACTION_USB_PERMISSION = "com.limelight.DUALSENSE_AUDIO_USB_PERMISSION"
    private const val SONY_VENDOR_ID = 0x054c
    private val DUALSENSE_PRODUCT_IDS = setOf(0x0ce6, 0x0df2)
    private const val USB_AUDIO_STREAMING_SUBCLASS = 0x02

    private lateinit var appContext: Context
    private lateinit var usbManager: UsbManager
    @Volatile private var initialized = false
    @Volatile private var usbRouteActive = false
    @Volatile private var streamActive = false
    @Volatile private var mode = "auto"
    private var audioConnection: UsbDeviceConnection? = null
    private var audioInterface: UsbInterface? = null
    private var lastSequence = -1
    private var lastPacketAtMs = 0L
    private var packetsReceived = 0L
    private var packetsLost = 0L
    private var btAccumulatorLeft = 0L
    private var btAccumulatorRight = 0L
    private var btAccumulatorFrames = 0
    private var lastBtRumbleAtMs = 0L

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (streamActive && mode != "off" &&
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                openUsbRoute(device)
            }
        }
    }

    @JvmStatic @Synchronized fun configure(selectedMode: String?) {
        val normalized = when (selectedMode) {
            "usb_speaker", "usb_headset", "haptics_only", "off" -> selectedMode
            else -> "auto"
        }
        if (mode != normalized) closeUsbRoute()
        mode = normalized
        LimeLog.info("DualSense audio mode: $mode")
    }

    @JvmStatic fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION") appContext.registerReceiver(permissionReceiver, filter)
            }
            initialized = true
        }
    }

    @JvmStatic fun receive(controller: Int, sequence: Int, frameCount: Int,
                           channels: Int, flags: Int, pcm: ByteArray?) {
        if (!initialized || pcm == null || channels != 4 || frameCount <= 0 ||
            pcm.size != frameCount * channels * 2) return

        packetsReceived++
        if (lastSequence >= 0) {
            val expected = (lastSequence + 1) and 0xffff
            if (sequence != expected) packetsLost += (sequence - expected) and 0xffff
        }
        lastSequence = sequence
        lastPacketAtMs = SystemClock.elapsedRealtime()
        streamActive = true
        if (mode == "off") return

        if (!usbRouteActive) ensureUsbRoute()
        if (usbRouteActive) {
            val usbPcm = if (mode == "haptics_only") withoutSpeakerChannels(pcm, frameCount) else pcm
            if (DualSenseIsoNative.push(usbPcm) == 0) return
            closeUsbRoute()
        }

        // Bluetooth DualSense exposes no USB isochronous audio function. Keep
        // useful feedback by reducing native haptic channels 3/4 to the two
        // compatible HID rumble actuators at a controlled update rate.
        if (mode == "auto" || mode == "haptics_only") {
            accumulateBluetoothHaptics(pcm, frameCount)
        }
    }

    private fun withoutSpeakerChannels(pcm: ByteArray, frameCount: Int): ByteArray =
        pcm.copyOf().also { output ->
            var offset = 0
            repeat(frameCount) {
                output[offset] = 0
                output[offset + 1] = 0
                output[offset + 2] = 0
                output[offset + 3] = 0
                offset += 8
            }
        }

    private fun accumulateBluetoothHaptics(pcm: ByteArray, frameCount: Int) {
        var offset = 0
        repeat(frameCount) {
            offset += 4 // speaker/jack channels 1 and 2
            btAccumulatorLeft += abs(readS16(pcm, offset))
            offset += 2
            btAccumulatorRight += abs(readS16(pcm, offset))
            offset += 2
        }
        btAccumulatorFrames += frameCount
        val now = SystemClock.elapsedRealtime()
        if (now - lastBtRumbleAtMs < 16 || btAccumulatorFrames == 0) return

        val left = ((btAccumulatorLeft / btAccumulatorFrames) * 2).coerceIn(0, 65535).toInt()
        val right = ((btAccumulatorRight / btAccumulatorFrames) * 2).coerceIn(0, 65535).toInt()
        DualSenseBridge.sendRumble(left.toShort(), right.toShort())
        btAccumulatorLeft = 0
        btAccumulatorRight = 0
        btAccumulatorFrames = 0
        lastBtRumbleAtMs = now
    }

    private fun readS16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) or (data[offset + 1].toInt() shl 8)).toShort().toInt()

    private fun ensureUsbRoute() {
        if (mode == "off") return
        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == SONY_VENDOR_ID && it.productId in DUALSENSE_PRODUCT_IDS
        } ?: return
        if (!usbManager.hasPermission(device)) {
            val permission = PendingIntent.getBroadcast(
                appContext, 0, Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(device, permission)
            return
        }
        openUsbRoute(device)
    }

    @Synchronized private fun openUsbRoute(device: UsbDevice): Boolean {
        if (usbRouteActive) return true
        closeUsbRoute()
        val target = findAudioTarget(device) ?: return false
        sendAudioWakeReport(device, mode)
        val connection = usbManager.openDevice(device) ?: return false
        if (!connection.claimInterface(target, true)) {
            connection.close()
            return false
        }
        val endpoint = (0 until target.endpointCount).map { target.getEndpoint(it) }.firstOrNull {
            it.direction == UsbConstants.USB_DIR_OUT &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
        } ?: run {
            connection.releaseInterface(target)
            connection.close()
            return false
        }

        val result = DualSenseIsoNative.start(connection.fileDescriptor, target.id,
            target.alternateSetting, endpoint.address)
        if (result != 0) {
            connection.releaseInterface(target)
            connection.close()
            LimeLog.warning("DualSense USB ISO start failed: $result")
            return false
        }
        audioConnection = connection
        audioInterface = target
        usbRouteActive = true
        LimeLog.info("DualSense USB audio route active: IF=${target.id} ALT=${target.alternateSetting} EP=${endpoint.address}")
        return true
    }

    private fun findAudioTarget(device: UsbDevice): UsbInterface? =
        (0 until device.interfaceCount).map { device.getInterface(it) }
            .filter { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    it.interfaceSubclass == USB_AUDIO_STREAMING_SUBCLASS &&
                    it.alternateSetting != 0 }
            .filter { intf -> (0 until intf.endpointCount).any {
                val endpoint = intf.getEndpoint(it)
                endpoint.direction == UsbConstants.USB_DIR_OUT &&
                        endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
            } }
            .maxByOrNull { intf -> (0 until intf.endpointCount).maxOf { intf.getEndpoint(it).maxPacketSize } }

    private fun sendAudioWakeReport(device: UsbDevice, selectedMode: String) {
        val connection = usbManager.openDevice(device) ?: return
        val hid = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { intf ->
            intf.interfaceClass == UsbConstants.USB_CLASS_HID &&
                    (0 until intf.endpointCount).any {
                        val ep = intf.getEndpoint(it)
                        ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_INT
                    }
        } ?: run { connection.close(); return }
        try {
            if (!connection.claimInterface(hid, true)) return
            val endpoint = (0 until hid.endpointCount).map { hid.getEndpoint(it) }.first {
                it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
            val report = ByteArray(63)
            report[0] = 0x02.toByte()
            report[2] = 0x15.toByte()
            if (selectedMode == "haptics_only") {
                // Proven PS5CTBRO music-rumble wake path without routing audible PCM.
                report[1] = 0x00.toByte()
            } else {
                report[1] = 0xf3.toByte()
                report[5] = 0xff.toByte()
                report[7] = 0x40.toByte()
                if (selectedMode == "usb_headset") {
                    report[6] = 0x00.toByte()
                    report[8] = 0x00.toByte()
                } else {
                    report[6] = 0xff.toByte()
                    report[8] = 0xff.toByte()
                }
            }
            report[39] = 0x03.toByte()
            report[42] = 0x02.toByte()
            report[44] = 0x24.toByte()
            connection.bulkTransfer(endpoint, report, report.size, 500)
            connection.releaseInterface(hid)
        } catch (_: Throwable) {
        } finally {
            connection.close()
        }
    }

    @JvmStatic @Synchronized fun stop() {
        streamActive = false
        closeUsbRoute()
        DualSenseBridge.sendRumble(0.toShort(), 0.toShort())
        lastSequence = -1
        btAccumulatorLeft = 0
        btAccumulatorRight = 0
        btAccumulatorFrames = 0
    }

    @Synchronized private fun closeUsbRoute() {
        if (usbRouteActive) DualSenseIsoNative.stop()
        usbRouteActive = false
        try { audioInterface?.let { audioConnection?.releaseInterface(it) } } catch (_: Throwable) {}
        try { audioConnection?.close() } catch (_: Throwable) {}
        audioInterface = null
        audioConnection = null
    }

    @JvmStatic fun diagnostics(): String {
        val native = if (usbRouteActive) DualSenseIsoNative.diagnostics() else longArrayOf(0, 0, 0, 0)
        val age = if (lastPacketAtMs == 0L) -1 else SystemClock.elapsedRealtime() - lastPacketAtMs
        return "mode=$mode packets=$packetsReceived lost=$packetsLost age=${age}ms usb=${native[0]} queue=${native[1]} underruns=${native[2]} droppedBytes=${native[3]}"
    }
}

object DualSenseIsoNative {
    @JvmStatic external fun start(fd: Int, interfaceNumber: Int, alternateSetting: Int, endpoint: Int): Int
    @JvmStatic external fun push(pcm: ByteArray): Int
    @JvmStatic external fun stop()
    @JvmStatic external fun diagnostics(): LongArray
}
