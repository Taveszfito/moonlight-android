// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.limelight.dualsense

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import com.example.usbbtonandroid.DualSenseBtAudioBuilder
import com.example.usbbtonandroid.DualSenseBtOutputBuilder
import com.example.usbbtonandroid.DualSenseOutputConfig
import com.limelight.LimeLog
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

/**
 * Complete output path for a DualSense paired directly with Android's Bluetooth stack.
 * The external USB Bluetooth bridge remains owned exclusively by [DualSenseBridge].
 */
object DirectDualSenseBt {
    private val lock = Any()
    private val writer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread({
            // Keep HID media above ordinary application work without starving
            // controller input or Android's Bluetooth service threads.
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            task.run()
        }, "DirectDualSenseBt").apply { isDaemon = true }
    }
    private val jackPoller = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "DirectDualSenseBtJack").apply { isDaemon = true }
    }
    private enum class AudioSource { NONE, COMBINED, STANDALONE }
    private class AudioPayload {
        val haptics = ByteArray(DualSenseBtAudioBuilder.HAPTICS_BYTES_PER_REPORT)
        val speaker = ByteArray(200)
        var hasSpeaker = false
        var source = AudioSource.NONE
    }
    private val audioQueueLock = Any()
    private val audioQueue = ArrayDeque<AudioPayload>()
    private val freeAudioPayloads = ArrayDeque<AudioPayload>().apply {
        repeat(AUDIO_PAYLOAD_POOL_SIZE) { addLast(AudioPayload()) }
    }
    private val audioReport = ByteArray(334)
    private val hapticsReport = ByteArray(206)
    private val audioReportCrc = CRC32()
    private val silentHaptics = ByteArray(DualSenseBtAudioBuilder.HAPTICS_BYTES_PER_REPORT)
    private val audioDrainScheduled = AtomicBoolean(false)
    private val pendingState = AtomicReference<ByteArray?>(null)
    private val stateDrainScheduled = AtomicBoolean(false)
    private var bridge: DirectDualSenseBtHidBridge? = null
    private var appContext: Context? = null
    private var state = DirectDualSenseBtReportBuilder.PersistentOutputState(
        playerLedBrightness = 0,
        playerLedMask = 0x04,
        triggerSoftnessLevel = 0,
        softRumbleReduce = 0,
        lightbarRed = 0,
        lightbarGreen = 0,
        lightbarBlue = 0,
    )
    private var triggerReport: ByteArray? = null
    private var leftTrigger = ByteArray(11).also { it[0] = 0x05 }
    private var rightTrigger = ByteArray(11).also { it[0] = 0x05 }
    private var leftRumble = 0
    private var rightRumble = 0
    private var microphoneMuted = false
    @Volatile private var microphoneCaptureEnabled = false
    private var microphoneReportSubscription: Closeable? = null
    @Volatile private var initialized = false
    @Volatile private var headsetRoute = false
    @Volatile private var jackStateKnown = false
    @Volatile private var microphoneHardwareStateKnown = false
    @Volatile private var lastJackStatusSignature: String? = null
    @Volatile private var connectedCache = false
    @Volatile private var lastConnectionProbeMs = 0L
    @Volatile private var primed = false
    @Volatile private var audioRouteArmed = false
    @Volatile private var nextAudioDeadlineNs = 0L
    @Volatile private var lastNativeAudioAtNs = 0L
    @Volatile private var activeAudioSource = AudioSource.NONE
    @Volatile private var lastCombinedAudioAtNs = 0L
    @Volatile private var encodedSilence: ByteArray? = null
    @Volatile private var lastStateSendNs = 0L
    private var audioSequence = 0
    private val outputSequence = AtomicInteger(0)
    private val audioReceivedWindow = AtomicInteger(0)
    private val audioOverwrittenWindow = AtomicInteger(0)
    private var audioSentWindow = 0
    private var audioFailedWindow = 0
    private var audioSlowWindow = 0
    private var audioSendTimeWindowNs = 0L
    private var audioMaxSendTimeWindowNs = 0L
    private var audioMaxDrainGapWindowNs = 0L
    private var lastAudioDrainStartNs = 0L
    private var lastAudioDiagnosticsNs = 0L

    @JvmStatic fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || initialized) return
        synchronized(lock) {
            if (initialized) return
            // Defer profile binding until BLUETOOTH_CONNECT has been granted. This also
            // keeps JVM/Robolectric startup free of an Android Bluetooth service bind.
            appContext = context.applicationContext
            bridge = DirectDualSenseBtHidBridge(context.applicationContext)
            initialized = true
            jackPoller.scheduleWithFixedDelay({
                try {
                    pollHeadsetJack()
                } catch (throwable: Throwable) {
                    LimeLog.warning("Direct DualSense BT jack poll failed: ${throwable.message}")
                }
            },
                JACK_POLL_PERIOD_MS, JACK_POLL_PERIOD_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun permissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @JvmStatic fun isConnected(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || DualSenseBridge.controllerConnected) return false
        return isDirectHidConnected(context)
    }

    /** Physical Android HID connection, independent of bridge route priority. */
    @JvmStatic fun isDirectHidConnected(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val active = bridge ?: return false
        if (!permissionGranted(context)) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastConnectionProbeMs < 500L) return connectedCache
        // Permission may have been granted after Application.onCreate(). Refresh is
        // idempotent and also recovers a profile proxy replaced by the system stack.
        val currentStatus = active.getStatus(permissionGranted(context))
        if (!currentStatus.proxyReady) active.start()
        val wasConnected = connectedCache
        connectedCache = runCatching {
            val status = active.getStatus(permissionGranted(context))
            status.permissionGranted && status.proxyReady && status.connectedDeviceCount > 0 &&
                (status.sendDataReady || status.setReportReady)
        }.getOrDefault(false)
        lastConnectionProbeMs = now
        if (connectedCache && (!wasConnected || !primed)) primeController()
        if (!connectedCache) primed = false
        return connectedCache
    }

    @JvmStatic fun isBluetoothDualSenseInput(device: InputDevice?): Boolean {
        if (device == null || device.vendorId != 0x054c ||
            (device.productId != 0x0ce6 && device.productId != 0x0df2)) return false
        val description = runCatching { device.toString() }.getOrDefault("")
        return description.contains("bluetoothAddress=", ignoreCase = true) ||
            device.descriptor.contains("bluetooth", ignoreCase = true) ||
            device.name.contains("Wireless Controller", ignoreCase = true)
    }

    @JvmStatic fun sendRumble(context: Context, low: Short, high: Short): Boolean {
        synchronized(lock) {
            leftRumble = (low.toInt() ushr 8) and 0xff
            rightRumble = (high.toInt() ushr 8) and 0xff
        }
        LimeLog.info("Direct DualSense BT rumble: left=$leftRumble right=$rightRumble")
        return sendState(context)
    }

    @JvmStatic fun sendLed(context: Context, red: Byte, green: Byte, blue: Byte): Boolean {
        synchronized(lock) {
            state = state.copy(lightbarRed = red.toInt() and 0xff,
                lightbarGreen = green.toInt() and 0xff, lightbarBlue = blue.toInt() and 0xff)
        }
        LimeLog.info("Direct DualSense BT lightbar: ${red.toInt() and 0xff},${green.toInt() and 0xff},${blue.toInt() and 0xff}")
        return sendState(context)
    }

    @JvmStatic fun setPlayerLeds(context: Context, mask: Int): Boolean {
        // Brightness 0 disables the player indicators regardless of the mask.
        // The reference feedback manager always sets 0x01 together with a host
        // player update; our initial state previously left this at zero.
        synchronized(lock) {
            state = state.copy(playerLedBrightness = 0x01, playerLedMask = mask and 0x3f)
        }
        LimeLog.info("Direct DualSense BT player LEDs: 0x${(mask and 0x3f).toString(16)}")
        return sendState(context)
    }

    @JvmStatic fun setAdaptiveTriggerEffects(context: Context, eventFlags: Byte,
                                               leftType: Byte, rightType: Byte,
                                               left: ByteArray?, right: ByteArray?): Boolean {
        synchronized(lock) {
            if ((eventFlags.toInt() and 0x08) != 0) {
                leftTrigger = packTrigger(leftType, left)
            }
            if ((eventFlags.toInt() and 0x04) != 0) {
                rightTrigger = packTrigger(rightType, right)
            }
            triggerReport = DirectDualSenseBtReportBuilder.buildTriggerEffectsReport(
                state,
                leftTrigger[0].toInt() and 0xff, leftTrigger.copyOfRange(1, 11),
                rightTrigger[0].toInt() and 0xff, rightTrigger.copyOfRange(1, 11))
        }
        LimeLog.info("Direct DualSense BT adaptive triggers: flags=0x${(eventFlags.toInt() and 0xff).toString(16)} " +
            "left=0x${(leftType.toInt() and 0xff).toString(16)} right=0x${(rightType.toInt() and 0xff).toString(16)}")
        return sendState(context)
    }

    @JvmStatic fun setTriggerRumble(context: Context, left: Short, right: Short): Boolean {
        val leftStrength = ((left.toInt() and 0xffff) * 0x3f / 65535)
        val rightStrength = ((right.toInt() and 0xffff) * 0x3f / 65535)
        val leftData = ByteArray(10).also {
            if (leftStrength > 0) { it[0] = 0xff.toByte(); it[1] = 0x03; it[2] = leftStrength.toByte() }
        }
        val rightData = ByteArray(10).also {
            if (rightStrength > 0) { it[0] = 0xff.toByte(); it[1] = 0x03; it[2] = rightStrength.toByte() }
        }
        return setAdaptiveTriggerEffects(context, 0x0c,
            (if (leftStrength > 0) 0x27 else 0).toByte(),
            (if (rightStrength > 0) 0x27 else 0).toByte(),
            leftData, rightData)
    }

    @JvmStatic fun setHeadsetRoute(context: Context, enabled: Boolean): Boolean {
        val routeChanged = headsetRoute != enabled
        headsetRoute = enabled
        DirectDualSenseBtReportBuilder.headphoneMode = enabled
        // The continuous 0x35 media packets carry the selected stream tag, but
        // the controller's wireless audio engine must also be re-armed with a
        // fresh 0x36 setup when that tag changes. Otherwise it keeps the route
        // selected when audio was first started until the next reconnect.
        if (routeChanged && isNativeAudioActive()) {
            audioRouteArmed = false
            // The already scheduled media drain performs the setup and then
            // emits the merged controller state in strict writer order.
            return true
        }
        return sendState(context)
    }

    @JvmStatic fun isHeadsetRoute(): Boolean = headsetRoute

    private fun pollHeadsetJack() {
        val context = appContext ?: return
        // Do not gate GET_REPORT on the cached HID profile snapshot. Android can
        // briefly publish an empty connected-device list while a stream starts,
        // even though the same controller remains usable. Only a successfully
        // parsed live report is authoritative for jack hotplug state.
        val report = readInputReport() ?: return
        val statusStart = if ((report[0].toInt() and 0xff) == 0x31) 54 else 55
        val statusEnd = minOf(report.size, statusStart + 3)
        val statusSignature = report.copyOfRange(statusStart, statusEnd)
            .joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
        if (statusSignature != lastJackStatusSignature) {
            lastJackStatusSignature = statusSignature
            LimeLog.info("Direct DualSense BT input status: $statusSignature")
        }
        val audioStatusOffset = when {
            report.size > 56 && (report[0].toInt() and 0xff) == 0xa1 &&
                (report[1].toInt() and 0xff) == 0x31 -> 56
            report.size > 55 && (report[0].toInt() and 0xff) == 0x31 -> 55
            else -> return
        }
        val connected = (report[audioStatusOffset].toInt() and 0x01) != 0
        val hardwareMuted = (report[audioStatusOffset].toInt() and 0x04) != 0
        if (!jackStateKnown || connected != headsetRoute) {
            jackStateKnown = true
            LimeLog.info("Direct DualSense BT jack: " +
                if (connected) "headset connected" else "controller speaker")
            setHeadsetRoute(context, connected)
        }
        if (!microphoneHardwareStateKnown) {
            microphoneHardwareStateKnown = true
            DualSenseMicrophoneBridge.setMuted(hardwareMuted)
        } else if (hardwareMuted != DualSenseMicrophoneBridge.isMuted()) {
            val effectiveMuted = DualSenseMicrophoneBridge.setMuted(hardwareMuted)
            com.limelight.Game.notifyDualSenseMicrophoneMute(effectiveMuted)
        }
    }

    /** True while the direct Bluetooth audio cadence owns most of the HID bandwidth. */
    @JvmStatic fun isNativeAudioActive(): Boolean {
        val last = lastNativeAudioAtNs
        return audioRouteArmed && last != 0L &&
            SystemClock.elapsedRealtimeNanos() - last < NATIVE_AUDIO_ACTIVE_GRACE_NS
    }

    /** Read the current complete Bluetooth input snapshot (report 0x31). */
    @JvmStatic fun readInputReport(): ByteArray? {
        val active = bridge ?: return null
        return active.requestInputReport(reportId = 0x31, bufferSize = 77, timeoutMs = 500L)
            .takeIf { it.success }
            ?.report
    }

    @JvmStatic fun setMicrophoneMuted(context: Context, muted: Boolean): Boolean {
        synchronized(lock) { microphoneMuted = muted }
        return sendState(context)
    }

    @JvmStatic fun setMicrophoneMuted(muted: Boolean): Boolean =
        appContext?.let { setMicrophoneMuted(it, muted) } ?: false

    @JvmStatic @Synchronized fun setBluetoothMicrophoneCapture(enabled: Boolean): Boolean {
        val active = bridge ?: return false
        if (enabled == microphoneCaptureEnabled) return true
        microphoneCaptureEnabled = enabled
        if (enabled) {
            microphoneReportSubscription = active.addReportListener { _, reportId, report ->
                if (reportId != 0x31 || report.isEmpty()) return@addReportListener
                val base = if ((report[0].toInt() and 0xff) == 0xa1) 2 else 1
                if (report.size < base + 2 + 71 ||
                    (report[base].toInt() and 0x02) == 0) return@addReportListener
                val opusOffset = base + 2
                DualSenseMicrophoneBridge.onBluetoothOpusFrame(
                    report.copyOfRange(opusOffset, opusOffset + 71))
            }
        } else {
            microphoneReportSubscription?.close()
            microphoneReportSubscription = null
        }
        writer.execute {
            send(DualSenseBtAudioBuilder.buildDuplexSetup(
                nextOutputSequence(), enabled, headsetRoute), true)
            if (enabled) {
                val config = synchronized(lock) { currentOutputConfigLocked() }
                send(DualSenseBtAudioBuilder.buildMicrophoneArm(
                    nextOutputSequence(), audioSequence++ and 0xff, config, headsetRoute), true)
                LimeLog.info("Direct DualSense BT microphone capture armed")
            }
        }
        return true
    }

    @JvmStatic fun sendNativeAudio(context: Context, haptics: ByteArray,
                                   speakerOpus: ByteArray?, speakerOnly: Boolean = false): Boolean {
        // A detected direct headset is an explicit physical route and may be
        // used even if the bridge's global priority flag is stale/also active.
        if (!isConnected(context) && !headsetRoute) return false
        audioReceivedWindow.incrementAndGet()
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val source = if (speakerOnly) AudioSource.STANDALONE else AudioSource.COMBINED
        var startDrain = false
        synchronized(audioQueueLock) {
            if (source == AudioSource.COMBINED) {
                lastCombinedAudioAtNs = nowNs
                if (activeAudioSource != AudioSource.COMBINED) {
                    // The native combined carrier owns both speaker and haptics.
                    // Discard any standalone frames immediately so two producers
                    // can never drain the controller at roughly twice its clock.
                    clearAudioQueueLocked()
                    nextAudioDeadlineNs = 0L
                    activeAudioSource = AudioSource.COMBINED
                    LimeLog.info("Direct DualSense BT audio owner: COMBINED")
                }
            } else {
                // A short native-stream gap is not permission to start a second
                // producer. Only take ownership after the combined carrier has
                // demonstrably stopped for several report periods.
                if (nowNs - lastCombinedAudioAtNs < COMBINED_ROUTE_RELEASE_NS) return true
                if (activeAudioSource != AudioSource.STANDALONE) {
                    clearAudioQueueLocked()
                    nextAudioDeadlineNs = 0L
                    activeAudioSource = AudioSource.STANDALONE
                    LimeLog.info("Direct DualSense BT audio owner: STANDALONE")
                }
            }

            if (audioQueue.size >= AUDIO_QUEUE_CAPACITY) {
                recycleAudioPayloadLocked(audioQueue.removeFirst())
                audioOverwrittenWindow.incrementAndGet()
            }
            val payload = freeAudioPayloads.removeFirstOrNull() ?: AudioPayload()
            if (speakerOnly) payload.haptics.fill(0)
            else haptics.copyInto(payload.haptics)
            // A missing speaker packet must never repeat old media. Combined
            // transport uses a real encoded CELT-stereo silence frame instead.
            val speaker = speakerOpus ?: encodedSilence
            payload.hasSpeaker = speaker != null
            if (speaker != null) speaker.copyInto(payload.speaker)
            payload.source = source
            audioQueue.addLast(payload)
            startDrain = !audioDrainScheduled.get() && audioQueue.size >= AUDIO_START_FRAMES
        }
        lastNativeAudioAtNs = nowNs
        if (encodedSilence == null) {
            encodedSilence = DualSenseBtAudioNative.encodeSpeakerSilence()
            if (encodedSilence == null) {
                LimeLog.warning("Direct DualSense BT failed to create encoded silence frame")
            }
        }
        // Arm from actual queue depth, not elapsed wall time. Network jitter can
        // otherwise leave fewer than the intended four frames available.
        if (startDrain) scheduleAudioDrain(0L)
        return true
    }

    @JvmStatic fun stopNativeAudio(context: Context) {
        synchronized(audioQueueLock) { clearAudioQueueLocked() }
        activeAudioSource = AudioSource.NONE
        lastCombinedAudioAtNs = 0L
        audioRouteArmed = false
        nextAudioDeadlineNs = 0L
        lastNativeAudioAtNs = 0L
        lastAudioDrainStartNs = 0L
        // A scheduled drain may still run, but it will find no payload. Do not append
        // a final audio packet: Android's HID service has its own asynchronous queue,
        // and adding silence there only extends the tail after the stream has stopped.
        if (isConnected(context)) sendState(context)
    }

    /** Drop only stale media when the decoded direct-controller source is truly silent. */
    @JvmStatic fun discardNativeAudioBacklogOnSilence() {
        synchronized(audioQueueLock) { clearAudioQueueLocked() }
        activeAudioSource = AudioSource.NONE
        lastCombinedAudioAtNs = 0L
        lastNativeAudioAtNs = 0L
        nextAudioDeadlineNs = 0L
        // Force the proven setup/state wake sequence at this safe silent point.
        // This rebases controller-side and vendor HID queues on a clean boundary.
        audioRouteArmed = false
        LimeLog.info("Direct DualSense BT silent transport rebase")
        // A silence interval is not Bluetooth scheduling jitter. Start the next
        // audible segment with a fresh timing baseline.
        lastAudioDrainStartNs = 0L
    }

    private fun packTrigger(type: Byte, data: ByteArray?): ByteArray = ByteArray(11).also { packed ->
        packed[0] = type
        data?.copyInto(packed, 1, 0, minOf(10, data.size))
        if (type.toInt() == 0) packed[0] = 0x05
    }

    private fun sendState(context: Context): Boolean {
        if (!isConnected(context)) return false
        val report = synchronized(lock) {
            DualSenseBtOutputBuilder.build(currentOutputConfigLocked(), nextOutputSequence(),
                nativeBluetoothAudio = audioRouteArmed,
                headsetRoute = headsetRoute)
        }
        pendingState.set(report)
        scheduleStateDrain()
        return true
    }

    private fun scheduleStateDrain() {
        if (!stateDrainScheduled.compareAndSet(false, true)) return
        val now = SystemClock.elapsedRealtimeNanos()
        val minimumInterval = if (isNativeAudioActive()) {
            STATE_INTERVAL_DURING_AUDIO_NS
        } else {
            STATE_INTERVAL_IDLE_NS
        }
        val delay = (lastStateSendNs + minimumInterval - now).coerceAtLeast(0L)
        writer.schedule(::drainState, delay, TimeUnit.NANOSECONDS)
    }

    private fun drainState() {
        val report = pendingState.getAndSet(null)
        if (report != null) {
            send(report, false)
            lastStateSendNs = SystemClock.elapsedRealtimeNanos()
        }
        stateDrainScheduled.set(false)
        if (pendingState.get() != null) scheduleStateDrain()
    }

    private fun currentOutputConfigLocked() = DualSenseOutputConfig(
        red = state.lightbarRed,
        green = state.lightbarGreen,
        blue = state.lightbarBlue,
        playerLeds = state.playerLedMask and 0x1f,
        micLed = microphoneMuted,
        leftRumble = leftRumble,
        rightRumble = rightRumble,
        leftTriggerEffect = leftTrigger.copyOf(),
        rightTriggerEffect = rightTrigger.copyOf(),
    )

    private fun nextOutputSequence(): Int = outputSequence.getAndIncrement()

    private fun primeController() {
        if (primed) return
        primed = true
        writer.execute {
            LimeLog.info("Direct DualSense BT output prime sequence starting")
            val report = synchronized(lock) {
                DualSenseBtOutputBuilder.build(currentOutputConfigLocked(), nextOutputSequence(),
                    nativeBluetoothAudio = false, headsetRoute = headsetRoute)
            }
            send(report, false)
            LimeLog.info("Direct DualSense BT output prime sequence complete")
        }
    }

    private fun scheduleAudioDrain(delayNs: Long) {
        if (!audioDrainScheduled.compareAndSet(false, true)) return
        writer.schedule(::drainAudio, delayNs.coerceAtLeast(0L), TimeUnit.NANOSECONDS)
    }

    private fun drainAudio() {
        val drainStartNs = SystemClock.elapsedRealtimeNanos()
        if (lastAudioDrainStartNs != 0L) {
            audioMaxDrainGapWindowNs = maxOf(audioMaxDrainGapWindowNs,
                drainStartNs - lastAudioDrainStartNs)
        }
        lastAudioDrainStartNs = drainStartNs
        val freshPayload = synchronized(audioQueueLock) {
            if (audioQueue.isNotEmpty()) audioQueue.removeFirst() else null
        }
        val audioStillActive = lastNativeAudioAtNs != 0L &&
            drainStartNs - lastNativeAudioAtNs < NATIVE_AUDIO_ACTIVE_GRACE_NS
        // Never repeat old media during an underrun. Keep the controller decoder
        // on its existing CELT-stereo stream with one genuinely encoded silence
        // packet and silent haptics until the producer recovers.
        val underrunSilence = if (freshPayload == null && audioStillActive) encodedSilence else null
        if (freshPayload != null || underrunSilence != null) {
            if (!armAudioRoute()) {
                if (freshPayload != null) synchronized(audioQueueLock) {
                    recycleAudioPayloadLocked(freshPayload)
                }
                audioDrainScheduled.set(false)
                return
            }
            // Keep the standard one-frame report on every Android Bluetooth stack.
            // Some vendor implementations accept the larger two-frame write but do
            // not deliver it to the controller, so sendData() cannot detect failure.
            val speaker = if (freshPayload?.hasSpeaker == true) freshPayload.speaker else underrunSilence
            val report = DualSenseBtAudioBuilder.write(
                report = if (speaker != null) audioReport else hapticsReport,
                haptics = freshPayload?.haptics ?: silentHaptics,
                sequence = nextOutputSequence(),
                packetCounter = audioSequence++ and 0xff,
                speakerOpus = speaker,
                headsetRoute = headsetRoute,
                microphoneEnabled = microphoneCaptureEnabled,
                latencyScale = AUDIO_LATENCY_SCALE,
                crc = audioReportCrc,
            )
            val sendStartNs = SystemClock.elapsedRealtimeNanos()
            val sent = send(report, true)
            val sendTimeNs = SystemClock.elapsedRealtimeNanos() - sendStartNs
            audioSentWindow++
            if (!sent) audioFailedWindow++
            if (sendTimeNs >= 5_000_000L) audioSlowWindow++
            audioSendTimeWindowNs += sendTimeNs
            audioMaxSendTimeWindowNs = maxOf(audioMaxSendTimeWindowNs, sendTimeNs)
            logAudioTransportDiagnostics(SystemClock.elapsedRealtimeNanos())
            if (freshPayload != null) synchronized(audioQueueLock) {
                recycleAudioPayloadLocked(freshPayload)
            }
        }
        val queueNotEmpty = synchronized(audioQueueLock) { audioQueue.isNotEmpty() }
        if (audioStillActive || queueNotEmpty) {
            val sendFinishedNs = SystemClock.elapsedRealtimeNanos()
            val currentDeadlineNs = if (nextAudioDeadlineNs == 0L) {
                drainStartNs
            } else nextAudioDeadlineNs
            var followingDeadlineNs = currentDeadlineNs + AUDIO_REPORT_PERIOD_NS
            var skippedSlots = 0

            // Stay on the original report grid. Only enter resync when the next
            // grid slot has actually elapsed by the time sendData() completes.
            // Ordinary sub-period scheduler lateness is not a missed slot. Once
            // a slot really is missed, advance by whole periods until at least
            // one complete period is ahead and discard the matching old frames.
            if (followingDeadlineNs <= sendFinishedNs) {
                val safeDeadlineNs = sendFinishedNs + AUDIO_REPORT_PERIOD_NS
                while (followingDeadlineNs < safeDeadlineNs) {
                    followingDeadlineNs += AUDIO_REPORT_PERIOD_NS
                    skippedSlots++
                }
            }
            if (skippedSlots > 0) dropSkippedAudioSlots(skippedSlots)
            nextAudioDeadlineNs = followingDeadlineNs
            writer.schedule(::drainAudio,
                (followingDeadlineNs - sendFinishedNs).coerceAtLeast(0L), TimeUnit.NANOSECONDS)
        } else {
            audioDrainScheduled.set(false)
            nextAudioDeadlineNs = 0L
            activeAudioSource = AudioSource.NONE
            // Do not measure the idle source interval as writer scheduler jitter
            // when a later audio segment starts.
            lastAudioDrainStartNs = 0L
            if (synchronized(audioQueueLock) { audioQueue.isNotEmpty() }) {
                scheduleAudioDrain(0L)
            }
        }
    }

    private fun dropSkippedAudioSlots(count: Int) {
        var dropped = 0
        synchronized(audioQueueLock) {
            repeat(count) {
                if (audioQueue.isEmpty()) return@repeat
                recycleAudioPayloadLocked(audioQueue.removeFirst())
                dropped++
            }
        }
        if (dropped > 0) {
            audioOverwrittenWindow.addAndGet(dropped)
            LimeLog.info("Direct DualSense BT deadline resync: skipped=$count dropped=$dropped")
        }
    }

    private fun clearAudioQueueLocked() {
        while (audioQueue.isNotEmpty()) recycleAudioPayloadLocked(audioQueue.removeFirst())
    }

    private fun recycleAudioPayloadLocked(payload: AudioPayload) {
        payload.hasSpeaker = false
        payload.source = AudioSource.NONE
        if (freeAudioPayloads.size < AUDIO_PAYLOAD_POOL_SIZE) freeAudioPayloads.addLast(payload)
    }

    private fun logAudioTransportDiagnostics(nowNs: Long) {
        if (lastAudioDiagnosticsNs == 0L) lastAudioDiagnosticsNs = nowNs
        if (nowNs - lastAudioDiagnosticsNs < 2_000_000_000L) return
        val sent = audioSentWindow
        val received = audioReceivedWindow.getAndSet(0)
        val averageUs = if (sent == 0) 0L else audioSendTimeWindowNs / sent / 1_000L
        LimeLog.info("Direct DualSense BT transport: received=$received " +
            "overwritten=${audioOverwrittenWindow.getAndSet(0)} sent=$sent failed=$audioFailedWindow " +
            "profile=ABSOLUTE_GRID latency=0x${AUDIO_LATENCY_SCALE.toString(16)} " +
            "slow5ms=$audioSlowWindow avgSendUs=$averageUs " +
            "maxSendUs=${audioMaxSendTimeWindowNs / 1_000L} " +
            "maxDrainGapUs=${audioMaxDrainGapWindowNs / 1_000L}")
        audioSentWindow = 0
        audioFailedWindow = 0
        audioSlowWindow = 0
        audioSendTimeWindowNs = 0L
        audioMaxSendTimeWindowNs = 0L
        audioMaxDrainGapWindowNs = 0L
        lastAudioDiagnosticsNs = nowNs
    }

    private fun armAudioRoute(): Boolean {
        if (audioRouteArmed) return true
        val active = bridge ?: return false
        // Match the bridge's narrow audio-only wake. It deliberately carries
        // no visual/trigger snapshot, so it cannot overwrite host-owned state.
        if (!send(DualSenseBtAudioBuilder.buildDuplexSetup(
                nextOutputSequence(), false, headsetRoute), true)) return false
        audioRouteArmed = true
        nextAudioDeadlineNs = SystemClock.elapsedRealtimeNanos()
        val stateReport = synchronized(lock) {
            DualSenseBtOutputBuilder.build(currentOutputConfigLocked(), nextOutputSequence(),
                nativeBluetoothAudio = true, headsetRoute = headsetRoute)
        }
        send(stateReport, true)
        LimeLog.info("Direct DualSense BT combined audio route armed")
        return active.getSelectedDevice() != null
    }

    private fun send(report: ByteArray, streaming: Boolean): Boolean {
        val active = bridge ?: return false
        val fast = active.sendOutputReportFast(report, DirectDualSenseBtHidBridge.OutputTransportMode.AUTO)
        if (fast) return true
        val result = active.sendOutputReport(report, streaming = streaming,
            transportMode = DirectDualSenseBtHidBridge.OutputTransportMode.AUTO)
        if (!result.success && !streaming) LimeLog.warning("Direct DualSense BT output failed: ${result.message}")
        return result.success
    }

    private const val AUDIO_REPORT_PERIOD_NS = 10_666_667L
    private const val AUDIO_LATENCY_SCALE = 0x80
    private const val AUDIO_START_FRAMES = 4
    private const val AUDIO_QUEUE_CAPACITY = 8
    private const val AUDIO_PAYLOAD_POOL_SIZE = AUDIO_QUEUE_CAPACITY + 2
    private const val COMBINED_ROUTE_RELEASE_NS = AUDIO_REPORT_PERIOD_NS * 8L
    private const val STATE_INTERVAL_IDLE_NS = 16_000_000L
    // Samsung's BluetoothHidHost accepts writes before they reach the radio. Keep
    // repeated host feedback from filling that hidden queue ahead of live audio.
    private const val STATE_INTERVAL_DURING_AUDIO_NS = 96_000_000L
    private const val NATIVE_AUDIO_ACTIVE_GRACE_NS = 250_000_000L
    private const val JACK_POLL_PERIOD_MS = 1_000L
}
