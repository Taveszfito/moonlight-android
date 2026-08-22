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
import com.limelight.binding.input.driver.DualSenseController
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Receives Apollo Extended's native DualSense four-channel audio stream. */
object DualSenseAudioBridge {
    private const val ACTION_USB_PERMISSION = "com.limelight.DUALSENSE_AUDIO_USB_PERMISSION"
    private const val SONY_VENDOR_ID = 0x054c
    private val DUALSENSE_PRODUCT_IDS = setOf(0x0ce6, 0x0df2)
    private const val USB_AUDIO_STREAMING_SUBCLASS = 0x02
    // The DualSense hardware route is already configured for maximum clean
    // speaker output. The amplified internal membrane reaches clipping well
    // before full-scale PCM; a headset does not have that limitation.
    private const val CLEAN_SPEAKER_GAIN_PERCENT = 100
    // UI 100% on the controller membrane means the former 30% PCM gain:
    // -10.46 dB relative to the old full-scale slider.
    private const val INTERNAL_SPEAKER_MAX_GAIN_PERCENT = 30

    private lateinit var appContext: Context
    private lateinit var usbManager: UsbManager
    @Volatile private var initialized = false
    @Volatile private var usbRouteActive = false
    @Volatile private var wiredControllerActive = false
    @Volatile private var streamActive = false

    /** Used by bridge diagnostics to avoid competing with a real media stream. */
    @JvmStatic fun isStreamActive(): Boolean = streamActive
    @Volatile private var mode = "auto"
    @Volatile private var controllerVolume = 100
    private var audioConnection: UsbDeviceConnection? = null
    private var audioInterface: UsbInterface? = null
    private var expectedSequence = -1
    private val pendingPackets = HashMap<Int, AudioPacket>()
    private var lastPacketAtMs = 0L
    private var packetsReceived = 0L
    private var packetsLost = 0L
    private var packetsOutOfOrder = 0L
    private var btNativeReports = 0L
    private var btNativeDrops = 0L
    private var btSpeakerReports = 0L
    private var btSpeakerEncodeFailures = 0L
    private var lastBtDiagnosticAtMs = 0L
    @Volatile private var headsetStreamOpus: ByteArray? = null
    @Volatile private var lastNativeBtReportAtMs = 0L
    private val headsetStreamPcm = ByteArray(2_048)
    private var headsetStreamPcmPosition = 0
    @Volatile private var standardStreamRoutedToHeadset = false
    private val btNativeResampler = NativeBluetoothHapticsResampler({ haptics, speaker ->
        if (speaker != null) btSpeakerReports++
        val sent = if (DualSenseBridge.controllerConnected) {
            DualSenseBridge.sendNativeBluetoothHaptics(haptics, speaker)
        } else {
            lastNativeBtReportAtMs = SystemClock.elapsedRealtime()
            DirectDualSenseBt.sendNativeAudio(appContext, haptics,
                if (DirectDualSenseBt.isHeadsetRoute()) headsetStreamOpus ?: speaker else speaker)
        }
        if (sent) {
            btNativeReports++
        } else {
            btNativeDrops++
        }
    }, onDirectSilence = {
        DirectDualSenseBt.discardNativeAudioBacklogOnSilence()
    })

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

    @JvmStatic @Synchronized fun configure(selectedMode: String?, selectedVolume: Int) {
        val normalized = when (selectedMode) {
            "usb_speaker", "usb_headset", "haptics_only", "off" -> selectedMode
            else -> "auto"
        }
        if (mode != normalized) closeUsbRoute()
        mode = normalized
        controllerVolume = selectedVolume.coerceIn(0, 100)
        DualSenseBridge.setBluetoothHeadsetRoute(
            mode == "usb_headset" || (mode == "auto" && DualSenseBridge.headphonesConnected))
        if (!DualSenseBridge.controllerConnected) {
            // Auto mode is owned by the physical jack poller. Do not reset its
            // live detection to speaker every time a stream is configured.
            when (mode) {
                "usb_headset" -> DirectDualSenseBt.setHeadsetRoute(appContext, true)
                "usb_speaker", "haptics_only", "off" ->
                    DirectDualSenseBt.setHeadsetRoute(appContext, false)
            }
        }
        LimeLog.info("DualSense audio mode: $mode, controller volume: $controllerVolume%")
    }

    @JvmStatic fun setControllerVolume(selectedVolume: Int) {
        controllerVolume = selectedVolume.coerceIn(0, 100)
    }

    /** Route the main stream exclusively to a directly-connected DualSense jack. */
    @JvmStatic @Synchronized fun routeStandardStreamAudio(pcm: ShortArray, channels: Int): Boolean {
        if (!initialized || channels <= 0 || !DirectDualSenseBt.isHeadsetRoute()) {
            if (standardStreamRoutedToHeadset) {
                standardStreamRoutedToHeadset = false
                LimeLog.info("Main stream audio returned to Android AudioTrack")
            }
            headsetStreamPcmPosition = 0
            headsetStreamOpus = null
            return false
        }
        if (!standardStreamRoutedToHeadset) {
            standardStreamRoutedToHeadset = true
            LimeLog.info("Main stream audio routed exclusively to DualSense headset")
        }

        var sourceFrame = 0
        val sourceFrames = pcm.size / channels
        while (sourceFrame < sourceFrames) {
            val left = pcm[sourceFrame * channels]
            val right = if (channels > 1) pcm[sourceFrame * channels + 1] else left
            headsetStreamPcm[headsetStreamPcmPosition++] = left.toByte()
            headsetStreamPcm[headsetStreamPcmPosition++] = (left.toInt() shr 8).toByte()
            headsetStreamPcm[headsetStreamPcmPosition++] = right.toByte()
            headsetStreamPcm[headsetStreamPcmPosition++] = (right.toInt() shr 8).toByte()
            sourceFrame++
            if (headsetStreamPcmPosition == headsetStreamPcm.size) {
                val encoded = DualSenseBtAudioNative.encodeSpeaker(headsetStreamPcm)
                if (encoded != null) {
                    headsetStreamOpus = encoded
                    // Normally Apollo's native controller stream supplies the
                    // 10.67 ms report clock and haptics. Fall back to our own
                    // silent-haptics carrier when that endpoint is absent.
                    if (SystemClock.elapsedRealtime() - lastNativeBtReportAtMs > 50L) {
                        DirectDualSenseBt.sendNativeAudio(appContext, ByteArray(64), encoded, true)
                    }
                } else {
                    btSpeakerEncodeFailures++
                }
                headsetStreamPcmPosition = 0
            }
        }
        return true
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

    @JvmStatic @Synchronized fun receive(controller: Int, sequence: Int, frameCount: Int,
                                         channels: Int, flags: Int, pcm: ByteArray?) {
        if (!initialized || pcm == null || channels != 4 || frameCount <= 0 ||
            pcm.size != frameCount * channels * 2) return

        packetsReceived++
        lastPacketAtMs = SystemClock.elapsedRealtime()
        streamActive = true
        if (expectedSequence < 0) expectedSequence = sequence

        var distance = (sequence - expectedSequence) and 0xffff
        if (distance >= 0x8000) {
            // Capture can restart its sequence without tearing down the stream.
            if (sequence < 64 && expectedSequence > 1024) {
                pendingPackets.clear()
                expectedSequence = sequence
                distance = 0
            } else {
                // Already played (duplicate or too late for the reorder window).
                packetsOutOfOrder++
                return
            }
        }
        if (distance > 0) packetsOutOfOrder++
        pendingPackets.putIfAbsent(sequence, AudioPacket(frameCount, pcm))

        // Wait briefly for a missing 3 ms block. If it really was lost, skip
        // only after enough newer packets prove that waiting would add latency.
        if (!pendingPackets.containsKey(expectedSequence) &&
            pendingPackets.size >= AUDIO_REORDER_WINDOW_PACKETS) {
            val next = pendingPackets.keys.minByOrNull { (it - expectedSequence) and 0xffff }
            if (next != null) {
                packetsLost += (next - expectedSequence) and 0xffff
                expectedSequence = next
            }
        }

        while (true) {
            val packet = pendingPackets.remove(expectedSequence) ?: break
            processPacket(packet.pcm, packet.frameCount)
            expectedSequence = (expectedSequence + 1) and 0xffff
        }
    }

    private fun processPacket(pcm: ByteArray, frameCount: Int) {
        if (mode == "off") return

        // Scale only speaker/jack channels 1/2. Native haptics on channels 3/4
        // must remain bit-identical. This avoids the controller's stateful
        // volume-control output bytes and works for both USB ISO and BT Bridge.
        val volumeAdjustedPcm = applySpeakerGain(pcm, frameCount)

        if (!usbRouteActive) ensureUsbRoute()
        if (usbRouteActive) {
            val usbPcm = if (mode == "haptics_only") withoutSpeakerChannels(volumeAdjustedPcm, frameCount) else volumeAdjustedPcm
            if (DualSenseIsoNative.push(usbPcm) == 0) return
            closeUsbRoute()
        }

        // A wireless DualSense carries the original haptic waveform through its
        // native 3 kHz stereo Bluetooth audio reports. Do not derive rumble
        // amplitudes, envelopes, bass boosts, or any other synthetic feedback.
        if (mode == "auto" || mode == "haptics_only" ||
            (!DualSenseBridge.controllerConnected && DirectDualSenseBt.isConnected(appContext))) {
            btNativeResampler.pushFourChannelPcm(volumeAdjustedPcm, frameCount,
                includeSpeaker = mode != "haptics_only")
            val now = SystemClock.elapsedRealtime()
            if (now - lastBtDiagnosticAtMs >= 2_000) {
                lastBtDiagnosticAtMs = now
                LimeLog.info("DualSense native BT audio: reports=$btNativeReports " +
                    "drops=$btNativeDrops speaker=$btSpeakerReports " +
                    "speakerEncodeFailures=$btSpeakerEncodeFailures " +
                    "packets=$packetsReceived lost=$packetsLost")
            }
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

    private fun ensureUsbRoute() {
        if (mode == "off") return
        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == SONY_VENDOR_ID && it.productId in DUALSENSE_PRODUCT_IDS
        } ?: return
        if (!usbManager.hasPermission(device)) {
            DualSenseWiredOutput.requestUsbPermissionIfNeeded()
            return
        }
        openUsbRoute(device)
    }

    private fun applySpeakerGain(pcm: ByteArray, frameCount: Int): ByteArray {
        val volume = effectiveSpeakerGainPercent()
        return pcm.copyOf().also { output ->
            var offset = 0
            repeat(frameCount) {
                writeScaledS16(output, offset, volume)
                writeScaledS16(output, offset + 2, volume)
                offset += 8
            }
        }
    }

    private fun writeScaledS16(data: ByteArray, offset: Int, volume: Int) {
        val sample = ((data[offset].toInt() and 0xff) or
            (data[offset + 1].toInt() shl 8)).toShort().toInt()
        val divisor = 100 * 100
        val numerator = sample * volume * CLEAN_SPEAKER_GAIN_PERCENT
        val scaled = (numerator + if (sample >= 0) divisor / 2 else -divisor / 2) / divisor
        data[offset] = scaled.toByte()
        data[offset + 1] = (scaled shr 8).toByte()
    }

    private fun writeScaledS16(data: ByteArray, offset: Int, sample: Short) {
        val volume = effectiveSpeakerGainPercent()
        val divisor = 100 * 100
        val numerator = sample.toInt() * volume * CLEAN_SPEAKER_GAIN_PERCENT
        val scaled = (numerator + if (sample >= 0) divisor / 2 else -divisor / 2) / divisor
        data[offset] = scaled.toByte()
        data[offset + 1] = (scaled shr 8).toByte()
    }

    /** Keep the full software range for a headset; cap only the built-in membrane. */
    private fun effectiveSpeakerGainPercent(): Int {
        val internalSpeakerActive =
            (wiredControllerActive && !DualSenseController.getActiveHeadphonesConnected()) ||
                (DualSenseBridge.controllerConnected && !DualSenseBridge.headphonesConnected) ||
                (!DualSenseBridge.controllerConnected && DirectDualSenseBt.isConnected(appContext) &&
                    !DirectDualSenseBt.isHeadsetRoute())
        val maximum = if (internalSpeakerActive) INTERNAL_SPEAKER_MAX_GAIN_PERCENT else 100
        return controllerVolume * maximum / 100
    }

    /**
     * Keep the DualSense's four-channel USB audio clock alive for the complete
     * lifetime of the exclusive wired controller session. The native streamer
     * supplies silence when Apollo has no PCM queued, just like PS5CTBRO with
     * both native haptics and speaker playback enabled.
     */
    @JvmStatic fun onWiredControllerConnected() {
        wiredControllerActive = true
        Thread({
            repeat(20) {
                if (!wiredControllerActive || usbRouteActive || mode == "off") return@Thread
                ensureUsbRoute()
                if (usbRouteActive) return@Thread
                SystemClock.sleep(100)
            }
            if (wiredControllerActive && !usbRouteActive && mode != "off") {
                LimeLog.warning("DualSense USB ISO route did not become active after controller attach")
            }
        }, "DualSenseIsoStartup").apply { isDaemon = true }.start()
    }

    @JvmStatic @Synchronized fun onWiredControllerDisconnected() {
        wiredControllerActive = false
        closeUsbRoute()
    }

    @Synchronized private fun openUsbRoute(device: UsbDevice): Boolean {
        if (usbRouteActive) return true
        closeUsbRoute()
        val target = findAudioTarget(device) ?: return false
        DualSenseWiredOutput.reactivateNativeAudioRoute()
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
        // SETINTERFACE can reset the controller-side USB audio engine. Repeat
        // the proven wake sequence after the isochronous alternate setting is
        // live so channels 1/2 and the haptic actuators on 3/4 stay enabled.
        DualSenseWiredOutput.reactivateNativeAudioRoute()
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
        val report = ByteArray(63)
        report[0] = 0x02.toByte()
        report[2] = 0x15.toByte()
        if (selectedMode == "haptics_only") {
            report[1] = 0x00.toByte()
        } else {
            report[1] = 0xf3.toByte()
            report[5] = 0xff.toByte()
            report[7] = 0x40.toByte()
            if (selectedMode == "usb_headset") {
                report[6] = 0x00.toByte(); report[8] = 0x00.toByte()
            } else {
                report[6] = 0xff.toByte(); report[8] = 0x30.toByte()
            }
        }
        report[39] = 0x03.toByte(); report[42] = 0x02.toByte(); report[44] = 0x24.toByte()
        if (DualSenseController.sendActiveReport(report)) return
        val connection = usbManager.openDevice(device) ?: return
        val hid = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { intf ->
            intf.interfaceClass == UsbConstants.USB_CLASS_HID &&
                    (0 until intf.endpointCount).any {
                        val ep = intf.getEndpoint(it)
                        ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_INT
                    }
        } ?: run { connection.close(); return }
        try {
            // Keep Android's HID input driver attached while waking the independent
            // USB audio interface used by native four-channel haptics.
            connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or 0x01,
                0x09, (0x02 shl 8) or 0x02, hid.id,
                report, report.size, 500)
        } catch (_: Throwable) {
        } finally {
            connection.close()
        }
    }

    @JvmStatic @Synchronized fun stop() {
        streamActive = false
        // The physical wired controller needs a continuous four-channel ISO
        // clock even between host audio packets and between stream sessions.
        if (!wiredControllerActive) closeUsbRoute()
        btNativeResampler.stop()
        DualSenseBridge.stopNativeBluetoothHaptics()
        DirectDualSenseBt.stopNativeAudio(appContext)
        expectedSequence = -1
        pendingPackets.clear()
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
        return "mode=$mode packets=$packetsReceived lost=$packetsLost reordered=$packetsOutOfOrder age=${age}ms " +
            "usb=${native[0]} queue=${native[1]} underruns=${native[2]} droppedBytes=${native[3]} " +
            "btNative=$btNativeReports btDrops=$btNativeDrops " +
            "btSpeaker=$btSpeakerReports btSpeakerFailures=$btSpeakerEncodeFailures"
    }

    /**
     * Band-limited conversion from the virtual controller's native USB format
     * (48 kHz, signed 16-bit stereo haptics in channels 3/4) to the physical
     * controller's native Bluetooth report format. Speaker audio is encoded in
     * 10 ms Opus frames and paired with each 64-byte haptics block.
     *
     * This is transport conversion only. The waveform is never rectified,
     * reduced to motor strengths, dynamically compressed, or otherwise shaped.
     */
    private class NativeBluetoothHapticsResampler(
        private val sendReport: (ByteArray, ByteArray?) -> Unit,
        private val onDirectSilence: () -> Unit,
    ) {
        private val left = DoubleArray(FIR_TAPS)
        private val right = DoubleArray(FIR_TAPS)
        private val report = ByteArray(BT_REPORT_HAPTICS_BYTES)
        private val speakerPcm = ByteArray(BT_REPORT_SPEAKER_PCM_BYTES)
        private var latestSpeakerOpus: ByteArray? = null
        private var ringPosition = 0
        private var decimationPhase = 0
        private var reportPosition = 0
        private var speakerPosition = 0
        private var directSilenceSignalled = false
        private var directSilentSpeakerFrames = 0

        fun pushFourChannelPcm(pcm: ByteArray, frameCount: Int, includeSpeaker: Boolean) {
            var offset = 0
            repeat(frameCount) {
                if (includeSpeaker) {
                    pcm.copyInto(speakerPcm, speakerPosition, offset, offset + 4)
                } else {
                    speakerPcm.fill(0, speakerPosition, speakerPosition + 4)
                }
                speakerPosition += 4
                if (speakerPosition == speakerPcm.size) {
                    val speakerIsSilent = speakerPcm.all { it == 0.toByte() }
                    if (!DualSenseBridge.controllerConnected) {
                        if (speakerIsSilent) {
                            directSilentSpeakerFrames++
                        } else {
                            directSilentSpeakerFrames = 0
                            directSilenceSignalled = false
                        }
                        if (directSilentSpeakerFrames >= DIRECT_REBASE_SILENCE_FRAMES &&
                            !directSilenceSignalled) {
                            // A sustained quiet interval is a safe point to discard
                            // accumulated vendor/controller queue drift. Brief gaps
                            // inside music never reconfigure the live decoder.
                            directSilenceSignalled = true
                            onDirectSilence()
                        }
                    }
                    latestSpeakerOpus = if (includeSpeaker) {
                        DualSenseBtAudioNative.encodeSpeaker(speakerPcm).also {
                            if (it == null) btSpeakerEncodeFailures++
                        }
                    } else {
                        null
                    }
                    speakerPosition = 0
                }
                offset += 4
                left[ringPosition] = readS16(pcm, offset).toDouble()
                offset += 2
                right[ringPosition] = readS16(pcm, offset).toDouble()
                offset += 2
                ringPosition = (ringPosition + 1) % FIR_TAPS

                decimationPhase++
                if (decimationPhase == DECIMATION) {
                    decimationPhase = 0
                    report[reportPosition++] = quantize(filter(left))
                    report[reportPosition++] = quantize(filter(right))
                    if (reportPosition == report.size) {
                        val hasHaptics = report.any { it != 0.toByte() }
                        val speaker = if (includeSpeaker) latestSpeakerOpus else null
                        if (hasHaptics || speaker != null) {
                            sendReport(report.copyOf(), speaker)
                        }
                        reportPosition = 0
                    }
                }
            }
        }

        fun stop() {
            if (reportPosition != 0) sendReport(ByteArray(BT_REPORT_HAPTICS_BYTES), null)
            DualSenseBtAudioNative.resetSpeaker()
            left.fill(0.0)
            right.fill(0.0)
            report.fill(0)
            speakerPcm.fill(0)
            latestSpeakerOpus = null
            ringPosition = 0
            decimationPhase = 0
            reportPosition = 0
            speakerPosition = 0
            directSilentSpeakerFrames = 0
            directSilenceSignalled = false
        }

        private fun filter(channel: DoubleArray): Double {
            var sum = 0.0
            var index = if (ringPosition == 0) FIR_TAPS - 1 else ringPosition - 1
            for (tap in COEFFICIENTS.indices) {
                sum += channel[index] * COEFFICIENTS[tap]
                if (--index < 0) index = FIR_TAPS - 1
            }
            return sum
        }

        private fun quantize(sample: Double): Byte =
            // Native Bluetooth haptics use twice the USB PCM normalization.
            // This preserves the waveform and matches the reference transport;
            // it does not synthesize compatible/legacy rumble.
            (sample * 127.0 * BT_HAPTICS_TRANSPORT_GAIN / 32768.0)
                .roundToInt().coerceIn(-128, 127).toByte()

        private fun readS16(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xff) or
                (data[offset + 1].toInt() shl 8)).toShort().toInt()

        companion object {
            // Opus speaker frames are 10 ms each.
            private const val DIRECT_REBASE_SILENCE_FRAMES = 50
            private const val INPUT_RATE = 48_000.0
            private const val CUTOFF_HZ = 1_400.0
            private const val DECIMATION = 16
            private const val FIR_TAPS = 127
            private const val BT_HAPTICS_TRANSPORT_GAIN = 2.0
            private const val BT_REPORT_HAPTICS_BYTES = 64
            // One Bluetooth report spans 512 source frames (10.667 ms). The
            // encoder converts these to one 480-frame Opus packet so playback
            // speed and pitch remain correct on the controller's report clock.
            private const val BT_REPORT_SPEAKER_PCM_BYTES = 2_048

            // Unity-gain Blackman-windowed sinc. The 1.4 kHz cutoff prevents
            // aliasing near the DualSense wireless haptics Nyquist limit.
            private val COEFFICIENTS: DoubleArray = DoubleArray(FIR_TAPS).also { taps ->
                val middle = (FIR_TAPS - 1) / 2.0
                val normalizedCutoff = CUTOFF_HZ / INPUT_RATE
                var sum = 0.0
                for (i in taps.indices) {
                    val x = i - middle
                    val sinc = if (x == 0.0) 2.0 * normalizedCutoff else
                        sin(2.0 * PI * normalizedCutoff * x) / (PI * x)
                    val window = 0.42 - 0.5 * cos(2.0 * PI * i / (FIR_TAPS - 1)) +
                        0.08 * cos(4.0 * PI * i / (FIR_TAPS - 1))
                    taps[i] = sinc * window
                    sum += taps[i]
                }
                for (i in taps.indices) taps[i] /= sum
            }
        }
    }

    private data class AudioPacket(val frameCount: Int, val pcm: ByteArray)

    private const val AUDIO_REORDER_WINDOW_PACKETS = 4
}

object DualSenseIsoNative {
    @JvmStatic external fun start(fd: Int, interfaceNumber: Int, alternateSetting: Int, endpoint: Int): Int
    @JvmStatic external fun push(pcm: ByteArray): Int
    @JvmStatic external fun stop()
    @JvmStatic external fun diagnostics(): LongArray
}

object DualSenseBtAudioNative {
    @JvmStatic external fun encodeSpeaker(pcm: ByteArray): ByteArray?
    @JvmStatic external fun encodeSpeakerSilence(): ByteArray?
    @JvmStatic external fun resetSpeaker()
}
