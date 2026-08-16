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
    private var recorder: AudioRecord? = null

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
        stopRequested.set(true)
        try { recorder?.stop() } catch (_: Throwable) { }
        captureThread?.join(300)
        captureThread = null
        try { recorder?.release() } catch (_: Throwable) { }
        recorder = null
        running = false
        DualSenseMicrophoneNative.stop()
    }
}
