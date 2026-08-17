package com.limelight.dualsense

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import com.limelight.LimeLog
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local, bridge-only DualSense Bluetooth microphone diagnostic.
 *
 * It receives the exact 71-byte Opus frames that the HCI parser exposes, uses
 * an isolated native decoder, records the resulting PCM, then plays it through
 * Android's normal media route. It does not send data to Apollo or change the
 * stream microphone path.
 */
object DualSenseBluetoothMicrophoneTest {
    private const val SAMPLE_RATE = 48_000
    private const val MAX_RECORDING_SAMPLES = SAMPLE_RATE * 20
    private val recording = AtomicBoolean(false)
    private val liveMonitoring = AtomicBoolean(false)
    private val stopWorker = AtomicBoolean(false)
    private val frames = ArrayBlockingQueue<CapturedFrame>(48)
    private val monitorFrames = ArrayBlockingQueue<ByteArray>(24)
    private val recordedChunks = ArrayList<ShortArray>()
    private val dataLock = Any()

    @Volatile private var worker: Thread? = null
    @Volatile private var monitorWorker: Thread? = null
    @Volatile private var playbackThread: Thread? = null
    @Volatile private var recordedSamples = 0
    @Volatile private var receivedFrames = 0L
    @Volatile private var decodedFrames = 0L
    @Volatile private var droppedFrames = 0L
    @Volatile private var decodeErrors = 0L
    @Volatile private var concealedFrames = 0L
    @Volatile private var smallestPacketSamples = Int.MAX_VALUE
    @Volatile private var largestPacketSamples = 0
    @Volatile private var smallestDecodedSamples = Int.MAX_VALUE
    @Volatile private var largestDecodedSamples = 0
    @Volatile private var recordingStartedAtElapsedMs = 0L
    @Volatile private var nextCaptureTimingLogAtElapsedMs = 0L
    @Volatile private var status = "Idle"

    @JvmStatic fun isRecording(): Boolean = recording.get()
    @JvmStatic fun isLiveMonitoring(): Boolean = liveMonitoring.get()
    @JvmStatic fun hasRecording(): Boolean = recordedSamples > 0

    /** Wall-clock duration since Start recording, independent of received audio. */
    @JvmStatic fun recordingElapsedMs(): Long = if (recording.get() && recordingStartedAtElapsedMs != 0L) {
        (SystemClock.elapsedRealtime() - recordingStartedAtElapsedMs).coerceAtLeast(0L)
    } else 0L

    /** Duration represented by real decoded 48 kHz PCM, excluding missing frames. */
    @JvmStatic fun capturedDurationMs(): Long = recordedSamples.toLong() * 1000L / SAMPLE_RATE

    @JvmStatic fun status(): String {
        if (!recording.get()) return status
        val elapsed = recordingElapsedMs()
        val captured = capturedDurationMs()
        val behind = (elapsed - captured).coerceAtLeast(0L)
        return "Recording… elapsed ${elapsed} ms · captured ${captured} ms · behind ${behind} ms · " +
                "$receivedFrames frames · Opus ${packetSamplesLabel()} → PCM ${decodedSamplesLabel()} · " +
                "$concealedFrames concealed · $decodeErrors decode errors · $droppedFrames dropped"
    }

    @JvmStatic fun startRecording(): Boolean {
        if (recording.get()) return true
        if (liveMonitoring.get()) {
            status = "Stop the live monitor before starting a recording."
            return false
        }
        if (!DualSenseBridge.controllerConnected) {
            status = "Connect a DualSense through the Bluetooth bridge first."
            return false
        }
        if (DualSenseAudioBridge.isStreamActive()) {
            status = "End the active stream before running this isolated microphone test."
            return false
        }
        stopPlayback()
        frames.clear()
        synchronized(dataLock) { recordedChunks.clear() }
        recordedSamples = 0
        receivedFrames = 0
        decodedFrames = 0
        droppedFrames = 0
        decodeErrors = 0
        concealedFrames = 0
        smallestPacketSamples = Int.MAX_VALUE
        largestPacketSamples = 0
        smallestDecodedSamples = Int.MAX_VALUE
        largestDecodedSamples = 0
        recordingStartedAtElapsedMs = SystemClock.elapsedRealtime()
        nextCaptureTimingLogAtElapsedMs = recordingStartedAtElapsedMs + CAPTURE_TIMING_LOG_INTERVAL_MS
        DualSenseMicrophoneNative.resetBluetoothOpusMonitor()
        // This only enables the controller's BT mic report path. The monitor
        // consumes the reports locally and never starts microphone forwarding.
        if (!DualSenseBridge.setBluetoothMicrophoneCapture(true)) {
            status = "Could not arm the controller microphone."
            return false
        }
        stopWorker.set(false)
        recording.set(true)
        status = "Recording raw controller Bluetooth microphone…"
        worker = Thread(::decodeLoop, "DualSenseBtMicMonitor").apply {
            isDaemon = true
            start()
        }
        return true
    }

    @JvmStatic fun stopRecording() {
        if (!recording.getAndSet(false)) return
        stopWorker.set(true)
        worker?.interrupt()
        worker = null
        // The diagnostic is never allowed while a game stream is active, so
        // it owns this microphone enable. Close it explicitly instead of
        // leaving the controller in an audio-report state after recording.
        DualSenseBridge.setBluetoothMicrophoneCapture(false)
        val elapsed = if (recordingStartedAtElapsedMs != 0L) {
            (SystemClock.elapsedRealtime() - recordingStartedAtElapsedMs).coerceAtLeast(0L)
        } else 0L
        val captured = capturedDurationMs()
        recordingStartedAtElapsedMs = 0L
        status = if (recordedSamples > 0) {
                    "Recorded: elapsed ${elapsed} ms · captured ${captured} ms · " +
                    "behind ${(elapsed - captured).coerceAtLeast(0L)} ms. Ready to play."
        } else {
            "No controller microphone frames were captured in ${elapsed} ms."
        }
        LimeLog.info("DualSense BT mic diagnostic capture: elapsed=${elapsed}ms captured=${captured}ms " +
                "behind=${(elapsed - captured).coerceAtLeast(0L)}ms " +
                "realFrames=$decodedFrames received=$receivedFrames concealed=$concealedFrames " +
                "opus=${packetSamplesLabel()} pcm=${decodedSamplesLabel()} " +
                "decodeErrors=$decodeErrors queueDrops=$droppedFrames")
    }

    /**
     * Plays decoded BT microphone packets as they arrive. Unlike the recording
     * playback path it has no stored PCM and no timing reconstruction, so it
     * exposes loss or cadence problems directly at the controller→HCI edge.
     */
    @JvmStatic fun startLiveMonitor(context: Context): Boolean {
        if (liveMonitoring.get()) return true
        if (recording.get()) {
            status = "Stop recording before starting the live monitor."
            return false
        }
        if (!DualSenseBridge.controllerConnected) {
            status = "Connect a DualSense through the Bluetooth bridge first."
            return false
        }
        if (DualSenseAudioBridge.isStreamActive()) {
            status = "End the active stream before running the isolated microphone monitor."
            return false
        }
        stopPlayback()
        monitorFrames.clear()
        decodeErrors = 0
        droppedFrames = 0
        if (!DualSenseBridge.setBluetoothMicrophoneCapture(true)) {
            status = "Could not arm the controller microphone."
            return false
        }
        stopWorker.set(false)
        liveMonitoring.set(true)
        status = "Live monitor active: controller microphone → phone speaker…"
        monitorWorker = Thread({ liveMonitorLoop(context.applicationContext) },
            "DualSenseBtMicLiveMonitor").apply {
            isDaemon = true
            start()
        }
        return true
    }

    @JvmStatic fun stopLiveMonitor() {
        if (!liveMonitoring.getAndSet(false)) return
        stopWorker.set(true)
        monitorWorker?.interrupt()
        monitorWorker = null
        monitorFrames.clear()
        DualSenseBridge.setBluetoothMicrophoneCapture(false)
        status = "Live monitor stopped."
    }

    /** Called from the HCI reader after it has isolated a tagged mic frame. */
    @JvmStatic fun onBluetoothOpusFrame(opus: ByteArray, sequence: Int) {
        if (opus.isEmpty()) return
        if (recording.get()) {
            receivedFrames++
            val frame = CapturedFrame(opus.copyOf(), sequence and 0xFF)
            if (!frames.offer(frame)) {
                frames.poll()
                if (frames.offer(frame)) droppedFrames++
            }
        }
        if (liveMonitoring.get()) {
            val copy = opus.copyOf()
            if (!monitorFrames.offer(copy)) {
                monitorFrames.poll()
                if (monitorFrames.offer(copy)) droppedFrames++
            }
        }
    }

    @JvmStatic fun playRecording(context: Context): Boolean {
        val pcm = synchronized(dataLock) {
            if (recordedSamples <= 0) return false
            val result = ShortArray(recordedSamples)
            var offset = 0
            for (chunk in recordedChunks) {
                chunk.copyInto(result, offset)
                offset += chunk.size
            }
            result
        }
        stopPlayback()
        status = "Playing local recording through Android media output…"
        playbackThread = Thread({ play(context.applicationContext, pcm) },
                "DualSenseBtMicPlayback").apply {
            isDaemon = true
            start()
        }
        return true
    }

    @JvmStatic fun clear() {
        stopRecording()
        stopLiveMonitor()
        stopPlayback()
        synchronized(dataLock) { recordedChunks.clear() }
        recordedSamples = 0
        status = "Recording cleared."
    }

    @JvmStatic fun stop() {
        stopRecording()
        stopLiveMonitor()
        stopPlayback()
    }

    private fun liveMonitorLoop(context: Context) {
        var track: AudioTrack? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            track = createPlaybackTrack() ?: return
            track.play()
            while (!stopWorker.get() && liveMonitoring.get()) {
                val opus = monitorFrames.take()
                val pcm = DualSenseMicrophoneNative.decodeBluetoothOpusForMonitor(opus)
                if (pcm == null || pcm.isEmpty()) {
                    decodeErrors++
                    continue
                }
                var offset = 0
                while (offset < pcm.size && liveMonitoring.get() && !stopWorker.get()) {
                    val written = track.write(pcm, offset, pcm.size - offset,
                        AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) break
                    offset += written
                }
            }
        } catch (_: InterruptedException) {
            // Normal stop.
        } catch (error: Throwable) {
            LimeLog.warning("DualSense BT microphone live monitor stopped: ${error.message}")
            status = "Live monitor failed: ${error.message ?: "unknown error"}"
        } finally {
            try { track?.pause() } catch (_: Throwable) { }
            try { track?.flush() } catch (_: Throwable) { }
            track?.release()
            liveMonitoring.set(false)
        }
    }

    private fun decodeLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (!stopWorker.get()) {
                val frame = frames.take()
                val declaredSamples = DualSenseMicrophoneNative.bluetoothOpusFrameSamples(frame.opus)
                if (declaredSamples > 0) {
                    smallestPacketSamples = minOf(smallestPacketSamples, declaredSamples)
                    largestPacketSamples = maxOf(largestPacketSamples, declaredSamples)
                }
                val pcm = DualSenseMicrophoneNative.decodeBluetoothOpusForMonitor(frame.opus)
                if (pcm == null || pcm.isEmpty()) {
                    decodeErrors++
                    continue
                }
                smallestDecodedSamples = minOf(smallestDecodedSamples, pcm.size)
                largestDecodedSamples = maxOf(largestDecodedSamples, pcm.size)
                decodedFrames++
                appendDecoded(pcm)
                logCaptureTimingIfDue()
            }
        } catch (_: InterruptedException) {
            // Normal stop.
        } catch (error: Throwable) {
            LimeLog.warning("DualSense BT microphone monitor stopped: ${error.message}")
            status = "Recording failed: ${error.message ?: "unknown error"}"
        } finally {
            recording.set(false)
        }
    }

    private fun appendDecoded(pcm: ShortArray?) {
        if (pcm == null || pcm.isEmpty()) {
            decodeErrors++
            return
        }
        synchronized(dataLock) {
            val available = MAX_RECORDING_SAMPLES - recordedSamples
            if (available <= 0) {
                recording.set(false)
                stopWorker.set(true)
                status = "20 second capture limit reached. Ready to play."
                return
            }
            val kept = if (pcm.size <= available) pcm else pcm.copyOf(available)
            recordedChunks.add(kept)
            recordedSamples += kept.size
        }
    }

    /** Emits wall-clock versus captured-PCM time once per second. */
    private fun logCaptureTimingIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now < nextCaptureTimingLogAtElapsedMs) return
        nextCaptureTimingLogAtElapsedMs = now + CAPTURE_TIMING_LOG_INTERVAL_MS
        val elapsed = recordingElapsedMs()
        val captured = capturedDurationMs()
        LimeLog.info("DualSense BT mic capture timeline: elapsed=${elapsed}ms " +
                "captured=${captured}ms behind=${(elapsed - captured).coerceAtLeast(0L)}ms " +
                "frames=$decodedFrames received=$receivedFrames queueDrops=$droppedFrames")
    }

    private fun play(context: Context, pcm: ShortArray) {
        val track = createPlaybackTrack() ?: return
        try {
            track.play()
            var offset = 0
            while (offset < pcm.size && !Thread.currentThread().isInterrupted) {
                val written = track.write(pcm, offset, pcm.size - offset,
                        AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            status = "Playback complete."
            LimeLog.info("DualSense BT mic diagnostic playback complete: " +
                    "samples=${pcm.size} duration=${pcm.size * 1000 / SAMPLE_RATE}ms")
        } catch (error: Throwable) {
            status = "Playback failed: ${error.message ?: "unknown error"}"
        } finally {
            try { track.pause() } catch (_: Throwable) { }
            try { track.flush() } catch (_: Throwable) { }
            track.release()
        }
    }

    private fun createPlaybackTrack(): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            status = "Android does not support 48 kHz mono playback."
            return null
        }
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(maxOf(minBuffer, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (error: Throwable) {
            status = "Could not create local playback: ${error.message}"
            null
        }
    }

    private fun stopPlayback() {
        playbackThread?.interrupt()
        playbackThread = null
    }

    private fun packetSamplesLabel(): String = when {
        largestPacketSamples <= 0 -> "?"
        smallestPacketSamples == largestPacketSamples -> largestPacketSamples.toString()
        else -> "$smallestPacketSamples–$largestPacketSamples"
    }

    private fun decodedSamplesLabel(): String = when {
        largestDecodedSamples <= 0 -> "?"
        smallestDecodedSamples == largestDecodedSamples -> largestDecodedSamples.toString()
        else -> "$smallestDecodedSamples–$largestDecodedSamples"
    }

    private data class CapturedFrame(val opus: ByteArray, val sequence: Int)

    private const val CAPTURE_TIMING_LOG_INTERVAL_MS = 1_000L
}
