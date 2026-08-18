package com.limelight.dualsense

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.limelight.LimeLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ArrayBlockingQueue

/**
 * Captures either the Android device microphone or the USB DualSense audio input,
 * encodes 20 ms Opus frames, and forwards them to Apollo Extended. The controller
 * mute button gates forwarding only; it never changes the physical microphone route.
 */
object DualSenseMicrophoneBridge {
    const val SOURCE_OFF = "off"
    const val SOURCE_DEVICE = "device"
    const val SOURCE_DUALSENSE = "dualsense"

    @Volatile private var selectedSource = SOURCE_OFF
    @Volatile private var muted = false
    @Volatile private var running = false
    private val stopRequested = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var bluetoothDecodeThread: Thread? = null
    private var recorder: AudioRecord? = null
    // The adapter's ACL reader is also responsible for gamepad input. Never do
    // Opus decode or network transmission inline on that real-time USB thread.
    private val bluetoothFrameQueue = ArrayBlockingQueue<ByteArray>(24)
    private var bluetoothFramesDropped = 0L
    private val bluetoothFramesReceived = AtomicLong(0)
    private val bluetoothStreamFramesSent = AtomicLong(0)
    private val bluetoothStreamFailures = AtomicLong(0)
    @Volatile private var bluetoothCaptureStartedAtMs = 0L
    @Volatile private var lastBluetoothFrameAtMs = 0L
    @Volatile private var lastBluetoothStreamSendAtMs = 0L

    @JvmStatic fun configure(source: String?) {
        selectedSource = when (source) {
            SOURCE_DEVICE, SOURCE_DUALSENSE -> source
            else -> SOURCE_OFF
        }
    }

    @JvmStatic fun selectedSource(): String = selectedSource
    @JvmStatic fun isMuted(): Boolean = muted
    @JvmStatic fun isRunning(): Boolean = running

    @JvmStatic fun stopIfUsingDualSense() {
        if (selectedSource == SOURCE_DUALSENSE) stop()
    }

    @JvmStatic fun setMuted(value: Boolean): Boolean {
        if (muted == value) return muted
        muted = value
        DualSenseWiredOutput.setMicrophoneMuted(value)
        DualSenseBridge.setMicrophoneMuted(value)
        LimeLog.info("DualSense microphone forwarding " + if (value) "muted" else "unmuted")
        return muted
    }

    @JvmStatic fun toggleMuted(): Boolean = setMuted(!muted)

    @JvmStatic @Synchronized fun start(context: Context): Boolean {
        stop()
        if (selectedSource == SOURCE_OFF) {
            return false
        }
        if (!DualSenseMicrophoneNative.start()) {
            LimeLog.warning("DualSense microphone Opus encoder failed to start")
            return false
        }

        // The USB bridge is a raw Bluetooth HCI transport, not an Android
        // AudioDevice. Its DualSense microphone returns Opus frames inside BT
        // HID input reports and is decoded by onBluetoothOpusFrame() below.
        if (selectedSource == SOURCE_DUALSENSE && DualSenseBridge.controllerConnected) {
            if (!DualSenseMicrophoneNative.startBluetooth()) {
                DualSenseMicrophoneNative.stop()
                return false
            }
            DualSenseBridge.setMicrophoneMuted(muted)
            DualSenseBridge.setBluetoothMicrophoneCapture(true)
            DualSenseWiredOutput.setMicrophoneMuted(muted)
            bluetoothFrameQueue.clear()
            bluetoothFramesDropped = 0
            bluetoothFramesReceived.set(0)
            bluetoothStreamFramesSent.set(0)
            bluetoothStreamFailures.set(0)
            bluetoothCaptureStartedAtMs = android.os.SystemClock.elapsedRealtime()
            lastBluetoothFrameAtMs = 0L
            lastBluetoothStreamSendAtMs = 0L
            stopRequested.set(false)
            running = true
            bluetoothDecodeThread = Thread(::bluetoothDecodeLoop,
                "DualSenseBluetoothMicrophone").apply {
                isDaemon = true
                start()
            }
            LimeLog.info("DualSense Bridge microphone capture armed")
            return true
        }

        val minBuffer = AudioRecord.getMinBufferSize(48_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            LimeLog.warning("DualSense microphone: unsupported 48 kHz mono capture")
            DualSenseMicrophoneNative.stop()
            return false
        }
        val bufferSize = maxOf(minBuffer * 2, 960 * 2 * 4)
        val audioRecord = createRecorder(bufferSize) ?: run {
            DualSenseMicrophoneNative.stop()
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && selectedSource == SOURCE_DUALSENSE) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val controllerMic = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            if (controllerMic == null) {
                LimeLog.warning("DualSense microphone endpoint not found; waiting for a USB controller audio input")
                audioRecord.release()
                DualSenseMicrophoneNative.stop()
                return false
            }
            if (!audioRecord.setPreferredDevice(controllerMic)) {
                LimeLog.warning("Android refused the DualSense microphone preferred audio route")
            }
        }

        try {
            audioRecord.startRecording()
        } catch (error: Throwable) {
            LimeLog.warning("DualSense microphone capture start failed: ${error.message}")
            audioRecord.release()
            DualSenseMicrophoneNative.stop()
            return false
        }
        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            LimeLog.warning("DualSense microphone capture did not enter recording state")
            audioRecord.release()
            DualSenseMicrophoneNative.stop()
            return false
        }

        recorder = audioRecord
        stopRequested.set(false)
        running = true
        captureThread = Thread({ captureLoop(audioRecord) }, "DualSenseMicrophone").apply {
            isDaemon = true
            start()
        }
        DualSenseWiredOutput.setMicrophoneMuted(muted)
        LimeLog.info("Client microphone capture started: $selectedSource")
        return true
    }

    /** Called only for filtered BT Duplex 0xD4 Opus frames. */
    @JvmStatic fun onBluetoothOpusFrame(opus: ByteArray) {
        if (!running || selectedSource != SOURCE_DUALSENSE || muted || opus.isEmpty()) return
        bluetoothFramesReceived.incrementAndGet()
        lastBluetoothFrameAtMs = android.os.SystemClock.elapsedRealtime()
        // Keep the newest audio when a transient network stall occurs. This is
        // preferable to blocking the ACL reader and losing many later HID/mic
        // packets at the USB boundary.
        if (!bluetoothFrameQueue.offer(opus)) {
            bluetoothFrameQueue.poll()
            if (!bluetoothFrameQueue.offer(opus)) return
            bluetoothFramesDropped++
            if (bluetoothFramesDropped == 1L || bluetoothFramesDropped % 50L == 0L) {
                LimeLog.warning("DualSense BT microphone queue overrun: $bluetoothFramesDropped frame(s) dropped")
            }
        }
    }

    private fun bluetoothDecodeLoop() {
        try {
            while (!stopRequested.get() && running && selectedSource == SOURCE_DUALSENSE) {
                val opus = bluetoothFrameQueue.take()
                // The Bluetooth DualSense microphone uses its own 10 ms Opus
                // framing. Decode it locally, then feed the normal 48 kHz/20 ms
                // Moonlight microphone encoder so the host sees precisely the
                // same stream format as an Android-device microphone capture.
                // This intentionally keeps the controller-radio cadence out of
                // the encrypted host microphone transport.
                if (!muted) {
                    val result = DualSenseMicrophoneNative.decodeBluetoothAndSend(opus)
                    // A successful result of 0 represents one complete 20 ms
                    // Moonlight packet; 1 means the first 10 ms BT half was
                    // buffered locally and is expected.
                    if (result == 0) {
                        bluetoothStreamFramesSent.incrementAndGet()
                        lastBluetoothStreamSendAtMs = android.os.SystemClock.elapsedRealtime()
                    } else if (result < 0) {
                        bluetoothStreamFailures.incrementAndGet()
                    }
                }
            }
        } catch (_: InterruptedException) {
            // Normal shutdown.
        } catch (error: Throwable) {
            LimeLog.warning("DualSense Bluetooth microphone worker stopped: ${error.message}")
        }
    }

    private fun createRecorder(bufferSize: Int): AudioRecord? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48_000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 48_000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        }
    } catch (error: Throwable) {
        LimeLog.warning("DualSense microphone recorder creation failed: ${error.message}")
        null
    }

    private fun captureLoop(audioRecord: AudioRecord) {
        val frame = ShortArray(960)
        var firstPacket = true
        try {
            while (!stopRequested.get()) {
                val read = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioRecord.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                } else {
                    @Suppress("DEPRECATION") audioRecord.read(frame, 0, frame.size)
                }
                if (read != frame.size) continue
                if (!muted) {
                    val result = DualSenseMicrophoneNative.encodeAndSend(frame)
                    if (result == 0 && firstPacket) {
                        firstPacket = false
                        LimeLog.info("Client microphone first packet forwarded")
                    }
                }
            }
        } catch (error: Throwable) {
            LimeLog.warning("DualSense microphone capture stopped unexpectedly: ${error.message}")
        } finally {
            running = false
        }
    }

    @JvmStatic @Synchronized fun stop() {
        if (selectedSource == SOURCE_DUALSENSE) {
            DualSenseBridge.setBluetoothMicrophoneCapture(false)
        }
        stopRequested.set(true)
        bluetoothDecodeThread?.interrupt()
        bluetoothDecodeThread?.join(300)
        bluetoothDecodeThread = null
        bluetoothFrameQueue.clear()
        try { recorder?.stop() } catch (_: Throwable) { }
        captureThread?.join(300)
        captureThread = null
        try { recorder?.release() } catch (_: Throwable) { }
        recorder = null
        running = false
        DualSenseMicrophoneNative.stop()
        DualSenseBridge.setMicrophoneMuted(muted)
    }

    /** Compact, live end-to-end client-side status for the in-stream bridge popup. */
    @JvmStatic fun diagnostics(): String {
        if (!running) return "inactive"
        if (selectedSource != SOURCE_DUALSENSE) return "phone / USB capture active"
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = (now - bluetoothCaptureStartedAtMs).coerceAtLeast(0L)
        val received = bluetoothFramesReceived.get()
        // DualSense Bluetooth microphone packets represent 10 ms each. This
        // is intentionally an estimate: it exposes sustained loss while not
        // pretending the Android-side queue is host acknowledgement.
        val expected = elapsed / 10L
        val estimatedMissing = (expected - received).coerceAtLeast(0L)
        val inputAge = if (lastBluetoothFrameAtMs == 0L) -1L else now - lastBluetoothFrameAtMs
        val sendAge = if (lastBluetoothStreamSendAtMs == 0L) -1L else now - lastBluetoothStreamSendAtMs
        return "BT mic: rx $received/$expected · est. missing $estimatedMissing\n" +
            "queue ${bluetoothFrameQueue.size}/24 · queue drops $bluetoothFramesDropped\n" +
            "stream: ${bluetoothStreamFramesSent.get()} × 20 ms sent · " +
            "failures ${bluetoothStreamFailures.get()}\n" +
            "BT age ${if (inputAge < 0) "n/a" else "${inputAge}ms"} · " +
            "stream age ${if (sendAge < 0) "n/a" else "${sendAge}ms"}"
    }
}
