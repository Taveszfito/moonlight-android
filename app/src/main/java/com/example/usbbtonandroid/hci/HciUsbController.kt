package com.example.usbbtonandroid.hci

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.SystemClock
import android.os.Process
import com.limelight.R
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Profile 1: generic, proven USB Bluetooth HCI transport.
 *
 * IMPORTANT: do not place chipset-specific workarounds in this class. It is
 * the stable path for the Baseus and antenna adapters. New chipset support
 * belongs in a dedicated subclass/profile (such as CsrHciUsbController), and
 * changes here are limited to generic correctness or a Profile 1 regression.
 */
open class HciUsbController(
    protected val usbManager: UsbManager,
    protected val device: UsbDevice,
    private val stringProvider: (Int, Array<out Any>) -> String,
    protected val onLog: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onDevice: (HciDevice) -> Unit,
    private val onAclPacket: (AclPacket) -> Unit,
    private val onDualSenseMicrophoneFrame: (ByteArray, Int) -> Unit,
    private val loadLinkKey: (String) -> String?,
    private val saveLinkKey: (String, String) -> Unit
) : Closeable {
    private fun text(id: Int, vararg args: Any): String = stringProvider(id, args)
    private val running = AtomicBoolean(false)
    protected var connection: UsbDeviceConnection? = null
    protected var hciInterface: UsbInterface? = null
    protected var eventIn: UsbEndpoint? = null
    private var aclIn: UsbEndpoint? = null
    private var aclOut: UsbEndpoint? = null
    private var worker: Thread? = null
    private var aclWorker: Thread? = null
    private var inputWorker: Thread? = null
    private var outputWorker: Thread? = null
    private var audioWorker: Thread? = null
    private val inputQueue = ArrayBlockingQueue<AclPacket>(INPUT_QUEUE_CAPACITY)
    private val outputSignal = ArrayBlockingQueue<Unit>(1)
    private val audioQueue = ArrayBlockingQueue<NativeAudioPayload>(AUDIO_QUEUE_CAPACITY)
    @Volatile private var latestOutputConfig: com.example.usbbtonandroid.DualSenseOutputConfig? = null
    @Volatile private var outputConnectionEpoch = 0L
    private val discoveredDevices = linkedMapOf<String, InquiryDevice>()
    @Volatile private var pendingConnection: InquiryDevice? = null
    @Volatile private var pendingIncomingConnection: InquiryDevice? = null
    @Volatile private var authorizedPairingAddress: String? = null
    @Volatile private var pendingDiscovery = false
    @Volatile private var pendingLinkRecovery = false
    // All HCI commands are deliberately issued by the one event worker.  This
    // generation lets a newer UI action interrupt the long event waits of an
    // older operation without introducing a second, racing command writer.
    private val controlGeneration = AtomicLong(0)
    @Volatile private var activeDevice: InquiryDevice? = null
    @Volatile private var activeHandle: Int? = null
    @Volatile private var encryptedAtMs = 0L
    @Volatile private var lastHidInputMs = 0L
    @Volatile private var hidOpenAttempted = false
    @Volatile private var hidOpenAttemptAtMs = 0L
    @Volatile private var hidChannelsReady = false
    @Volatile private var hidControlReady = false
    @Volatile private var hidReadyAtMs = 0L
    @Volatile private var hidChannelReopenAtMs = 0L
    @Volatile private var hidInterruptReopenAtMs = 0L
    private var hidOpenRetries = 0
    private var hidInterruptOpenRetries = 0
    private var hidRecoveryCycles = 0
    private var nextSignalId = 0x40
    private var nextLocalCid = 0x0040
    @Volatile private var hidInterruptRemoteCid: Int? = null
    private var outputSequence = 0
    private var audioPacketCounter = 0
    private var nativeAudioReportsSent = 0L
    private var nativeAudioReportsSkippedForInput = 0L
    @Volatile private var nativeBluetoothHapticsRequested = false
    @Volatile private var nativeBluetoothMicrophoneRequested = false
    @Volatile private var nativeBluetoothHeadsetRoute = false
    @Volatile private var lastBluetoothMicrophoneFrameMs = 0L
    private var lastBluetoothMicrophoneArmMs = 0L
    private var nativeAudioWakeEpoch = -1L
    @Volatile private var lastOutputErrorLogMs = 0L
    private var droppedInputPackets = 0L
    @Volatile private var lastActiveDevicePublishMs = 0L
    @Volatile private var lastAclLogMs = 0L
    private var btMicFramesSinceLog = 0
    private var btMicSequenceGapsSinceLog = 0
    private val btMicSequenceDeltaCounts = IntArray(16)
    private var btMicLastSequence = -1
    private var btMicLastLogMs = 0L
    private var btMicCandidateFramesSinceLog = 0
    private var btMicCandidateLastLogMs = 0L
    private val btMicCandidateTocCounts = IntArray(256)
    // A nonzero packet rate alone is not sufficient: a bad initial duplex
    // session can deliver only three quarters of the 10 ms Opus cadence, which
    // keeps the old stale-frame watchdog permanently satisfied.
    private val btMicFramesInWatchWindow = AtomicLong(0)
    @Volatile private var btMicWatchWindowStartedMs = 0L
    @Volatile private var lastUsbAclAtMs = 0L
    @Volatile private var lastHidInputElapsedMs = 0L
    @Volatile private var linkQualityPercent = 100
    @Volatile private var lastHidGapMs = 0L
    @Volatile private var lastInputDispatchMs = 0L
    @Volatile private var lastInputDispatchDurationMs = 0L
    @Volatile private var lastOutputStallMs = 0L
    @Volatile private var lastIncidentAtMs = 0L
    @Volatile private var lastIncidentType = INCIDENT_NONE
    @Volatile private var lastIncidentDurationMs = 0L
    @Volatile private var postConnectTuningStage = 0
    @Volatile private var postConnectTuningAtMs = 0L
    @Volatile private var lastExitLowPowerModeAtMs = 0L
    // HCI Mode Change uses 0 for active and 2 for sniff mode. Keep the last
    // adapter-confirmed value rather than assuming that a successfully written
    // command actually changed the radio schedule.
    @Volatile private var currentLinkMode = LINK_MODE_UNKNOWN
    @Volatile private var microphoneActiveModeRequested = false
    // Read from the adapter during startup. Native DualSense audio reports are
    // larger than the ACL buffer exposed by a number of inexpensive dongles.
    @Volatile private var aclDataPacketLength = DEFAULT_ACL_DATA_PACKET_LENGTH
    @Volatile private var aclPacketCapacity = 0
    private val aclPacketsSent = AtomicLong(0)
    private val aclPacketsCompleted = AtomicLong(0)
    // Controller-to-host flow control is enabled during adapter startup. This
    // counter returns HCI ACL buffer credits to the dongle after a packet has
    // been fully consumed by this reader.
    @Volatile private var controllerToHostFlowControlEnabled = false
    private var hostCompletedAclHandle = -1
    private var hostCompletedAclPackets = 0
    // Signaling is received on the ACL reader while fallback/recovery runs on
    // the HCI event worker. These maps must never be ordinary mutable maps.
    private val pendingChannels = ConcurrentHashMap<Int, L2capChannel>()
    private val pendingConfigs = ConcurrentHashMap<Int, L2capChannel>()
    private val channelsByLocalCid = ConcurrentHashMap<Int, L2capChannel>()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread(::runProbe, "usb-hci-probe").also { it.start() }
    }

    private fun runProbe() {
        try {
            open()
            onStatus(text(R.string.dualsense_bridge_status_hci_reset))
            requireCommandComplete(0x0C03)
            onLog("HCI Reset → Success")

            val version = requireCommandComplete(0x1001)
            if (version.size >= 9) {
                onLog("Local Version → HCI ${version[1].u8()}.${version[2].u8()}, manufacturer ${version.le16(6)}")
            }

            val address = requireCommandComplete(0x1009)
            if (address.size >= 7) onLog("Local Address → ${formatAddress(address, 1)}")

            val bufferSize = requireCommandComplete(0x1005)
            if (bufferSize.size >= 6) {
                val reportedLength = bufferSize.le16(1)
                if (reportedLength > 0) aclDataPacketLength = reportedLength
                aclPacketCapacity = bufferSize.le16(4)
                aclPacketsSent.set(0)
                aclPacketsCompleted.set(0)
                onLog(
                    "HCI ACL buffer → ${aclDataPacketLength} byte, " +
                        "${aclPacketCapacity} packet(s)"
                )
            }

            // The USB dongle used by the bridge has a small receive-side ACL
            // pool. With controller-to-host flow control left at its implicit
            // default it can discard a periodic incoming packet before this
            // raw HCI host has a chance to read it. Advertise a bounded host
            // queue and explicitly return credits after parsing each batch.
            // This must happen before the first ACL connection exists.
            configureControllerToHostAclFlowControl()

            // Always use the proven standalone startup path. Skipping inquiry on
            // recovery leaves cheap HCI dongles with stale page/clock state and
            // produces an ACL link that often never becomes a usable HID session.
            // Page Scan must be active *before* inquiry/name resolution. A
            // controller-initiated reconnect is otherwise invisible until the
            // potentially long discovery pass completes.
            enableIncomingConnections()
            if (runStartupInquiry()) {
                onStatus(text(R.string.dualsense_bridge_status_classic_scan))
                sendCommand(0x0401, byteArrayOf(0x33, 0x8B.toByte(), 0x9E.toByte(), 0x08, 0x00))
                val generation = controlGeneration.get()
                val discovered = resolveRemoteNames(scanUntilComplete(generation), generation)
                discoveredDevices.clear()
                discovered.forEach { discoveredDevices[it.address] = it }
                onStatus(text(R.string.dualsense_bridge_status_scan_finished))
            } else {
                onLog("Adapter ready in passive Page Scan; Profile 2 starts Inquiry only on Search")
                onStatus(text(R.string.dualsense_bridge_status_scan_finished))
            }
            hostLoop()
        } catch (t: Throwable) {
            if (running.get()) {
                onLog("HIBA: ${t.message ?: t.javaClass.simpleName}")
                onStatus(text(R.string.dualsense_bridge_status_probe_failed))
            }
        }
    }

    fun connect(address: String, name: String = text(R.string.dualsense_bridge_paired_device)) {
        val normalizedAddress = address.uppercase()
        val current = activeDevice
        val handle = activeHandle
        if (handle != null && current?.address?.uppercase() == normalizedAddress) {
            if (hidChannelsReady) {
                onStatus(text(R.string.dualsense_bridge_status_already_connected))
            } else if (!hasHidChannelProgress()) {
                onStatus(text(R.string.dualsense_bridge_status_restoring_hid))
                requestHidChannels(handle)
            } else {
                // A saved controller can reconnect at ACL level while leaving an
                // old L2CAP negotiation behind. Treat an explicit Connect tap as
                // a request to repair that half-open HID session immediately.
                onStatus(text(R.string.dualsense_bridge_status_restoring_hid))
                rebuildHidChannels(handle)
            }
            return
        }
        if (loadLinkKey(normalizedAddress) == null) authorizedPairingAddress = normalizedAddress
        // A direct controller choice must never wait behind an inquiry.
        pendingDiscovery = false
        controlGeneration.incrementAndGet()
        pendingConnection = discoveredDevices[normalizedAddress] ?: InquiryDevice(
            address = normalizedAddress,
            addressLittleEndian = addressToLittleEndian(normalizedAddress),
            pageScanRepetitionMode = 0x01,
            clockOffsetLow = 0,
            clockOffsetHigh = 0,
            deviceClass = "2508ED",
            rssi = null,
            name = name
        )
        if (handle != null && current != null && current.address != normalizedAddress) {
            onLog("Switching controller → disconnecting ${current.address} before connecting $normalizedAddress")
            // connect() is called from the UI thread. Keep the actual HCI write
            // on the event worker by queuing its normal disconnect intent.
            pendingDisconnectAddress = current.address.uppercase()
        }
        onStatus(text(R.string.dualsense_bridge_status_preparing_connection))
    }

    @Volatile private var pendingDisconnectAddress: String? = null

    fun disconnect(address: String) {
        // Disconnect is a user override too: stop a pending scan/connect first.
        pendingDiscovery = false
        pendingConnection = null
        controlGeneration.incrementAndGet()
        pendingDisconnectAddress = address.uppercase()
    }

    fun forget(address: String) {
        val normalized = address.uppercase()
        discoveredDevices.remove(normalized)
        if (authorizedPairingAddress == normalized) authorizedPairingAddress = null
        if (pendingConnection?.address?.uppercase() == normalized) pendingConnection = null
        disconnect(normalized)
    }

    fun requestLinkRecovery() {
        // Recovery is only allowed after this session has delivered real input.
        // During first connection it must not tear down a valid-but-slow HID pair.
        if (lastHidInputMs != 0L) pendingLinkRecovery = true
    }

    open fun requestDiscovery() {
        // Restart rather than queue discovery behind an older operation.
        controlGeneration.incrementAndGet()
        pendingDiscovery = true
    }

    fun sendOutput(config: com.example.usbbtonandroid.DualSenseOutputConfig): Boolean {
        // Never send a DualSense output report during link/HID negotiation.
        // These reports also contain lightbar and player LED control fields and
        // can disturb controller-side startup on some Bluetooth firmware. The
        // first valid input report is our proof that the complete HID path is live.
        if (activeHandle == null || hidInterruptRemoteCid == null || lastHidInputMs == 0L) {
            return false
        }
        // Rumble and LED callbacks can arrive in bursts. Never perform a blocking
        // USB write on their callback thread; only the newest complete state matters.
        latestOutputConfig = config
        outputSignal.offer(Unit)
        return true
    }

    /** Queues one native Bluetooth audio/haptics report worth of 3 kHz stereo PCM. */
    fun sendNativeBluetoothHaptics(haptics: ByteArray, speakerOpus: ByteArray?,
                                   speakerOnly: Boolean = false): Boolean {
        if (haptics.size != com.example.usbbtonandroid.DualSenseBtAudioBuilder.HAPTICS_BYTES_PER_REPORT ||
            (speakerOpus != null && speakerOpus.size != 200) ||
            activeHandle == null || hidInterruptRemoteCid == null || lastHidInputMs == 0L) {
            return false
        }
        nativeBluetoothHapticsRequested = true
        val payload = NativeAudioPayload(haptics.copyOf(), speakerOpus?.copyOf(),
            speakerOnly = speakerOnly)
        if (audioQueue.offer(payload)) return true
        // Controller speaker/headset audio is continuous and can always use the
        // next interval. Never evict a native host haptics block for it.
        if (speakerOnly) return false
        // Real-time audio must remain current. Drop the oldest queued waveform,
        // never block the Moonlight receive thread and never collapse it to rumble.
        audioQueue.poll()
        return audioQueue.offer(payload)
    }

    fun stopNativeBluetoothHaptics() {
        nativeBluetoothHapticsRequested = false
        nativeAudioWakeEpoch = -1L
        audioQueue.clear()
        outputSignal.offer(Unit)
    }

    fun setNativeBluetoothMicrophoneCapture(enabled: Boolean): Boolean {
        nativeBluetoothMicrophoneRequested = enabled
        microphoneActiveModeRequested = enabled
        // The controller does not document a reliable BT mic-stop command.
        // Once armed, stop forwarding on the client side; do not inject a
        // speculative status packet that can reset its live audio path.
        if (!enabled) return activeHandle != null && hidInterruptRemoteCid != null
        lastBluetoothMicrophoneFrameMs = 0L
        lastBluetoothMicrophoneArmMs = 0L
        btMicFramesInWatchWindow.set(0)
        btMicWatchWindowStartedMs = SystemClock.elapsedRealtime()
        val queued = queueNativeBluetoothSetup()
        outputSignal.offer(Unit)
        return queued
    }

    fun setNativeBluetoothHeadsetRoute(enabled: Boolean): Boolean {
        nativeBluetoothHeadsetRoute = enabled
        // The physical audio route is encoded in the normal 0x31 output
        // report. Re-send the current complete controller state immediately;
        // this preserves LEDs, rumble, and trigger effects while changing only
        // the route fields.
        outputSignal.offer(Unit)
        // Re-assert the same audio/mic state for both jack transitions. This
        // restores the full speaker route after unplugging and wakes a headset
        // microphone after plugging in, without using a broad LED snapshot.
        return if (nativeBluetoothMicrophoneRequested) {
            queueNativeBluetoothSetup()
        } else {
            activeHandle != null && hidInterruptRemoteCid != null && lastHidInputMs != 0L
        }
    }

    private fun queueNativeBluetoothSetup(): Boolean {
        if (activeHandle == null || hidInterruptRemoteCid == null || lastHidInputMs == 0L) {
            return false
        }
        val setup = NativeAudioPayload(ByteArray(64), null, configurationOnly = true,
            microphoneArm = true)
        if (audioQueue.offer(setup)) return true
        audioQueue.poll()
        return audioQueue.offer(setup)
    }

    /**
     * Enables controller-initiated reconnects.  Some isolated adapter
     * profiles deliberately degrade this to an optional operation when their
     * firmware does not implement Write Scan Enable.
     */
    protected open fun enableIncomingConnections() {
        requireCommandComplete(0x0C1A, byteArrayOf(0x02))
        onLog("Page Scan enabled → párosított eszközök visszakapcsolódhatnak")
    }

    private fun hostLoop() {
        var lastLinkCheck = 0L
        while (running.get()) {
            if (pendingLinkRecovery) {
                pendingLinkRecovery = false
                val handle = activeHandle
                if (handle != null) {
                    rebuildHidChannels(handle)
                }
            }
            pendingDisconnectAddress?.let { address ->
                pendingDisconnectAddress = null
                if (activeDevice?.address?.uppercase() == address) {
                    activeHandle?.let { handle ->
                        sendCommand(0x0406, byteArrayOf(
                            handle.toByte(), (handle ushr 8).toByte(), 0x13
                        ))
                    }
                }
            }
            pendingIncomingConnection?.let { target ->
                pendingIncomingConnection = null
                // An incoming page can win while the user has just tapped the
                // same controller. The physical ACL link is then already being
                // established; retaining the manual request would start a
                // second Create Connection after pairing and block the event
                // worker until its 45-second timeout.
                if (pendingConnection?.address?.uppercase() == target.address.uppercase()) {
                    pendingConnection = null
                    onLog("Incoming controller link superseded matching Connect request")
                }
                runCatching { connectAndPair(target, incomingAddress = target.addressLittleEndian) }.onFailure {
                    onLog("Incoming connection failed: ${it.message ?: it.javaClass.simpleName}")
                }
            }
            pendingConnection?.let { target ->
                // A connection being torn down must finish its Disconnect Complete
                // event before the adapter is asked to page another controller.
                if (activeHandle != null && activeDevice?.address?.uppercase() != target.address.uppercase()) {
                    return@let
                }
                if (activeHandle != null && activeDevice?.address?.uppercase() == target.address.uppercase()) {
                    // The HCI link was completed by the controller while this
                    // UI request was queued. Do not page an already-connected
                    // controller again; the normal post-encryption path will
                    // open HID on the next event-worker pass.
                    pendingConnection = null
                    onLog("Connect request consumed: ${target.address} already has an active HCI link")
                    return@let
                }
                pendingConnection = null
                val generation = controlGeneration.get()
                runCatching { connectAndPair(target, operationGeneration = generation) }.onFailure {
                    if (it is OperationSupersededException) return@onFailure
                    onLog("HIBA: ${it.message ?: it.javaClass.simpleName}")
                    onStatus(text(R.string.dualsense_bridge_status_connection_failed))
                    publishDevice(target, "Sikertelen")
                }
            }
            if (pendingDiscovery) {
                pendingDiscovery = false
                if (activeHandle == null) {
                    val generation = controlGeneration.get()
                    runCatching { performLiveDiscovery(generation) }.onFailure {
                        onLog("Live inquiry failed: ${it.message ?: it.javaClass.simpleName}")
                        onStatus(text(R.string.dualsense_bridge_status_probe_failed))
                        runCatching { enableIncomingConnections() }
                    }
                } else {
                    onStatus(text(R.string.dualsense_bridge_status_already_connected))
                }
            }
            val now = System.currentTimeMillis()
            val active = activeHandle
            if (active != null && postConnectTuningStage != 0 &&
                now >= postConnectTuningAtMs
            ) {
                applyNextPostConnectTuning(active, now)
            }
            // Do not force a mode transition while the DualSense Bluetooth
            // microphone is active.  The controller's own scheduling is part
            // of its 100 Hz Opus uplink: forcing Exit Sniff was measured to
            // collapse a healthy ~100 frame/s capture to ~40 frame/s once the
            // transition completed.  Normal gamepad recovery still uses the
            // mode-change handler below when microphone capture is inactive.
            if (active != null && hidChannelReopenAtMs != 0L && now >= hidChannelReopenAtMs) {
                hidChannelReopenAtMs = 0L
                hidOpenAttempted = false
                onLog("Smart recovery → reopening HID control and interrupt channels")
                requestHidChannels(active)
            }
            if (active != null && encryptedAtMs > 0 && !hidOpenAttempted &&
                now - encryptedAtMs >= HID_OPEN_DELAY_MS
            ) {
                onStatus(text(R.string.dualsense_bridge_status_opening_hid))
                requestHidChannels(active)
            }
            if (active != null && hidOpenAttempted && !hidChannelsReady &&
                lastHidInputMs == 0L && !hasHidChannelProgress() &&
                now - hidOpenAttemptAtMs >= HID_RETRY_INTERVAL_MS &&
                hidOpenRetries < HID_MAX_RETRIES
            ) {
                hidOpenRetries++
                onStatus(text(R.string.dualsense_bridge_status_retrying_hid,
                    hidOpenRetries, HID_MAX_RETRIES))
                pendingChannels.clear()
                pendingConfigs.clear()
                requestHidChannels(active)
            }
            if (active != null && hidInterruptReopenAtMs != 0L &&
                now >= hidInterruptReopenAtMs
            ) {
                hidInterruptReopenAtMs = 0L
                onLog("HID Interrupt retry → opening clean channel")
                requestL2capChannel(active, 0x0013)
            }
            if (active != null && hidControlReady && !hidChannelsReady &&
                lastHidInputMs == 0L &&
                now - hidOpenAttemptAtMs >= hidInterruptStageTimeoutMs() &&
                hidInterruptOpenRetries < HID_MAX_RETRIES
            ) {
                hidInterruptOpenRetries++
                onLog(
                    "HID Interrupt stage timeout → retry " +
                        "$hidInterruptOpenRetries/$HID_MAX_RETRIES"
                )
                retryInterruptChannel(active)
            }
            activeHandle?.let { handle ->
                // Never issue diagnostic HCI commands while the real-time HID stream is
                // active. Some Android USB stacks serialize control and bulk transfers,
                // which produces periodic multi-second input stalls.
                if (lastHidInputMs == 0L && allowIdleLinkCheck() &&
                    now - lastLinkCheck >= LINK_CHECK_INTERVAL_MS
                ) {
                    sendCommand(0x1405, byteArrayOf(handle.toByte(), (handle ushr 8).toByte()))
                    lastLinkCheck = now
                }
            }
            if (active != null && hidOpenAttempted && !hidChannelsReady &&
                lastHidInputMs == 0L &&
                now - encryptedAtMs >= hidStartTimeoutMs()
            ) {
                onStatus(text(R.string.dualsense_bridge_status_no_hid_stream))
                if (hidRecoveryCycles < HID_INITIAL_RECOVERY_CYCLES &&
                    now - hidOpenAttemptAtMs >= HID_RETRY_INTERVAL_MS
                ) {
                    hidRecoveryCycles++
                    onLog("Initial HID recovery cycle $hidRecoveryCycles/$HID_INITIAL_RECOVERY_CYCLES")
                    rebuildHidChannels(active)
                }
            }
            val event = readEvent(500) ?: continue
            when (event.code) {
                // Always queue an incoming page. The host loop services this
                // intent before discovery, recovery and manual connection work,
                // so a controller-initiated reconnect cannot race a live Inquiry.
                0x04 -> queueIncomingConnection(event.parameters)
                0x05 -> handleDisconnection(event.parameters)
                0x14 -> handleModeChange(event.parameters)
                0x0E -> handleCommandComplete(event.parameters)
                0x0F -> handleCommandStatus(event.parameters)
            }
        }
    }

    @Volatile private var liveDiscoveryInProgress = false

    private fun performLiveDiscovery(generation: Long) {
        liveDiscoveryInProgress = true
        try {
            onStatus(text(R.string.dualsense_bridge_status_classic_scan))
            onLog("Live HCI Inquiry → clearing stale discovery cache")
            discoveredDevices.clear()
            // Keep paired controllers pageable during both inquiry and the remote
            // name requests that follow it.
            enableIncomingConnections()
            sendCommand(0x0401, byteArrayOf(
                0x33, 0x8B.toByte(), 0x9E.toByte(), 0x08, 0x00
            ))
            val discovered = resolveRemoteNames(scanUntilComplete(generation), generation)
            discovered.forEach { discoveredDevices[it.address] = it }
            onStatus(text(R.string.dualsense_bridge_status_scan_finished))
            onLog("Live HCI Inquiry complete → ${discovered.size} device(s)")
        } finally {
            liveDiscoveryInProgress = false
        }
    }

    private fun handleCommandComplete(parameters: ByteArray) {
        if (parameters.size < 4) return
        val opcode = parameters.le16(1)
        val status = parameters[3].u8()
        when (opcode) {
            0x1405 -> handleLinkCheckResult(parameters)
            0x080D, 0x0C37 -> onLog(
                "HCI link command 0x${opcode.hex4()} " +
                    if (status == 0) "confirmed" else "rejected: status=0x${status.hex2()}"
            )
        }
    }

    private fun handleCommandStatus(parameters: ByteArray) {
        if (parameters.size < 4) return
        val opcode = parameters.le16(2)
        if (opcode != 0x0804) return
        val status = parameters[0].u8()
        onLog(
            "HCI Exit Sniff " +
                if (status == 0) "accepted; waiting for Mode Change" else "rejected: status=0x${status.hex2()}"
        )
    }

    private fun handleLinkCheckResult(parameters: ByteArray) {
        if (parameters.size < 7 || parameters.le16(1) != 0x1405) return
        val status = parameters[3].u8()
        val device = activeDevice ?: return
        if (status == 0) {
            val rssi = parameters[6].toInt()
            onDevice(HciDevice(
                device.address, device.name ?: "DualSense", device.deviceClass,
                rssi, paired = true,
                state = text(R.string.dualsense_bridge_state_connected_live)
            ))
        } else {
            // RSSI is diagnostic only. A transient command failure is not a
            // Bluetooth disconnect, so preserve the active link and HID channels.
            onLog("Read RSSI unavailable: status=0x${status.hex2()}; link preserved")
        }
    }

    private fun handleDisconnection(parameters: ByteArray) {
        if (parameters.size < 4) return
        val handle = parameters.le16(1) and 0x0FFF
        val reason = parameters[3].u8()
        val description = disconnectReason(reason)
        onLog("Disconnection Complete → handle=0x${handle.hex4()} reason=0x${reason.hex2()} ($description)")
        if (activeHandle == handle) markDisconnected(text(
            R.string.dualsense_bridge_status_disconnected_reason, description, reason.hex2()))
    }

    private fun markDisconnected(reason: String) {
        val device = activeDevice
        activeHandle = null
        nativeBluetoothHapticsRequested = false
        nativeAudioWakeEpoch = -1L
        audioQueue.clear()
        activeDevice = null
        hidInterruptRemoteCid = null
        encryptedAtMs = 0
        lastHidInputMs = 0
        hidOpenAttempted = false
        hidOpenAttemptAtMs = 0
        hidChannelsReady = false
        hidControlReady = false
        hidReadyAtMs = 0L
        hidChannelReopenAtMs = 0L
        hidInterruptReopenAtMs = 0L
        hidOpenRetries = 0
        hidInterruptOpenRetries = 0
        hidRecoveryCycles = 0
        postConnectTuningStage = 0
        postConnectTuningAtMs = 0L
        lastExitLowPowerModeAtMs = 0L
        currentLinkMode = LINK_MODE_UNKNOWN
        microphoneActiveModeRequested = false
        pendingChannels.clear()
        pendingConfigs.clear()
        channelsByLocalCid.clear()
        onStatus(reason)
        if (device != null) onDevice(HciDevice(
            device.address, device.name ?: "DualSense", device.deviceClass,
            null, paired = loadLinkKey(device.address) != null,
            state = text(R.string.dualsense_bridge_state_disconnected)
        ))
    }

    private fun open() {
        val interfaces = (0 until device.interfaceCount).map(device::getInterface)
        val conn = usbManager.openDevice(device) ?: error("Az USB eszköz nem nyitható meg")
        val iface = selectHciInterface(conn, interfaces)
        val endpoint = (0 until iface.endpointCount)
            .map(iface::getEndpoint)
            .firstOrNull {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            } ?: error("Hiányzik a HCI interrupt IN endpoint")
        val bulkIn = (0 until iface.endpointCount)
            .map(iface::getEndpoint)
            .firstOrNull {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            } ?: error("Hiányzik a HCI ACL bulk IN endpoint")
        val bulkOut = (0 until iface.endpointCount)
            .map(iface::getEndpoint)
            .firstOrNull {
                it.direction == UsbConstants.USB_DIR_OUT &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            } ?: error("Hiányzik a HCI ACL bulk OUT endpoint")
        if (!conn.claimInterface(iface, true)) {
            conn.close()
            error("A Bluetooth interfész nem foglalható le")
        }
        connection = conn
        hciInterface = iface
        eventIn = endpoint
        aclIn = bulkIn
        aclOut = bulkOut
        onLog("USB device opened")
        onLog("Interface ${iface.id} claimed; event endpoint 0x${endpoint.address.toString(16)}")
        onLog("ACL endpoint 0x${bulkIn.address.toString(16)}; raw input monitor started")
        inputWorker = Thread(::inputDispatchLoop, "dualsense-input-dispatch").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        outputWorker = Thread(::outputDispatchLoop, "dualsense-output-dispatch").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
        audioWorker = Thread(::audioDispatchLoop, "dualsense-native-bt-audio").apply {
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
        aclWorker = Thread(::aclReadLoop, "usb-hci-acl-in").apply {
            // HCI/L2CAP reception is the real-time edge of the pipeline. It must
            // not lose time to UI rendering or Wi-Fi/HID relay work.
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun outputDispatchLoop() {
        var lastWriteAtMs = 0L
        var lastSentConfig: com.example.usbbtonandroid.DualSenseOutputConfig? = null
        var lastSentEpoch = -1L
        var lastSentNativeHaptics = false
        var lastSentHeadsetRoute = false
        var lastSentAudioEngineActive = false
        while (running.get()) {
            try {
                outputSignal.take()
                val waitMs = OUTPUT_MIN_INTERVAL_MS -
                    (System.currentTimeMillis() - lastWriteAtMs)
                if (waitMs > 0) Thread.sleep(waitMs)
                outputSignal.clear()
                val config = latestOutputConfig ?: continue
                val epoch = outputConnectionEpoch
                val nativeHaptics = nativeBluetoothHapticsRequested
                val audioEngineActive = nativeHaptics || nativeBluetoothMicrophoneRequested
                val headsetRoute = nativeBluetoothHeadsetRoute
                if (config == lastSentConfig && epoch == lastSentEpoch &&
                    nativeHaptics == lastSentNativeHaptics &&
                    headsetRoute == lastSentHeadsetRoute &&
                    audioEngineActive == lastSentAudioEngineActive) continue
                val handle = activeHandle ?: continue
                val cid = hidInterruptRemoteCid ?: continue
                val report = com.example.usbbtonandroid.DualSenseBtOutputBuilder.build(
                    config, nextOutputSequence(), audioEngineActive, headsetRoute
                )
                val startedAt = System.currentTimeMillis()
                val sent = runCatching {
                    sendAcl(handle, cid, byteArrayOf(0xA2.toByte()) + report,
                        OUTPUT_WRITE_TIMEOUT_MS, false)
                    true
                }.onFailure { error ->
                    recordIncident(INCIDENT_OUTPUT_ERROR, 0L)
                    val now = System.currentTimeMillis()
                    if (now - lastOutputErrorLogMs >= OUTPUT_ERROR_LOG_INTERVAL_MS) {
                        lastOutputErrorLogMs = now
                        onLog("DualSense output write failed: ${error.message ?: error.javaClass.simpleName}")
                    }
                }.getOrDefault(false)
                lastWriteAtMs = System.currentTimeMillis()
                if (sent) {
                    lastSentConfig = config
                    lastSentEpoch = epoch
                    lastSentNativeHaptics = nativeHaptics
                    lastSentHeadsetRoute = headsetRoute
                    lastSentAudioEngineActive = audioEngineActive
                }
                val duration = System.currentTimeMillis() - startedAt
                if (duration >= OUTPUT_STALL_LOG_MS) {
                    lastOutputStallMs = duration
                    recordIncident(INCIDENT_OUTPUT_STALL, duration)
                    onLog("DualSense output USB stall: $duration ms")
                }
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun audioDispatchLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var nextSendAtNs = 0L
        while (running.get()) {
            try {
                maybeScheduleMicrophoneCadenceRecovery(SystemClock.elapsedRealtime())
                var audio = audioQueue.poll(MICROPHONE_ARM_POLL_MS, TimeUnit.MILLISECONDS)
                if (audio == null) {
                    val nowMs = SystemClock.elapsedRealtime()
                    val microphoneStalled = nativeBluetoothMicrophoneRequested &&
                        (lastBluetoothMicrophoneFrameMs == 0L ||
                            nowMs - lastBluetoothMicrophoneFrameMs >= MICROPHONE_STALE_MS)
                    if (!microphoneStalled || nowMs - lastBluetoothMicrophoneArmMs <
                        MICROPHONE_ARM_INTERVAL_MS) {
                        continue
                    }
                    // Match the known-good DS5Dongle control-only transport:
                    // at most 4 Hz while arming, then no traffic once the DS5
                    // starts its sticky Opus uplink.
                    audio = NativeAudioPayload(ByteArray(64), null,
                        configurationOnly = true, microphoneArm = true)
                }
                // Moonlight packets may arrive in short bursts. Feeding that burst
                // directly into the Bluetooth radio makes the controller speaker
                // alternate between buffer overrun and underrun. Keep only the most
                // recent waveform when we are behind and transmit on the controller's
                // native 64-sample/3 kHz report clock.
                while (audioQueue.size > 1) {
                    val newer = audioQueue.poll() ?: break
                    // A duplex setup is a state transition, not real-time
                    // audio. It must reach the controller even when fresh
                    // feedback blocks are arriving continuously.
                    if (audio.configurationOnly && !newer.configurationOnly) {
                        continue
                    }
                    // A normal game-audio/headset packet carries deliberately
                    // silent haptics. Do not let it replace a real Apollo
                    // haptics waveform merely because it arrived later.
                    if (newer.configurationOnly || !newer.speakerOnly || audio.speakerOnly) {
                        audio = newer
                    }
                }
                val nowNs = System.nanoTime()
                if (nextSendAtNs == 0L || nowNs - nextSendAtNs > AUDIO_CLOCK_RESET_NS) {
                    nextSendAtNs = nowNs
                } else if (nextSendAtNs > nowNs) {
                    LockSupport.parkNanos(nextSendAtNs - nowNs)
                }
                val handle = activeHandle ?: continue
                val cid = hidInterruptRemoteCid ?: continue
                // Native audio reports are the largest sustained traffic on the
                // controller link. Never keep feeding them into a congested
                // dongle while the high-priority HID input stream is already
                // late. Audio is real-time data, so dropping this now is better
                // than delivering an obsolete waveform after it has starved
                // buttons, sticks, or motion input.
                val hidAgeMs = SystemClock.elapsedRealtime() - lastHidInputElapsedMs
                // A state transition (mic enable/disable or headset route) is
                // not disposable real-time media. In particular, dropping the
                // final mic-disable report leaves the DualSense in an active
                // audio state and it eventually tears down the ACL link.
                if (!audio.configurationOnly && lastHidInputElapsedMs != 0L &&
                    hidAgeMs >= AUDIO_HID_PRIORITY_AGE_MS) {
                    nativeAudioReportsSkippedForInput++
                    if (nativeAudioReportsSkippedForInput == 1L ||
                        nativeAudioReportsSkippedForInput % 100L == 0L) {
                        onLog("DualSense native Bluetooth audio yielded to HID input: " +
                            "$nativeAudioReportsSkippedForInput reports, HID age ${hidAgeMs}ms")
                    }
                    continue
                }
                val epoch = outputConnectionEpoch
                if (nativeAudioWakeEpoch != epoch && !audio.configurationOnly) {
                    // Use the same narrow audio-only configuration for initial
                    // activation as for mic and headset transitions. The old
                    // broad 0x32 wake snapshot carried independent LED and
                    // low-volume audio values which could overwrite live host
                    // feedback after a jack transition.
                    val wake = com.example.usbbtonandroid.DualSenseBtAudioBuilder.buildDuplexSetup(
                        nextOutputSequence(), nativeBluetoothMicrophoneRequested,
                        nativeBluetoothHeadsetRoute)
                    sendAcl(handle, cid, byteArrayOf(0xa2.toByte()) + wake,
                        AUDIO_WRITE_TIMEOUT_MS, false)
                    sendAmplifiedBluetoothSpeakerSetup(handle, cid)
                    nativeAudioWakeEpoch = epoch
                    outputSignal.offer(Unit)
                    onLog("DualSense native Bluetooth audio/haptics path enabled")
                }
                if (audio.configurationOnly) {
                    if (!audio.microphoneArm || !nativeBluetoothMicrophoneRequested) continue
                    // The standalone bridge microphone diagnostic has no
                    // Apollo feedback yet. Use the bridge's neutral controller
                    // state in that case; during a stream the latest host state
                    // still takes precedence and is copied byte-for-byte.
                    val config = latestOutputConfig
                        ?: com.example.usbbtonandroid.DualSenseOutputConfig()
                    audioPacketCounter = (audioPacketCounter + 1) and 0xff
                    val arm = com.example.usbbtonandroid.DualSenseBtAudioBuilder
                        .buildMicrophoneArm(nextOutputSequence(), audioPacketCounter, config,
                            nativeBluetoothHeadsetRoute)
                    sendAcl(handle, cid, byteArrayOf(0xa2.toByte()) + arm,
                        AUDIO_WRITE_TIMEOUT_MS, false)
                    lastBluetoothMicrophoneArmMs = SystemClock.elapsedRealtime()
                    onLog("DualSense BT microphone arm sent; waiting for native Opus uplink")
                    continue
                }
                audioPacketCounter = (audioPacketCounter + 1) and 0xff
                val report = com.example.usbbtonandroid.DualSenseBtAudioBuilder.build(
                    audio.haptics, nextOutputSequence(), audioPacketCounter, audio.speakerOpus,
                    nativeBluetoothHeadsetRoute, nativeBluetoothMicrophoneRequested
                )
                runCatching {
                    sendAcl(handle, cid, byteArrayOf(0xa2.toByte()) + report,
                        AUDIO_WRITE_TIMEOUT_MS, false)
                    nextSendAtNs += AUDIO_REPORT_INTERVAL_NS
                    nativeAudioReportsSent++
                    if (nativeAudioReportsSent == 1L || nativeAudioReportsSent % 100L == 0L) {
                        onLog("DualSense native Bluetooth haptics sent: $nativeAudioReportsSent reports")
                    }
                }.onFailure { error ->
                    val now = System.currentTimeMillis()
                    if (now - lastOutputErrorLogMs >= OUTPUT_ERROR_LOG_INTERVAL_MS) {
                        lastOutputErrorLogMs = now
                        onLog("DualSense native Bluetooth haptics write failed: " +
                            (error.message ?: error.javaClass.simpleName))
                    }
                }
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /** Profile hook: Generic chooses the first standard Bluetooth HCI interface. */
    protected open fun selectHciInterface(
        connection: UsbDeviceConnection,
        interfaces: List<UsbInterface>
    ): UsbInterface = interfaces.firstOrNull(::isBluetoothInterface)
        ?: interfaces.firstOrNull(::hasHciEndpointLayout)
        ?: error("Nincs használható Bluetooth HCI interfész")

    /**
     * Re-arm only an actually under-running Bluetooth microphone session.
     *
     * A DualSense uplink frame represents 10 ms, so a healthy link supplies
     * roughly 100 frames per second.  This intentionally does not touch link
     * mode: forcing Exit Sniff is known to make a healthy microphone worse.
     */
    private fun maybeScheduleMicrophoneCadenceRecovery(nowMs: Long) {
        if (!nativeBluetoothMicrophoneRequested) return
        val startedAt = btMicWatchWindowStartedMs
        if (startedAt == 0L || nowMs - startedAt < MICROPHONE_RATE_WINDOW_MS) return
        btMicWatchWindowStartedMs = nowMs
        val received = btMicFramesInWatchWindow.getAndSet(0)
        if (received >= MICROPHONE_MIN_FRAMES_PER_WINDOW ||
            nowMs - lastBluetoothMicrophoneArmMs < MICROPHONE_RATE_RECOVERY_INTERVAL_MS) {
            return
        }
        onLog(
            "DualSense BT microphone cadence low: $received/${MICROPHONE_RATE_WINDOW_MS}ms " +
                "→ re-arming duplex session"
        )
        val arm = NativeAudioPayload(ByteArray(64), null,
            configurationOnly = true, microphoneArm = true)
        if (!audioQueue.offer(arm)) {
            // Configuration is a recovery state transition.  Keep it ahead of
            // stale real-time feedback; the normal queue logic preserves it.
            audioQueue.poll()
            audioQueue.offer(arm)
        }
    }

    /** Re-assert the dedicated amplified speaker profile without touching host-owned feedback. */
    private fun sendAmplifiedBluetoothSpeakerSetup(handle: Int, cid: Int) {
        if (nativeBluetoothHeadsetRoute) return
        val speakerSetup = com.example.usbbtonandroid.DualSenseBtOutputBuilder
            .buildAmplifiedSpeakerSetup(nextOutputSequence(), nativeBluetoothMicrophoneRequested)
        sendAcl(handle, cid, byteArrayOf(0xa2.toByte()) + speakerSetup,
            AUDIO_WRITE_TIMEOUT_MS, false)
    }

    @Synchronized private fun nextOutputSequence(): Int = outputSequence++

    private fun inputDispatchLoop() {
        while (running.get()) {
            try {
                var packet = inputQueue.take()
                // Every DualSense input report is a complete state snapshot. If the
                // consumer briefly stalls, replaying queued historical states makes
                // buttons appear stuck long after they were released. Collapse any
                // backlog and process only the newest available snapshot.
                while (true) {
                    packet = inputQueue.poll() ?: break
                }
                val startedAt = System.currentTimeMillis()
                onAclPacket(packet)
                val duration = System.currentTimeMillis() - startedAt
                lastInputDispatchMs = SystemClock.elapsedRealtime()
                lastInputDispatchDurationMs = duration
                if (duration >= INPUT_DISPATCH_STALL_LOG_MS) {
                    recordIncident(INCIDENT_APP_STALL, duration)
                    onLog("App input dispatch stall: $duration ms")
                }
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                onLog("Kontrolleradat-feldolgozási hiba: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun enqueueInput(packet: AclPacket) {
        if (inputQueue.offer(packet)) return
        // Real-time controls prefer the newest state. Never block the USB reader
        // behind stale gyro/stick samples when the consumer briefly falls behind.
        inputQueue.poll()
        inputQueue.offer(packet)
        droppedInputPackets++
        recordIncident(INCIDENT_QUEUE_OVERRUN, 0L)
        if (droppedInputPackets == 1L || droppedInputPackets % 100L == 0L) {
            onLog("Input queue overrun: $droppedInputPackets packet(s) dropped")
        }
    }

    /** Profile seam: Generic Profile 1 receives an already-complete ACL packet. */
    protected open fun normalizeIncomingAclPacket(packet: AclPacket): AclPacket? = packet

    private fun aclReadLoop() {
        // Reuse one carry buffer instead of copying the complete pending stream on
        // every report. This keeps GC away from the real-time input edge.
        val pending = ByteArray(MAX_USB_CARRY_BYTES)
        var pendingSize = 0
        val conn = connection ?: return
        val endpoint = aclIn ?: return
        val requests = ArrayList<UsbRequest>(ACL_USB_REQUEST_COUNT)
        try {
            repeat(ACL_USB_REQUEST_COUNT) {
                val request = UsbRequest()
                check(request.initialize(conn, endpoint)) { "ACL USB request initialization failed" }
                val buffer = ByteBuffer.allocate(ACL_USB_REQUEST_BYTES)
                request.clientData = buffer
                check(request.queue(buffer)) { "ACL USB request queue failed" }
                requests += request
            }
            onLog("ACL asynchronous receive ring armed: $ACL_USB_REQUEST_COUNT request(s)")
        } catch (error: Throwable) {
            requests.forEach { request ->
                runCatching { request.cancel() }
                runCatching { request.close() }
            }
            onLog("ACL asynchronous receive setup failed: ${error.message ?: error.javaClass.simpleName}")
            return
        }
        try {
        while (running.get()) {
            if (pendingSize == pending.size) {
                onLog("ACL carry buffer full ($pendingSize); reset")
                pendingSize = 0
            }
            val request = try {
                conn.requestWait(ACL_READ_TIMEOUT_MS.toLong())
            } catch (_: TimeoutException) {
                continue
            } catch (error: Throwable) {
                if (running.get()) onLog("ACL USB request wait failed: ${error.message ?: error.javaClass.simpleName}")
                break
            } ?: continue
            val buffer = request.clientData as? ByteBuffer ?: continue
            val size = buffer.position()
            if (size > 0 && pendingSize + size > pending.size) {
                onLog("ACL carry buffer overflow ($pendingSize + $size); reset")
                pendingSize = 0
            }
            if (size > 0) {
                buffer.flip()
                buffer.get(pending, pendingSize, size)
            }
            buffer.clear()
            if (running.get() && !request.queue(buffer)) {
                onLog("ACL USB request requeue failed")
                break
            }
            if (size <= 0) continue
            lastUsbAclAtMs = SystemClock.elapsedRealtime()
            pendingSize += size
            var offset = 0
            while (offset + 4 <= pendingSize) {
                val handleAndFlags = pending.le16(offset)
                val dataLength = pending.le16(offset + 2)
                if (dataLength > MAX_ACL_PAYLOAD) {
                    onLog("Érvénytelen ACL hossz=$dataLength; USB stream újraszinkronizálva")
                    pendingSize = 0
                    offset = 0
                    break
                }
                val packetEnd = offset + 4 + dataLength
                if (packetEnd > pendingSize) break
                val handle = handleAndFlags and 0x0FFF
                val packetBoundary = (handleAndFlags ushr 12) and 0x03
                val aclPayload = pending.copyOfRange(offset + 4, packetEnd)
                val isStart = packetBoundary == 0 || packetBoundary == 2
                var cid = if (isStart && aclPayload.size >= 4) aclPayload.le16(2) else null
                val l2capLength = if (isStart && aclPayload.size >= 4) aclPayload.le16(0) else null
                var payload = if (cid != null) aclPayload.copyOfRange(4, aclPayload.size) else aclPayload
                var packet = AclPacket(
                    handle = handle,
                    packetBoundary = packetBoundary,
                    cid = cid,
                    l2capLength = l2capLength,
                    payload = payload,
                    // Full hex conversion on every high-rate gyro report created
                    // avoidable garbage collection pressure.
                    rawHex = ""
                )
                // Profile 1 receives complete HID reports in the normal ACL
                // layout. Profiles whose USB transport fragments an L2CAP
                // payload may reassemble it before the report parser sees it.
                packet = normalizeIncomingAclPacket(packet) ?: run {
                    offset = packetEnd
                    continue
                }
                cid = packet.cid
                payload = packet.payload
                val now = System.currentTimeMillis()
                if (now - lastAclLogMs >= ACL_LOG_INTERVAL_MS) {
                    lastAclLogMs = now
                    onLog(
                        "RX ACL handle=0x${handle.hex4()} pb=$packetBoundary " +
                            "cid=${cid?.let { "0x${it.hex4()}" } ?: "continuation"} len=$dataLength"
                    )
                }
                if (cid == 0x0001) {
                    runCatching { handleL2capSignaling(handle, payload) }
                        .onFailure { error ->
                            onLog(
                                "L2CAP signaling error: " +
                                    (error.message ?: error.javaClass.simpleName)
                            )
                        }
                }
                val isHidInput = cid != null && cid != 0x0001 &&
                    payload.firstOrNull()?.u8() == 0xA1
                if (cid != null && cid != 0x0001 && !isHidInput) {
                    val channel = channelsByLocalCid[cid]
                    onNonHidL2capPayload(handle, cid, channel?.remoteCid, channel?.psm, payload)
                }
                // Bluetooth Duplex audio is sent by the DualSense on the same
                // 0x31 input-report path as gamepad state. A microphone frame
                // is tagged by bit 1 of report byte 2 and contains exactly one
                // 71-byte Opus packet at report offset 4 (including the A1 HID
                // input prefix). Never enqueue it as a controller report: it
                // otherwise becomes phantom stick/button input at mic rate.
                //
                // Do not infer the Opus size from the Bluetooth report tail.
                // The tail also carries report/checksum data, and treating it
                // as compressed audio corrupts the Opus frame boundaries.
                val microphoneOpusOffset = 4
                val microphoneOpusSize = 71
                val isDualSenseMicrophoneCandidate = isHidInput &&
                    payload.size >= microphoneOpusOffset + microphoneOpusSize &&
                    payload[1].u8() == 0x31
                if (isDualSenseMicrophoneCandidate) {
                    btMicCandidateFramesSinceLog++
                    btMicCandidateTocCounts[payload[microphoneOpusOffset].u8()]++
                    if (now - btMicCandidateLastLogMs >= 1000L) {
                        val topTocs = btMicCandidateTocCounts.indices
                            .filter { btMicCandidateTocCounts[it] > 0 }
                            .sortedByDescending { btMicCandidateTocCounts[it] }
                            .take(4)
                            .joinToString { toc -> "%02X:%d".format(toc, btMicCandidateTocCounts[toc]) }
                        onLog("BT mic candidates → $btMicCandidateFramesSinceLog frame/s, TOC [$topTocs]")
                        btMicCandidateFramesSinceLog = 0
                        btMicCandidateTocCounts.fill(0)
                        btMicCandidateLastLogMs = now
                    }
                }
                val isDualSenseMicrophoneFrame = isDualSenseMicrophoneCandidate &&
                    // Same discriminator used by SDL's working Windows
                    // DualSense Bluetooth implementation.  The byte following
                    // report ID 0x31 is a dedicated mic tag; the apparent Opus
                    // first byte is not a report-type discriminator.
                    (payload[2].u8() and 0x02) != 0
                if (isDualSenseMicrophoneFrame) {
                    val micSequence = payload[3].u8()
                    if (btMicLastSequence >= 0) {
                        val delta = (micSequence - btMicLastSequence) and 0xFF
                        if (delta > 1) btMicSequenceGapsSinceLog += delta - 1
                        if (delta in btMicSequenceDeltaCounts.indices) {
                            btMicSequenceDeltaCounts[delta]++
                        }
                    }
                    btMicLastSequence = micSequence
                    btMicFramesSinceLog++
                    if (now - btMicLastLogMs >= 1000L) {
                        onLog(
                            "BT mic RX → $btMicFramesSinceLog frame/s, " +
                                "$btMicSequenceGapsSinceLog sequence gap(s), seq=$micSequence, delta=" +
                                btMicSequenceDeltaCounts.indices
                                    .filter { btMicSequenceDeltaCounts[it] > 0 }
                                    .joinToString(",") { "$it:${btMicSequenceDeltaCounts[it]}" }
                        )
                        btMicFramesSinceLog = 0
                        btMicSequenceGapsSinceLog = 0
                        btMicSequenceDeltaCounts.fill(0)
                        btMicLastLogMs = now
                    }
                    onDualSenseMicrophoneFrame(
                        payload.copyOfRange(
                            microphoneOpusOffset,
                            microphoneOpusOffset + microphoneOpusSize
                        ),
                        micSequence
                    )
                    lastBluetoothMicrophoneFrameMs = now
                    btMicFramesInWatchWindow.incrementAndGet()
                }
                if (isHidInput) {
                    val previousHidInputMs = lastHidInputMs
                    lastHidInputMs = now
                    if (previousHidInputMs == 0L) {
                        // Keep all optional radio tuning out of pairing and L2CAP
                        // negotiation. Enable it only after a real input report
                        // proves the complete HID path is operational.
                        postConnectTuningStage = 1
                        postConnectTuningAtMs = now
                    }
                    lastHidInputElapsedMs = SystemClock.elapsedRealtime()
                    if (previousHidInputMs != 0L) {
                        val interval = now - previousHidInputMs
                        val sampleQuality = when {
                            interval <= 12L -> 100
                            interval <= 30L -> 90
                            interval <= 60L -> 75
                            interval <= 120L -> 55
                            interval <= HID_GAP_LOG_MS -> 30
                            else -> 0
                        }
                        linkQualityPercent = (linkQualityPercent * 9 + sampleQuality) / 10
                    }
                    if (previousHidInputMs != 0L && now - previousHidInputMs >= HID_GAP_LOG_MS) {
                        lastHidGapMs = now - previousHidInputMs
                        recordIncident(INCIDENT_RADIO_USB_GAP, lastHidGapMs)
                        onLog("HID radio/USB input gap: $lastHidGapMs ms")
                    }
                    hidChannelsReady = true
                    if (now - lastActiveDevicePublishMs >= DEVICE_PUBLISH_INTERVAL_MS) {
                        lastActiveDevicePublishMs = now
                        activeDevice?.let { publishDevice(it,
                            text(R.string.dualsense_bridge_state_connected_hid), paired = true) }
                    }
                }
                acknowledgeCompletedAclPacket(handle)
                if (isHidInput && !isDualSenseMicrophoneFrame) enqueueInput(packet)
                offset = packetEnd
            }
            if (offset > 0) {
                val remaining = pendingSize - offset
                if (remaining > 0) {
                    System.arraycopy(pending, offset, pending, 0, remaining)
                }
                pendingSize = remaining
            }
        }
        } finally {
            requests.forEach { request ->
                runCatching { request.cancel() }
                runCatching { request.close() }
            }
        }
    }

    private fun disconnectReason(reason: Int) = when (reason) {
        0x08 -> "kapcsolati időtúllépés"
        0x13 -> "a kontroller bontotta"
        0x14 -> "a helyi host bontotta"
        0x16 -> "a helyi host kikapcsolta"
        0x22 -> "LMP válaszidő-túllépés"
        else -> "Bluetooth bontás"
    }

    /**
     * Profile seam only. Profile 1 uses this implementation unchanged; adapter
     * subclasses may apply a narrowly-scoped startup retry for their own
     * transport quirks.
     */
    protected open fun requireCommandComplete(opcode: Int, parameters: ByteArray = byteArrayOf()): ByteArray {
        sendCommand(opcode, parameters)
        val deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS
        while (running.get() && System.currentTimeMillis() < deadline) {
            val event = readEvent((deadline - System.currentTimeMillis()).coerceAtMost(1000).toInt()) ?: continue
            if (event.code == 0x0E && event.parameters.size >= 4) {
                val completedOpcode = event.parameters.le16(1)
                if (completedOpcode == opcode) {
                    val result = event.parameters.copyOfRange(3, event.parameters.size)
                    check(result.isNotEmpty() && result[0].u8() == 0) {
                        "HCI 0x${opcode.hex4()} status=0x${result.firstOrNull()?.u8()?.hex2() ?: "??"}"
                    }
                    return result
                }
            }
            if (event.code == 0x0F && event.parameters.size >= 4 &&
                event.parameters.le16(2) == opcode && event.parameters[0].u8() != 0
            ) error("HCI 0x${opcode.hex4()} status=0x${event.parameters[0].u8().hex2()}")
            if (event.code == 0x04) {
                // Do not lose a controller's reconnect request while this
                // helper is waiting for a harmless adapter command completion.
                queueIncomingConnection(event.parameters)
            }
        }
        error("HCI 0x${opcode.hex4()} időtúllépés")
    }

    /**
     * Tell the raw USB HCI adapter how much incoming ACL data this host can
     * retain, then enable the standard controller-to-host credit mechanism.
     *
     * This is intentionally negotiated before inquiry/pairing because the HCI
     * specification only permits changing it while no connection exists.
     */
    /**
     * Profile seam only. Profile 1 keeps the proven controller-to-host credit
     * negotiation; adapters that falsely advertise this optional feature may
     * decline it in their dedicated profile.
     */
    protected open fun configureControllerToHostAclFlowControl() {
        controllerToHostFlowControlEnabled = false
        hostCompletedAclHandle = -1
        hostCompletedAclPackets = 0
        runCatching {
            // HCI_Host_Buffer_Size: 679-byte ACL packets, 32 host slots, no SCO.
            // 679 matches the adapter's reported ACL maximum on this bridge.
            requireCommandComplete(0x0C33, byteArrayOf(
                (HOST_ACL_BUFFER_BYTES and 0xFF).toByte(),
                (HOST_ACL_BUFFER_BYTES ushr 8).toByte(),
                0x00,
                (HOST_ACL_BUFFER_PACKETS and 0xFF).toByte(),
                (HOST_ACL_BUFFER_PACKETS ushr 8).toByte(),
                0x00, 0x00
            ))
            requireCommandComplete(0x0C31, byteArrayOf(0x01))
            controllerToHostFlowControlEnabled = true
            onLog("HCI controller→host ACL flow control enabled")
        }.onFailure { error ->
            // The HCI default is unlimited controller→host delivery. Keep that
            // safe fallback for adapters that do not implement this optional
            // BR/EDR command rather than failing bridge startup.
            onLog("HCI controller→host flow control unavailable: " +
                (error.message ?: error.javaClass.simpleName))
        }
    }

    /** Returns controller-to-host ACL credits in small batches without logging. */
    private fun acknowledgeCompletedAclPacket(handle: Int) {
        if (!controllerToHostFlowControlEnabled || handle == 0) return
        if (hostCompletedAclHandle != handle) {
            flushCompletedAclPackets()
            hostCompletedAclHandle = handle
        }
        hostCompletedAclPackets++
        if (hostCompletedAclPackets >= HOST_ACL_COMPLETION_BATCH) {
            flushCompletedAclPackets()
        }
    }

    private fun flushCompletedAclPackets() {
        val handle = hostCompletedAclHandle
        val completed = hostCompletedAclPackets
        if (!controllerToHostFlowControlEnabled || handle < 0 || completed <= 0) return
        hostCompletedAclPackets = 0
        runCatching {
            // HCI_Host_Number_Of_Completed_Packets is explicitly allowed at
            // any time during a connection and has no completion event.
            sendCommand(0x0C35, byteArrayOf(
                0x01,
                handle.toByte(), (handle ushr 8).toByte(),
                completed.toByte(), (completed ushr 8).toByte()
            ), logPacket = false)
        }.onFailure { error ->
            controllerToHostFlowControlEnabled = false
            onLog("HCI controller→host credit return failed; disabled: " +
                (error.message ?: error.javaClass.simpleName))
        }
    }

    private fun scanUntilComplete(operationGeneration: Long): List<InquiryDevice> {
        val discovered = linkedMapOf<String, InquiryDevice>()
        val deadline = System.currentTimeMillis() + INQUIRY_TIMEOUT_MS
        while (running.get() && System.currentTimeMillis() < deadline) {
            if (controlGeneration.get() != operationGeneration) {
                cancelInquiryForNewOperation()
                return discovered.values.toList()
            }
            val event = readEvent(1000) ?: continue
            when (event.code) {
                0x04 -> {
                    queueIncomingConnection(event.parameters)
                    cancelInquiryForNewOperation()
                    return discovered.values.toList()
                }
                0x01 -> {
                    val status = event.parameters.firstOrNull()?.u8() ?: -1
                    if (status != 0) error("Inquiry Complete status=0x${status.hex2()}")
                    onLog("Inquiry Complete → Success")
                    return discovered.values.toList()
                }
                0x02 -> parseInquiryResults(event.parameters, withRssi = false, discovered)
                0x22 -> parseInquiryResults(event.parameters, withRssi = true, discovered)
                0x2F -> parseExtendedInquiryResult(event.parameters, discovered)
                0x0F -> {
                    if (event.parameters.size >= 4 && event.parameters.le16(2) == 0x0401) {
                        check(event.parameters[0].u8() == 0) { "Inquiry indítása sikertelen" }
                        onLog("Inquiry started")
                    }
                }
            }
        }
        error("Az Inquiry nem fejeződött be időben")
    }

    private fun parseInquiryResults(
        p: ByteArray,
        withRssi: Boolean,
        discovered: MutableMap<String, InquiryDevice>
    ) {
        if (p.isEmpty()) return
        val count = p[0].u8()
        val recordSize = if (withRssi) 14 else 13
        for (i in 0 until count) {
            val offset = 1 + i * recordSize
            if (offset + 12 >= p.size) break
            val address = formatAddress(p, offset)
            val deviceClass = "%02X%02X%02X".format(p[offset + 10].u8(), p[offset + 9].u8(), p[offset + 8].u8())
            val rssi = if (withRssi && offset + 13 < p.size) p[offset + 13].toInt() else null
            discovered[address] = InquiryDevice(
                address = address,
                addressLittleEndian = p.copyOfRange(offset, offset + 6),
                pageScanRepetitionMode = p[offset + 6],
                clockOffsetLow = p[offset + 11],
                clockOffsetHigh = p[offset + 12],
                deviceClass = deviceClass,
                rssi = rssi,
                name = null
            )
            reportDevice(address, deviceClass, rssi, null)
        }
    }

    private fun parseExtendedInquiryResult(p: ByteArray, discovered: MutableMap<String, InquiryDevice>) {
        if (p.size < 15) return
        val address = formatAddress(p, 1)
        val deviceClass = "%02X%02X%02X".format(p[11].u8(), p[10].u8(), p[9].u8())
        val rssi = p[14].toInt()
        val name = parseEirName(p, 15)
        discovered[address] = InquiryDevice(
            address = address,
            addressLittleEndian = p.copyOfRange(1, 7),
            pageScanRepetitionMode = p[7],
            clockOffsetLow = p[12],
            clockOffsetHigh = p[13],
            deviceClass = deviceClass,
            rssi = rssi,
            name = name
        )
        reportDevice(address, deviceClass, rssi, name)
    }

    private fun resolveRemoteNames(
        devices: List<InquiryDevice>,
        operationGeneration: Long
    ): List<InquiryDevice> {
        val resolved = devices.toMutableList()
        devices.filter { it.name.isNullOrBlank() }.forEachIndexed { index, device ->
            if (!running.get() || controlGeneration.get() != operationGeneration) return resolved
            onStatus(text(R.string.dualsense_bridge_status_resolving_name,
                index + 1, devices.size))
            onLog("Remote Name Request → ${device.address}")
            val parameters = ByteArray(10)
            device.addressLittleEndian.copyInto(parameters)
            parameters[6] = device.pageScanRepetitionMode
            parameters[7] = 0
            parameters[8] = device.clockOffsetLow
            parameters[9] = device.clockOffsetHigh
            sendCommand(0x0419, parameters)

            val deadline = System.currentTimeMillis() + REMOTE_NAME_TIMEOUT_MS
            while (running.get() && System.currentTimeMillis() < deadline) {
                if (controlGeneration.get() != operationGeneration) return resolved
                val event = readEvent(1000) ?: continue
                if (event.code == 0x04) {
                    queueIncomingConnection(event.parameters)
                    cancelRemoteNameRequest(device)
                    return resolved
                }
                if (event.code == 0x0F && event.parameters.size >= 4 &&
                    event.parameters.le16(2) == 0x0419 &&
                    event.parameters[0].u8() != 0
                ) {
                    onLog("Remote Name Request rejected: status=0x${event.parameters[0].u8().hex2()}")
                    break
                }
                if (event.code == 0x07 && event.parameters.size >= 7 &&
                    formatAddress(event.parameters, 1) == device.address
                ) {
                    val status = event.parameters[0].u8()
                    if (status == 0) {
                        val zero = (7 until event.parameters.size)
                            .firstOrNull { event.parameters[it] == 0.toByte() }
                            ?: event.parameters.size
                        val name = event.parameters.copyOfRange(7, zero).toString(Charsets.UTF_8)
                        onLog("Remote Name → $name")
                        reportDevice(device.address, device.deviceClass, device.rssi, name)
                        val itemIndex = resolved.indexOfFirst { it.address == device.address }
                        if (itemIndex >= 0) resolved[itemIndex] = device.copy(name = name)
                    } else {
                        onLog("Remote Name failed: status=0x${status.hex2()}")
                    }
                    break
                }
            }
        }
        return resolved
    }

    private fun connectAndPair(
        device: InquiryDevice,
        incomingAddress: ByteArray? = null,
        operationGeneration: Long = controlGeneration.get()
    ) {
        if (incomingAddress != null &&
            pendingConnection?.address?.uppercase() == device.address.uppercase()
        ) {
            pendingConnection = null
            onLog("Incoming controller link superseded matching Connect request")
        }
        onStatus(text(R.string.dualsense_bridge_status_connecting_device, device.name ?: "DualSense"))
        publishDevice(device, text(R.string.dualsense_bridge_state_connecting))
        if (incomingAddress != null) {
            onLog("Accept Connection Request → ${device.address}")
            // Preserve the controller as BR/EDR master for controller-initiated
            // reconnects. This matches the proven DS5Dongle link setup and lets
            // the DualSense schedule its own 100 Hz microphone ACL uplink.
            sendCommand(0x0409, incomingAddress + byteArrayOf(incomingConnectionRole()))
        } else {
            onLog("Create Connection → ${device.address}")
            val parameters = ByteArray(13)
            device.addressLittleEndian.copyInto(parameters)
            parameters[6] = 0x18
            parameters[7] = 0xCC.toByte()
            parameters[8] = device.pageScanRepetitionMode
            parameters[9] = 0
            parameters[10] = device.clockOffsetLow
            parameters[11] = device.clockOffsetHigh
            parameters[12] = 0x01
            sendCommand(0x0405, parameters)
        }

        val deadline = System.currentTimeMillis() + CONNECTION_TIMEOUT_MS
        var connectionHandle: Int? = null
        var pairingComplete = false
        while (running.get() && System.currentTimeMillis() < deadline) {
            if (controlGeneration.get() != operationGeneration) {
                cancelConnectionForNewOperation(device, connectionHandle)
                throw OperationSupersededException()
            }
            val event = readEvent(1000) ?: continue
            when (event.code) {
                0x03 -> {
                    if (event.parameters.size < 11) continue
                    val status = event.parameters[0].u8()
                    if (status != 0) error("Connection Complete status=0x${status.hex2()}")
                    connectionHandle = event.parameters.le16(1) and 0x0FFF
                    activeHandle = connectionHandle
                    activeDevice = device
                    if (pendingConnection?.address?.uppercase() == device.address.uppercase()) {
                        pendingConnection = null
                        onLog("Connection Complete consumed matching Connect request")
                    }
                    onLog("Connection Complete → handle=0x${connectionHandle.hex4()}")
                    publishDevice(device, text(R.string.dualsense_bridge_state_connected_live),
                        paired = loadLinkKey(device.address) != null)
                    if (event.parameters[10].u8() != 0) {
                        encryptedAtMs = System.currentTimeMillis()
                        lastHidInputMs = 0
                        hidOpenAttempted = false
                        hidChannelsReady = false
                        hidControlReady = false
                        hidOpenRetries = 0
                        hidInterruptOpenRetries = 0
                        onStatus(text(R.string.dualsense_bridge_status_restored_encrypted))
                        publishDevice(device, "Titkosítva • HID-re vár", paired = true)
                        return
                    }
                    onStatus(text(R.string.dualsense_bridge_status_pairing))
                    publishDevice(device, "Hitelesítés…")
                    sendCommand(0x0411, byteArrayOf(
                        (connectionHandle and 0xFF).toByte(),
                        (connectionHandle ushr 8).toByte()
                    ))
                }
                0x06 -> {
                    val status = event.parameters.firstOrNull()?.u8() ?: -1
                    onLog("Authentication Complete → status=0x${status.hex2()}")
                    if (status == 0 && connectionHandle != null) {
                        sendCommand(0x0413, byteArrayOf(
                            (connectionHandle and 0xFF).toByte(),
                            (connectionHandle ushr 8).toByte(),
                            0x01
                        ))
                    }
                }
                0x08 -> {
                    if (event.parameters.size >= 4) {
                        val status = event.parameters[0].u8()
                        val enabled = event.parameters[3].u8()
                        onLog("Encryption Change → status=0x${status.hex2()}, enabled=$enabled")
                        if (status == 0 && enabled != 0) {
                            pairingComplete = true
                            encryptedAtMs = System.currentTimeMillis()
                            lastHidInputMs = 0
                            hidOpenAttempted = false
                            hidChannelsReady = false
                            hidControlReady = false
                            hidOpenRetries = 0
                            hidInterruptOpenRetries = 0
                            onStatus(text(R.string.dualsense_bridge_status_encrypted))
                            publishDevice(device, "Titkosítva • HID-re vár", paired = true)
                            authorizedPairingAddress = null
                            return
                        }
                    }
                }
                0x16 -> {
                    val address = formatAddress(event.parameters, 0)
                    onLog("PIN Code Request → $address")
                    sendPinCodeReply(event.parameters.copyOfRange(0, 6), "0000")
                }
                0x17 -> handleLinkKeyRequest(event.parameters)
                0x18 -> {
                    if (event.parameters.size >= 23) {
                        val address = formatAddress(event.parameters, 0)
                        val key = event.parameters.copyOfRange(6, 22).toHex()
                        saveLinkKey(address, key)
                        onLog("Link Key Notification → $address (elmentve, type=0x${event.parameters[22].u8().hex2()})")
                    }
                }
                0x31 -> {
                    val address = event.parameters.copyOfRange(0, 6)
                    onLog("IO Capability Request → ${formatAddress(address, 0)}")
                    sendCommand(0x042B, address + byteArrayOf(0x03, 0x00, 0x02))
                }
                0x32 -> {
                    if (event.parameters.size >= 9) {
                        onLog(
                            "IO Capability Response ← ${formatAddress(event.parameters, 0)} " +
                                "io=0x${event.parameters[6].u8().hex2()} auth=0x${event.parameters[8].u8().hex2()}"
                        )
                    }
                }
                0x33 -> {
                    val address = event.parameters.copyOfRange(0, 6)
                    onLog("User Confirmation Request → ${formatAddress(address, 0)}; auto-confirm")
                    sendCommand(0x042C, address)
                }
                0x36 -> {
                    val status = event.parameters.firstOrNull()?.u8() ?: -1
                    onLog("Simple Pairing Complete → status=0x${status.hex2()}")
                    pairingComplete = status == 0
                }
                0x05 -> {
                    val reason = event.parameters.getOrNull(3)?.u8() ?: -1
                    markDisconnected(text(R.string.dualsense_bridge_status_disconnected_code,
                        reason.hex2()))
                    error("Disconnected: reason=0x${reason.hex2()}")
                }
            }
        }
        if (pairingComplete) {
            onStatus(text(R.string.dualsense_bridge_status_paired))
        } else {
            error("Kapcsolódási/párosítási időtúllépés")
        }
    }

    /** Stops an inquiry without waiting for its normal 15 second completion. */
    private fun cancelInquiryForNewOperation() {
        if (!running.get()) return
        onLog("Inquiry cancelled → newer controller action takes priority")
        // HCI Inquiry Cancel. Do not wait here: this worker must return to the
        // host loop, which will consume the command result and the next intent.
        runCatching { sendCommand(0x0402) }
    }

    /** Stops an in-flight Remote Name Request so an incoming DualSense page wins. */
    private fun cancelRemoteNameRequest(device: InquiryDevice) {
        if (!running.get()) return
        onLog("Remote Name Request cancelled → incoming controller takes priority")
        // HCI Remote Name Request Cancel uses the target controller's BD_ADDR.
        // It is distinct from Inquiry Cancel and is valid after inquiry has
        // already completed.
        runCatching { sendCommand(0x041A, device.addressLittleEndian) }
    }

    /** Queues an incoming controller request so discovery/name lookup cannot lose it. */
    private fun queueIncomingConnection(parameters: ByteArray) {
        if (parameters.size < 10) return
        val addressBytes = parameters.copyOfRange(0, 6)
        val address = formatAddress(addressBytes, 0)
        val known = discoveredDevices[address] ?: InquiryDevice(
            address, addressBytes, 0x01, 0, 0,
            "%02X%02X%02X".format(
                parameters[8].u8(), parameters[7].u8(), parameters[6].u8()
            ),
            null, text(R.string.dualsense_bridge_paired_name)
        )
        onLog("Incoming Connection Request ← $address")
        val paired = loadLinkKey(address) != null
        val explicitlyAuthorized = authorizedPairingAddress == address
        if (paired || explicitlyAuthorized) {
            pendingIncomingConnection = known
        } else {
            onLog("Incoming connection rejected: $address (not paired/authorized)")
            sendCommand(0x040A, addressBytes + byteArrayOf(0x0F))
        }
    }

    private fun cancelConnectionForNewOperation(device: InquiryDevice, handle: Int?) {
        if (handle == null) {
            onLog("Create Connection cancelled → newer controller action takes priority")
            // HCI Create Connection Cancel, valid before Connection Complete.
            sendCommand(0x0408, device.addressLittleEndian)
        } else {
            onLog("Disconnecting provisional link 0x${handle.hex4()} → newer controller action")
            sendDisconnect(handle)
        }
    }

    private fun sendDisconnect(handle: Int) {
        sendCommand(0x0406, byteArrayOf(
            handle.toByte(), (handle ushr 8).toByte(), 0x13
        ))
    }

    private class OperationSupersededException : Exception()

    private fun handleLinkKeyRequest(parameters: ByteArray) {
        if (parameters.size < 6) return
        val addressBytes = parameters.copyOfRange(0, 6)
        val address = formatAddress(addressBytes, 0)
        val stored = loadLinkKey(address)
        if (stored != null && stored.length == 32) {
            onLog("Link Key Request → mentett kulcs használata")
            sendCommand(0x040B, addressBytes + stored.hexToBytes())
        } else {
            onLog("Link Key Request → nincs mentett kulcs")
            sendCommand(0x040C, addressBytes)
        }
    }

    private fun sendPinCodeReply(address: ByteArray, pin: String) {
        val pinBytes = pin.encodeToByteArray().copyOf(16)
        sendCommand(0x040D, address + byteArrayOf(pin.length.toByte()) + pinBytes)
    }

    private fun reportDevice(address: String, deviceClass: String, rssi: Int?, name: String?) {
        val label = buildString {
            append(name ?: text(R.string.dualsense_bridge_unknown_device))
            append(" — $address, class=$deviceClass")
            if (rssi != null) append(", RSSI=$rssi dBm")
        }
        onDevice(HciDevice(
            address, name ?: text(R.string.dualsense_bridge_unknown_device), deviceClass, rssi,
            paired = loadLinkKey(address) != null,
            state = text(R.string.dualsense_bridge_state_available)
        ))
        onLog("Inquiry Result → $label")
    }

    private fun publishDevice(
        device: InquiryDevice,
        state: String,
        paired: Boolean = loadLinkKey(device.address) != null
    ) {
        onDevice(HciDevice(
            device.address, device.name ?: text(R.string.dualsense_bridge_unknown_device), device.deviceClass,
            device.rssi, paired, state
        ))
    }

    private fun parseEirName(bytes: ByteArray, start: Int): String? {
        var pos = start
        while (pos < bytes.size) {
            val length = bytes[pos].u8()
            if (length == 0 || pos + length >= bytes.size) break
            val type = bytes[pos + 1].u8()
            if (type == 0x08 || type == 0x09) {
                return bytes.copyOfRange(pos + 2, pos + 1 + length)
                    .toString(Charsets.UTF_8).trimEnd('\u0000')
            }
            pos += length + 1
        }
        return null
    }

    protected open fun sendCommand(opcode: Int, parameters: ByteArray = byteArrayOf(),
                                   logPacket: Boolean = true) {
        val packet = ByteArray(3 + parameters.size)
        packet[0] = (opcode and 0xFF).toByte()
        packet[1] = (opcode ushr 8).toByte()
        packet[2] = parameters.size.toByte()
        parameters.copyInto(packet, 3)
        val sent = connection?.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or USB_RECIP_INTERFACE,
            0, 0, hciInterface?.id ?: 0, packet, packet.size, COMMAND_TIMEOUT_MS
        ) ?: -1
        check(sent == packet.size) { "HCI command write: $sent/${packet.size} byte" }
        if (logPacket) onLog("TX CMD 0x${opcode.hex4()} (${parameters.size} byte)")
    }

    @Synchronized
    private fun sendAcl(handle: Int, cid: Int, payload: ByteArray,
                        timeoutMs: Int = COMMAND_TIMEOUT_MS, logPacket: Boolean = true) {
        val l2cap = ByteArray(4 + payload.size)
        l2cap[0] = payload.size.toByte()
        l2cap[1] = (payload.size ushr 8).toByte()
        l2cap[2] = cid.toByte()
        l2cap[3] = (cid ushr 8).toByte()
        payload.copyInto(l2cap, 4)
        val fragmentLimit = outgoingAclFragmentLimit(aclDataPacketLength)
        var offset = 0
        var firstFragment = true
        while (offset < l2cap.size) {
            awaitAclCredit(timeoutMs)
            val fragmentLength = minOf(fragmentLimit, l2cap.size - offset)
            val packet = ByteArray(4 + fragmentLength)
            // PB=10 starts an automatically flushable L2CAP packet. PB=01 marks
            // each continuation fragment belonging to the same L2CAP packet.
            val handleAndFlags = handle or if (firstFragment) 0x2000 else 0x1000
            packet[0] = handleAndFlags.toByte()
            packet[1] = (handleAndFlags ushr 8).toByte()
            packet[2] = fragmentLength.toByte()
            packet[3] = (fragmentLength ushr 8).toByte()
            l2cap.copyInto(packet, 4, offset, offset + fragmentLength)
            val sent = connection?.bulkTransfer(aclOut, packet, packet.size, timeoutMs) ?: -1
            check(sent == packet.size) { "ACL OUT write: $sent/${packet.size}" }
            aclPacketsSent.incrementAndGet()
            offset += fragmentLength
            firstFragment = false
        }
        if (logPacket) {
            val fragmentCount = (l2cap.size + fragmentLimit - 1) / fragmentLimit
            onLog(
                "TX ACL handle=0x${handle.hex4()} cid=0x${cid.hex4()} " +
                    "len=${payload.size} fragments=$fragmentCount"
            )
        }
    }

    /** Profile seam: Generic Profile 1 trusts the HCI-reported ACL payload MTU. */
    protected open fun outgoingAclFragmentLimit(reportedBytes: Int): Int =
        reportedBytes.coerceAtLeast(MIN_ACL_DATA_PACKET_LENGTH)

    private fun handleL2capSignaling(handle: Int, payload: ByteArray) {
        var offset = 0
        while (offset + 4 <= payload.size) {
            val code = payload[offset].u8()
            val id = payload[offset + 1].u8()
            val length = payload.le16(offset + 2)
            if (offset + 4 + length > payload.size) break
            val data = payload.copyOfRange(offset + 4, offset + 4 + length)
            when (code) {
                0x02 -> if (data.size >= 4) {
                    val psm = data.le16(0)
                    val remoteCid = data.le16(2)
                    val localCid = nextLocalCid++
                    val channel = L2capChannel(psm, localCid, remoteCid, remoteInitiated = true)
                    channelsByLocalCid[localCid] = channel
                    if (psm == 0x0011 || psm == 0x0013) {
                        // The DualSense normally initiates its HID channels on a
                        // restored link. Claim that attempt immediately so the
                        // host-side fallback cannot open a duplicate channel.
                        hidOpenAttempted = true
                        hidOpenAttemptAtMs = System.currentTimeMillis()
                    }
                    if (psm == 0x0013) hidInterruptRemoteCid = remoteCid
                    onLog("L2CAP Connection Request → PSM=0x${psm.hex4()} SCID=0x${remoteCid.hex4()}")
                    sendAcl(handle, 0x0001, byteArrayOf(
                        0x03, id.toByte(), 0x08, 0x00,
                        localCid.toByte(), (localCid ushr 8).toByte(),
                        remoteCid.toByte(), (remoteCid ushr 8).toByte(),
                        0x00, 0x00, 0x00, 0x00
                    ))
                    // Keep the request identifier associated with this channel.
                    // A Configuration Response contains the peer-side CID, so a
                    // lookup in our local-CID map is not reliable for channels
                    // initiated by the controller.
                    sendConfigRequest(handle, remoteCid, channel)
                }
                0x03 -> if (data.size >= 8) {
                    val remoteCid = data.le16(0)
                    val localCid = data.le16(2)
                    val result = data.le16(4)
                    val status = data.le16(6)
                    val channel = pendingChannels[id]
                    onLog(
                        "L2CAP Connection Response ← DCID=0x${remoteCid.hex4()} " +
                            "SCID=0x${localCid.hex4()} result=0x${result.hex4()} " +
                            "status=0x${status.hex4()}"
                    )
                    when (result) {
                        0x0000 -> {
                            pendingChannels.remove(id)
                            if (channel != null) {
                                channel.remoteCid = remoteCid
                                channelsByLocalCid[channel.localCid] = channel
                                sendConfigRequest(handle, remoteCid, channel)
                            }
                        }
                        0x0001 -> {
                            // Pending: authentication/authorization is still running.
                            // The final response arrives later with the same identifier.
                            hidOpenAttemptAtMs = System.currentTimeMillis()
                            onStatus(
                                when (status) {
                                    0x0001 -> text(R.string.dualsense_bridge_status_hid_authentication)
                                    0x0002 -> text(R.string.dualsense_bridge_status_hid_authorization)
                                    else -> text(R.string.dualsense_bridge_status_hid_pending)
                                }
                            )
                        }
                        else -> {
                            pendingChannels.remove(id)
                            onStatus(text(R.string.dualsense_bridge_status_hid_rejected,
                                result.hex4()))
                        }
                    }
                }
                0x04 -> if (data.size >= 4) {
                    val destinationCid = data.le16(0)
                    onLog("L2CAP Configuration Request → DCID=0x${destinationCid.hex4()}")
                    sendAcl(handle, 0x0001, byteArrayOf(
                        0x05, id.toByte(), 0x06, 0x00,
                        destinationCid.toByte(), (destinationCid ushr 8).toByte(),
                        0x00, 0x00, 0x00, 0x00
                    ))
                    channelsByLocalCid[destinationCid]?.let { channel ->
                        channel.localConfigured = true
                        finishHidChannelIfReady(handle, channel)
                    }
                }
                0x05 -> if (data.size >= 6) {
                    val localCid = data.le16(0)
                    val result = data.le16(4)
                    val channel = pendingConfigs[id] ?: channelsByLocalCid[localCid]
                    onLog(
                        "L2CAP Configuration Response ← SCID=0x${localCid.hex4()} " +
                            "result=0x${result.hex4()}"
                    )
                    if (result == 0 && channel != null) {
                        pendingConfigs.remove(id)
                        channel.remoteConfigured = true
                        finishHidChannelIfReady(handle, channel)
                        /*
                        if (channel.psm == 0x0011) {
                            hidControlReady = true
                            hidOpenAttemptAtMs = System.currentTimeMillis()
                            onStatus(text(R.string.dualsense_bridge_status_hid_control_ready))
                            // On a controller-initiated reconnect, DualSense owns
                            // the HID channel sequence. Opening Interrupt from our
                            // side here races its own request and commonly leaves
                            // a valid ACL link with no input stream. Give it the
                            // normal remote-initiated path; the host-loop timeout
                            // remains the fallback if Interrupt never arrives.
                            if (pendingChannels.values.none { it.psm == 0x0013 } &&
                                channelsByLocalCid.values.none { it.psm == 0x0013 } &&
                                !channel.remoteInitiated
                            ) requestL2capChannel(handle, 0x0013)
                        } else if (channel.psm == 0x0013) {
                            hidInterruptRemoteCid = channel.remoteCid
                            hidChannelsReady = true
                            hidInterruptOpenRetries = 0
                            outputConnectionEpoch++
                            hidReadyAtMs = System.currentTimeMillis()
                            onStatus(text(R.string.dualsense_bridge_status_hid_waiting))
                            channelsByLocalCid.values.firstOrNull { it.psm == 0x0011 }
                                ?.remoteCid?.let { controlCid ->
                                    sendAcl(handle, controlCid, byteArrayOf(0x71))
                                    onLog("HIDP Set Protocol → Report mode")
                                }
                        }
                        */
                    } else if (result == 0x0004) {
                        // Pending configuration; keep the request until the final response.
                        hidOpenAttemptAtMs = System.currentTimeMillis()
                    } else {
                        pendingConfigs.remove(id)
                    }
                }
                0x06 -> if (data.size >= 4) {
                    val dcid = data.le16(0)
                    val scid = data.le16(2)
                    sendAcl(handle, 0x0001, byteArrayOf(
                        0x07, id.toByte(), 0x04, 0x00,
                        dcid.toByte(), (dcid ushr 8).toByte(),
                        scid.toByte(), (scid ushr 8).toByte()
                    ))
                }
                0x07 -> if (data.size >= 4) {
                    val dcid = data.le16(0)
                    val scid = data.le16(2)
                    onLog(
                        "L2CAP Disconnection Response ← DCID=0x${dcid.hex4()} " +
                            "SCID=0x${scid.hex4()}"
                    )
                }
                else -> onLog("L2CAP signaling code=0x${code.hex2()} id=$id len=$length")
            }
            offset += 4 + length
        }
    }

    private fun finishHidChannelIfReady(handle: Int, channel: L2capChannel) {
        if (!channel.localConfigured || !channel.remoteConfigured || channel.readyPublished) return
        channel.readyPublished = true
        if (channel.psm == 0x0011) {
            hidControlReady = true
            hidOpenAttemptAtMs = System.currentTimeMillis()
            onStatus(text(R.string.dualsense_bridge_status_hid_control_ready))
            // A controller-initiated reconnect owns the HID channel sequence.
            // Opening our own Interrupt channel here races the controller's
            // PSM 0x0013 request and produces two otherwise-valid channels.
            // The existing HID-stage timeout remains the fallback if the
            // controller never opens Interrupt.
            if (pendingChannels.values.none { it.psm == 0x0013 } &&
                channelsByLocalCid.values.none { it.psm == 0x0013 } &&
                !channel.remoteInitiated
            ) requestL2capChannel(handle, 0x0013)
        } else if (channel.psm == 0x0013) {
            hidInterruptRemoteCid = channel.remoteCid
            hidChannelsReady = true
            hidInterruptOpenRetries = 0
            outputConnectionEpoch++
            hidReadyAtMs = System.currentTimeMillis()
            onStatus(text(R.string.dualsense_bridge_status_hid_waiting))
            channelsByLocalCid.values.firstOrNull { it.psm == 0x0011 && it.readyPublished }
                ?.remoteCid?.let { controlCid ->
                    sendAcl(handle, controlCid, byteArrayOf(0x71))
                    onLog("HIDP Set Protocol -> Report mode (fully configured)")
                }
        }
    }

    private fun requestHidChannels(handle: Int) {
        if (hidChannelsReady || hasHidChannelProgress()) return
        hidOpenAttempted = true
        hidOpenAttemptAtMs = System.currentTimeMillis()
        requestL2capChannel(handle, 0x0011)
    }

    /** Rebuild HID without dropping the encrypted Bluetooth ACL connection. */
    private fun rebuildHidChannels(handle: Int) {
        onLog("Smart recovery → rebuilding HID channels on live ACL 0x${handle.hex4()}")
        val hidChannels = channelsByLocalCid.values
            .filter { it.psm == 0x0011 || it.psm == 0x0013 }
        hidChannels.forEach { channel ->
            channel.remoteCid?.let { remoteCid ->
                val id = nextSignalId++ and 0xFF
                runCatching {
                    sendAcl(handle, 0x0001, byteArrayOf(
                        0x06, id.toByte(), 0x04, 0x00,
                        remoteCid.toByte(), (remoteCid ushr 8).toByte(),
                        channel.localCid.toByte(), (channel.localCid ushr 8).toByte()
                    ))
                }.onFailure { onLog("HID channel close failed: ${it.message}") }
            }
        }

        pendingChannels.entries.removeIf { it.value.psm == 0x0011 || it.value.psm == 0x0013 }
        pendingConfigs.entries.removeIf { it.value.psm == 0x0011 || it.value.psm == 0x0013 }
        channelsByLocalCid.entries.removeIf { it.value.psm == 0x0011 || it.value.psm == 0x0013 }
        hidInterruptRemoteCid = null
        hidChannelsReady = false
        hidControlReady = false
        hidReadyAtMs = 0L
        lastHidInputMs = 0L
        hidOpenRetries = 0
        hidInterruptOpenRetries = 0
        // Keep the normal opener dormant until the controller has acknowledged
        // the old channel teardown, then negotiate a clean control/interrupt pair.
        hidOpenAttempted = true
        hidOpenAttemptAtMs = System.currentTimeMillis()
        // Start a fresh startup window. Without this, the original encryption
        // timestamp makes the recovery loop immediately time out again.
        encryptedAtMs = hidOpenAttemptAtMs
        hidChannelReopenAtMs = hidOpenAttemptAtMs + HID_CHANNEL_REOPEN_DELAY_MS
        hidInterruptReopenAtMs = 0L
        onStatus(text(R.string.dualsense_bridge_status_recovery_waiting))
    }

    protected fun hasHidChannelProgress(): Boolean =
        pendingChannels.values.any { it.psm == 0x0011 || it.psm == 0x0013 } ||
            pendingConfigs.values.any { it.psm == 0x0011 || it.psm == 0x0013 } ||
            channelsByLocalCid.values.any { it.psm == 0x0011 || it.psm == 0x0013 }

    /** Profile 1's established HID opening timeout. */
    protected open fun hidInterruptStageTimeoutMs(): Long = HID_INTERRUPT_STAGE_TIMEOUT_MS

    /**
     * Profile 1 treats an encrypted link without HID as failed after six
     * seconds.  Some adapter firmwares need a longer one-time authorization
     * window and override this without changing the generic/Baseus path.
     */
    protected open fun hidStartTimeoutMs(): Long = HID_START_TIMEOUT_MS

    /** HCI Accept Connection Request role: 0x01 keeps the controller master. */
    protected open fun incomingConnectionRole(): Byte = 0x01

    /** Optional profile-only observability for non-HID L2CAP services such as SDP. */
    protected open fun onNonHidL2capPayload(
        handle: Int,
        cid: Int,
        remoteCid: Int?,
        psm: Int?,
        payload: ByteArray
    ) = Unit

    /** Profile-only reply path for a negotiated non-HID L2CAP service. */
    protected fun sendNonHidL2capPayload(handle: Int, remoteCid: Int, payload: ByteArray) {
        sendAcl(handle, remoteCid, payload)
    }


    /** Profile 1 may probe an idle ACL link while no HID setup is in flight. */
    protected open fun allowIdleLinkCheck(): Boolean = true

    /** Profile 1 maintains its historical immediate startup discovery behavior. */
    protected open fun runStartupInquiry(): Boolean = true

    /** Read-only lifecycle seam for profiles with fragile Inquiry firmware. */
    protected fun isLiveDiscoveryInProgress(): Boolean = liveDiscoveryInProgress

    /** True after Search has been requested but before the host worker starts it. */
    protected fun isDiscoveryQueued(): Boolean = pendingDiscovery

    private fun requestL2capChannel(handle: Int, psm: Int) {
        hidOpenAttemptAtMs = System.currentTimeMillis()
        val localCid = nextLocalCid++
        val id = nextSignalId++ and 0xFF
        pendingChannels[id] = L2capChannel(psm, localCid, null, remoteInitiated = false)
        onLog("L2CAP Connection Request → PSM=0x${psm.hex4()} SCID=0x${localCid.hex4()}")
        sendAcl(handle, 0x0001, byteArrayOf(
            0x02, id.toByte(), 0x04, 0x00,
            psm.toByte(), (psm ushr 8).toByte(),
            localCid.toByte(), (localCid ushr 8).toByte()
        ))
    }

    private fun sendConfigRequest(handle: Int, remoteCid: Int, channel: L2capChannel) {
        hidOpenAttemptAtMs = System.currentTimeMillis()
        val id = nextSignalId++ and 0xFF
        pendingConfigs[id] = channel
        sendAcl(handle, 0x0001, byteArrayOf(
            0x04, id.toByte(), 0x04, 0x00,
            remoteCid.toByte(), (remoteCid ushr 8).toByte(),
            0x00, 0x00
        ))
    }

    private fun applyNextPostConnectTuning(handle: Int, now: Long) {
        val handleBytes = byteArrayOf(handle.toByte(), (handle ushr 8).toByte())
        when (postConnectTuningStage) {
            1 -> {
                // Disable Hold/Sniff/Park entry after HID is fully live. This
                // matches a desktop-style always-active game controller link.
                runCatching {
                    sendCommand(0x080D, handleBytes + byteArrayOf(0x00, 0x00))
                }.onSuccess {
                    onLog("Live HID link policy → active/low latency")
                }.onFailure {
                    onLog("Live link policy failed: ${it.message}")
                }
                postConnectTuningStage = 2
                postConnectTuningAtMs = now + POST_CONNECT_COMMAND_GAP_MS
            }
            2 -> {
                // 12.8 s supervision timeout tolerates short radio/USB stalls
                // without declaring the controller disconnected.
                runCatching {
                    sendCommand(0x0C37, handleBytes + byteArrayOf(0x00, 0x50))
                }.onSuccess {
                    onLog("Live link supervision timeout → 12.8 s")
                }.onFailure {
                    onLog("Live supervision setup failed: ${it.message}")
                }
                postConnectTuningStage = 0
                postConnectTuningAtMs = 0L
            }
        }
    }

    private fun handleModeChange(parameters: ByteArray) {
        if (parameters.size < 4 || parameters[0].u8() != 0) return
        val handle = parameters.le16(1) and 0x0FFF
        val mode = parameters[3].u8()
        if (handle != activeHandle) return
        currentLinkMode = mode
        if (mode == LINK_MODE_ACTIVE) {
            onLog("Live HID active mode confirmed")
            return
        }
        if (microphoneActiveModeRequested) {
            onLog("DualSense BT microphone owns link mode $mode; leaving controller schedule unchanged")
            return
        }
        if (lastHidInputMs == 0L) return
        requestActiveHidMode(handle, "Live HID")
    }

    private fun requestActiveHidMode(handle: Int, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastExitLowPowerModeAtMs < EXIT_LOW_POWER_RETRY_MS) return
        lastExitLowPowerModeAtMs = now
        runCatching {
            sendCommand(0x0804, byteArrayOf(handle.toByte(), (handle ushr 8).toByte()))
        }.onSuccess {
            onLog("$reason: low-power mode $currentLinkMode → requesting active mode")
        }.onFailure {
            onLog("Unable to restore active HID mode: ${it.message}")
        }
    }

    private fun retryInterruptChannel(handle: Int) {
        val staleInterruptChannels = channelsByLocalCid.values.filter { it.psm == 0x0013 }
        staleInterruptChannels.forEach { channel ->
            channel.remoteCid?.let { remoteCid ->
                val id = nextSignalId++ and 0xFF
                runCatching {
                    sendAcl(handle, 0x0001, byteArrayOf(
                        0x06, id.toByte(), 0x04, 0x00,
                        remoteCid.toByte(), (remoteCid ushr 8).toByte(),
                        channel.localCid.toByte(), (channel.localCid ushr 8).toByte()
                    ))
                }
            }
        }
        pendingChannels.entries.removeIf { it.value.psm == 0x0013 }
        pendingConfigs.entries.removeIf { it.value.psm == 0x0013 }
        channelsByLocalCid.entries.removeIf { it.value.psm == 0x0013 }
        hidInterruptRemoteCid = null
        hidOpenAttemptAtMs = System.currentTimeMillis()
        hidInterruptReopenAtMs = hidOpenAttemptAtMs + HID_CHANNEL_REOPEN_DELAY_MS
    }

    /** Profile seam: Profile 2 reassembles fixed-size fake-CSR event fragments. */
    protected open fun readEvent(timeoutMs: Int): HciEvent? {
        val buffer = ByteArray(eventReadBufferBytes())
        val size = connection?.bulkTransfer(eventIn, buffer, buffer.size, timeoutMs) ?: -1
        if (size <= 0) return null
        check(size >= 2) { "Túl rövid HCI event: $size byte" }
        val parameterLength = buffer[1].u8()
        check(size >= parameterLength + 2) { "Hiányos HCI event: $size/${parameterLength + 2}" }
        val event = HciEvent(buffer[0].u8(), buffer.copyOfRange(2, 2 + parameterLength))
        if (event.code == 0x13) recordCompletedAclPackets(event.parameters)
        // Number Of Completed Packets can arrive at output report frequency.
        // Processing it is critical, logging every instance would create its own
        // allocation/lock pressure and undermine the flow-control improvement.
        if (event.code != 0x13) {
            onLog("RX EVT 0x${event.code.hex2()} ($parameterLength byte)")
        }
        return event
    }

    /** Generic HCI accepts a maximum-size event read; adapter profiles may not. */
    protected open fun eventReadBufferBytes(): Int = 260

    /**
     * Accounts for HCI Number Of Completed Packets events. Profile 2 owns its
     * fragmented event reader, so it must invoke the same accounting itself.
     */
    protected fun recordCompletedAclPackets(parameters: ByteArray) {
        if (parameters.isEmpty()) return
        var offset = 1
        var completed = 0L
        repeat(parameters[0].u8()) {
            if (offset + 4 > parameters.size) return@repeat
            completed += parameters.le16(offset + 2).toLong()
            offset += 4
        }
        if (completed != 0L) aclPacketsCompleted.addAndGet(completed)
    }

    private fun awaitAclCredit(timeoutMs: Int) {
        val capacity = aclPacketCapacity
        // The event worker must never wait for an event that only it can read.
        if (capacity <= 0 || Thread.currentThread() === worker) return
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (running.get() &&
            aclPacketsSent.get() - aclPacketsCompleted.get() >= capacity &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            LockSupport.parkNanos(ACL_CREDIT_POLL_NS)
        }
    }

    override fun close() {
        running.set(false)
        connection?.close()
        worker?.interrupt()
        aclWorker?.interrupt()
        inputWorker?.interrupt()
        outputWorker?.interrupt()
        audioWorker?.interrupt()
        inputQueue.clear()
        outputSignal.clear()
        audioQueue.clear()
        nativeBluetoothHapticsRequested = false
        nativeBluetoothMicrophoneRequested = false
        nativeBluetoothHeadsetRoute = false
        nativeAudioWakeEpoch = -1L
        latestOutputConfig = null
    }

    fun getDiagnostics(): LinkDiagnostics = LinkDiagnostics(
        lastUsbAclAtMs, lastHidInputElapsedMs, linkQualityPercent,
        lastHidGapMs, lastInputDispatchMs, lastInputDispatchDurationMs,
        droppedInputPackets, lastOutputStallMs, lastIncidentAtMs,
        lastIncidentType, lastIncidentDurationMs
    )

    private fun recordIncident(type: String, durationMs: Long) {
        lastIncidentType = type
        lastIncidentDurationMs = durationMs
        lastIncidentAtMs = SystemClock.elapsedRealtime()
    }

    private fun release() {
        val conn = connection
        val iface = hciInterface
        if (conn != null && iface != null) runCatching { conn.releaseInterface(iface) }
        runCatching { conn?.close() }
        connection = null
    }

    protected data class HciEvent(val code: Int, val parameters: ByteArray)
    private data class NativeAudioPayload(
        val haptics: ByteArray,
        val speakerOpus: ByteArray?,
        val configurationOnly: Boolean = false,
        val speakerOnly: Boolean = false,
        val microphoneArm: Boolean = false
    )
    private data class L2capChannel(
        val psm: Int,
        val localCid: Int,
        var remoteCid: Int?,
        val remoteInitiated: Boolean,
        var localConfigured: Boolean = false,
        var remoteConfigured: Boolean = false,
        var readyPublished: Boolean = false
    )
    private data class InquiryDevice(
        val address: String,
        val addressLittleEndian: ByteArray,
        val pageScanRepetitionMode: Byte,
        val clockOffsetLow: Byte,
        val clockOffsetHigh: Byte,
        val deviceClass: String,
        val rssi: Int?,
        val name: String?
    )

    data class HciDevice(
        val address: String,
        val name: String,
        val deviceClass: String,
        val rssi: Int?,
        val paired: Boolean,
        val state: String
    )

    data class AclPacket(
        val handle: Int,
        val packetBoundary: Int,
        val cid: Int?,
        val l2capLength: Int?,
        val payload: ByteArray,
        val rawHex: String
    )

    data class LinkDiagnostics(
        val lastUsbAclAtMs: Long,
        val lastHidInputAtMs: Long,
        val linkQualityPercent: Int,
        val lastHidGapMs: Long,
        val lastInputDispatchAtMs: Long,
        val lastInputDispatchDurationMs: Long,
        val droppedInputPackets: Long,
        val lastOutputStallMs: Long,
        val lastIncidentAtMs: Long,
        val lastIncidentType: String,
        val lastIncidentDurationMs: Long
    )

    companion object {
        private const val COMMAND_TIMEOUT_MS = 5_000
        private const val OUTPUT_WRITE_TIMEOUT_MS = 80
        private const val AUDIO_WRITE_TIMEOUT_MS = 100
        private const val AUDIO_HID_PRIORITY_AGE_MS = 40L
        private const val OUTPUT_MIN_INTERVAL_MS = 8L
        private const val OUTPUT_STALL_LOG_MS = 40L
        private const val OUTPUT_ERROR_LOG_INTERVAL_MS = 2_000L
        private const val ACL_READ_TIMEOUT_MS = 250
        // Keep several receives submitted to Android's USB host stack at all
        // times. A DualSense microphone emits a 10 ms packet cadence, so a
        // synchronous receive/re-submit gap can otherwise become packet loss.
        private const val ACL_USB_REQUEST_COUNT = 4
        private const val ACL_USB_REQUEST_BYTES = 1024
        private const val INQUIRY_TIMEOUT_MS = 15_000
        private const val REMOTE_NAME_TIMEOUT_MS = 8_000
        private const val CONNECTION_TIMEOUT_MS = 45_000
        private const val LINK_CHECK_INTERVAL_MS = 3_000L
        private const val POST_CONNECT_COMMAND_GAP_MS = 500L
        private const val EXIT_LOW_POWER_RETRY_MS = 2_000L
        private const val LINK_MODE_UNKNOWN = -1
        private const val LINK_MODE_ACTIVE = 0
        private const val HID_OPEN_DELAY_MS = 750L
        private const val HID_CHANNEL_REOPEN_DELAY_MS = 350L
        // BlueZ does not tear down a valid HID channel pair merely because the
        // controller has not emitted its first report yet. Allow slow controller
        // wake-up and cheap dongle firmware substantially more time.
        private const val HID_START_TIMEOUT_MS = 6_000L
        private const val HID_RETRY_INTERVAL_MS = 3_000L
        private const val HID_INTERRUPT_STAGE_TIMEOUT_MS = 3_000L
        private const val HID_MAX_RETRIES = 3
        private const val HID_INITIAL_RECOVERY_CYCLES = 2
        private const val MAX_ACL_PAYLOAD = 4096
        private const val MAX_USB_CARRY_BYTES = 8192
        private const val ACL_LOG_INTERVAL_MS = 1_000L
        private const val DEVICE_PUBLISH_INTERVAL_MS = 500L
        private const val HID_GAP_LOG_MS = 250L
        private const val INPUT_DISPATCH_STALL_LOG_MS = 100L
        private const val INPUT_QUEUE_CAPACITY = 32
        private const val AUDIO_QUEUE_CAPACITY = 4
        private const val AUDIO_REPORT_INTERVAL_NS = 10_666_667L
        private const val AUDIO_CLOCK_RESET_NS = 100_000_000L
        private const val HOST_ACL_BUFFER_BYTES = 679
        private const val HOST_ACL_BUFFER_PACKETS = 32
        private const val HOST_ACL_COMPLETION_BATCH = 4
        private const val MICROPHONE_ARM_POLL_MS = 100L
        private const val MICROPHONE_ARM_INTERVAL_MS = 250L
        private const val MICROPHONE_STALE_MS = 1_000L
        private const val MICROPHONE_RATE_WINDOW_MS = 1_500L
        // 80 frames/s gives startup jitter room but detects the observed
        // 75%-rate duplex failure before an audible backlog accumulates.
        private const val MICROPHONE_MIN_FRAMES_PER_WINDOW = 120L
        private const val MICROPHONE_RATE_RECOVERY_INTERVAL_MS = 1_500L
        private const val ACL_CREDIT_POLL_NS = 500_000L
        private const val DEFAULT_ACL_DATA_PACKET_LENGTH = 1021
        private const val MIN_ACL_DATA_PACKET_LENGTH = 27
        private const val USB_RECIP_INTERFACE = 0x01
        const val INCIDENT_NONE = "none"
        const val INCIDENT_RADIO_USB_GAP = "radio_usb_gap"
        const val INCIDENT_APP_STALL = "app_stall"
        const val INCIDENT_QUEUE_OVERRUN = "queue_overrun"
        const val INCIDENT_OUTPUT_STALL = "output_stall"
        const val INCIDENT_OUTPUT_ERROR = "output_error"

        fun looksLikeBluetoothHci(device: UsbDevice): Boolean =
            (0 until device.interfaceCount).map(device::getInterface).any(::isBluetoothInterface)

        fun hasHciEndpointLayout(device: UsbDevice): Boolean =
            (0 until device.interfaceCount).map(device::getInterface).any(::hasHciEndpointLayout)

        private fun isBluetoothInterface(iface: UsbInterface): Boolean =
            iface.interfaceClass == 0xE0 &&
                iface.interfaceSubclass == 0x01 &&
                iface.interfaceProtocol == 0x01

        private fun hasHciEndpointLayout(iface: UsbInterface): Boolean {
            val endpoints = (0 until iface.endpointCount).map(iface::getEndpoint)
            val eventIn = endpoints.any {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
            val aclIn = endpoints.any {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            }
            val aclOut = endpoints.any {
                it.direction == UsbConstants.USB_DIR_OUT &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            }
            return eventIn && aclIn && aclOut
        }

        private fun Byte.u8() = toInt() and 0xFF
        private fun ByteArray.le16(offset: Int) = this[offset].u8() or (this[offset + 1].u8() shl 8)
        private fun ByteArray.toHex() = joinToString("") { "%02X".format(it.u8()) }
        private fun String.hexToBytes() =
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private fun Int.hex2() = "%02X".format(this)
        private fun Int.hex4() = "%04X".format(this)

        private fun formatAddress(bytes: ByteArray, offset: Int): String =
            (5 downTo 0).joinToString(":") { bytes[offset + it].u8().hex2() }

        private fun addressToLittleEndian(address: String): ByteArray =
            address.split(":").map { it.toInt(16).toByte() }.reversed().toByteArray()
    }
}
