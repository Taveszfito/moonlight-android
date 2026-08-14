package com.example.usbbtonandroid.hci

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.os.Process
import com.limelight.R
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

class HciUsbController(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val stringProvider: (Int, Array<out Any>) -> String,
    private val onLog: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onDevice: (HciDevice) -> Unit,
    private val onAclPacket: (AclPacket) -> Unit,
    private val loadLinkKey: (String) -> String?,
    private val saveLinkKey: (String, String) -> Unit
) : Closeable {
    private fun text(id: Int, vararg args: Any): String = stringProvider(id, args)
    private val running = AtomicBoolean(false)
    private var connection: UsbDeviceConnection? = null
    private var hciInterface: UsbInterface? = null
    private var eventIn: UsbEndpoint? = null
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
    @Volatile private var authorizedPairingAddress: String? = null
    @Volatile private var pendingDiscovery = false
    @Volatile private var pendingLinkRecovery = false
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
    private var nativeAudioWakeEpoch = -1L
    @Volatile private var lastOutputErrorLogMs = 0L
    private var droppedInputPackets = 0L
    @Volatile private var lastActiveDevicePublishMs = 0L
    @Volatile private var lastAclLogMs = 0L
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
    // Read from the adapter during startup. Native DualSense audio reports are
    // larger than the ACL buffer exposed by a number of inexpensive dongles.
    @Volatile private var aclDataPacketLength = DEFAULT_ACL_DATA_PACKET_LENGTH
    @Volatile private var aclPacketCapacity = 0
    private val aclPacketsSent = AtomicLong(0)
    private val aclPacketsCompleted = AtomicLong(0)
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

            // Always use the proven standalone startup path. Skipping inquiry on
            // recovery leaves cheap HCI dongles with stale page/clock state and
            // produces an ACL link that often never becomes a usable HID session.
            onStatus(text(R.string.dualsense_bridge_status_classic_scan))
            sendCommand(0x0401, byteArrayOf(0x33, 0x8B.toByte(), 0x9E.toByte(), 0x08, 0x00))
            val discovered = resolveRemoteNames(scanUntilComplete())
            discoveredDevices.clear()
            discovered.forEach { discoveredDevices[it.address] = it }
            enableIncomingConnections()
            onStatus(text(R.string.dualsense_bridge_status_scan_finished))
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
        if (handle != null && current?.address == address) {
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
        onStatus(text(R.string.dualsense_bridge_status_preparing_connection))
    }

    @Volatile private var pendingDisconnectAddress: String? = null

    fun disconnect(address: String) {
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

    fun requestDiscovery() {
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
    fun sendNativeBluetoothHaptics(haptics: ByteArray, speakerOpus: ByteArray?): Boolean {
        if (haptics.size != com.example.usbbtonandroid.DualSenseBtAudioBuilder.HAPTICS_BYTES_PER_REPORT ||
            (speakerOpus != null && speakerOpus.size != 200) ||
            activeHandle == null || hidInterruptRemoteCid == null || lastHidInputMs == 0L) {
            return false
        }
        nativeBluetoothHapticsRequested = true
        val payload = NativeAudioPayload(haptics.copyOf(), speakerOpus?.copyOf())
        if (audioQueue.offer(payload)) return true
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

    private fun enableIncomingConnections() {
        requireCommandComplete(0x0C1A, byteArrayOf(0x02))
        onLog("Page Scan enabled → párosított eszközök visszakapcsolódhatnak")
    }

    private fun hostLoop() {
        var lastLinkCheck = 0L
        while (running.get()) {
            if (pendingDiscovery) {
                pendingDiscovery = false
                if (activeHandle == null) {
                    runCatching { performLiveDiscovery() }.onFailure {
                        onLog("Live inquiry failed: ${it.message ?: it.javaClass.simpleName}")
                        onStatus(text(R.string.dualsense_bridge_status_probe_failed))
                        runCatching { enableIncomingConnections() }
                    }
                } else {
                    onStatus(text(R.string.dualsense_bridge_status_already_connected))
                }
            }
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
            pendingConnection?.let { target ->
                pendingConnection = null
                runCatching { connectAndPair(target) }.onFailure {
                    onLog("HIBA: ${it.message ?: it.javaClass.simpleName}")
                    onStatus(text(R.string.dualsense_bridge_status_connection_failed))
                    publishDevice(target, "Sikertelen")
                }
            }
            val now = System.currentTimeMillis()
            val active = activeHandle
            if (active != null && postConnectTuningStage != 0 &&
                now >= postConnectTuningAtMs
            ) {
                applyNextPostConnectTuning(active, now)
            }
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
                now - hidOpenAttemptAtMs >= HID_INTERRUPT_STAGE_TIMEOUT_MS &&
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
                if (lastHidInputMs == 0L && now - lastLinkCheck >= LINK_CHECK_INTERVAL_MS) {
                    sendCommand(0x1405, byteArrayOf(handle.toByte(), (handle ushr 8).toByte()))
                    lastLinkCheck = now
                }
            }
            if (active != null && hidOpenAttempted && !hidChannelsReady &&
                lastHidInputMs == 0L &&
                now - encryptedAtMs >= HID_START_TIMEOUT_MS
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
                0x04 -> if (event.parameters.size >= 10) {
                    val addressBytes = event.parameters.copyOfRange(0, 6)
                    val address = formatAddress(addressBytes, 0)
                    val known = discoveredDevices[address] ?: InquiryDevice(
                        address, addressBytes, 0x01, 0, 0,
                        "%02X%02X%02X".format(
                            event.parameters[8].u8(), event.parameters[7].u8(), event.parameters[6].u8()
                        ),
                        null, text(R.string.dualsense_bridge_paired_name)
                    )
                    onLog("Incoming Connection Request ← $address")
                    val paired = loadLinkKey(address) != null
                    val explicitlyAuthorized = authorizedPairingAddress == address
                    if (paired || explicitlyAuthorized) {
                        connectAndPair(known, incomingAddress = addressBytes)
                    } else {
                        onLog("Incoming connection rejected: $address (not paired/authorized)")
                        // HCI Reject Connection Request: unacceptable address.
                        sendCommand(0x040A, addressBytes + byteArrayOf(0x0F))
                    }
                }
                0x05 -> handleDisconnection(event.parameters)
                0x14 -> handleModeChange(event.parameters)
                0x0E -> handleLinkCheckResult(event.parameters)
            }
        }
    }

    private fun performLiveDiscovery() {
        onStatus(text(R.string.dualsense_bridge_status_classic_scan))
        onLog("Live HCI Inquiry → clearing stale discovery cache")
        discoveredDevices.clear()
        sendCommand(0x0401, byteArrayOf(
            0x33, 0x8B.toByte(), 0x9E.toByte(), 0x08, 0x00
        ))
        val discovered = resolveRemoteNames(scanUntilComplete())
        discovered.forEach { discoveredDevices[it.address] = it }
        enableIncomingConnections()
        onStatus(text(R.string.dualsense_bridge_status_scan_finished))
        onLog("Live HCI Inquiry complete → ${discovered.size} device(s)")
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
        val iface = interfaces.firstOrNull(::isBluetoothInterface)
            ?: interfaces.firstOrNull(::hasHciEndpointLayout)
            ?: error("Nincs használható Bluetooth HCI interfész")
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
        val conn = usbManager.openDevice(device) ?: error("Az USB eszköz nem nyitható meg")
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
                if (config == lastSentConfig && epoch == lastSentEpoch &&
                    nativeHaptics == lastSentNativeHaptics) continue
                val handle = activeHandle ?: continue
                val cid = hidInterruptRemoteCid ?: continue
                val report = com.example.usbbtonandroid.DualSenseBtOutputBuilder.build(
                    config, nextOutputSequence(), nativeHaptics
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
                var audio = audioQueue.take()
                // Moonlight packets may arrive in short bursts. Feeding that burst
                // directly into the Bluetooth radio makes the controller speaker
                // alternate between buffer overrun and underrun. Keep only the most
                // recent waveform when we are behind and transmit on the controller's
                // native 64-sample/3 kHz report clock.
                while (audioQueue.size > 1) {
                    audio = audioQueue.poll() ?: audio
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
                if (lastHidInputElapsedMs != 0L && hidAgeMs >= AUDIO_HID_PRIORITY_AGE_MS) {
                    nativeAudioReportsSkippedForInput++
                    if (nativeAudioReportsSkippedForInput == 1L ||
                        nativeAudioReportsSkippedForInput % 100L == 0L) {
                        onLog("DualSense native Bluetooth audio yielded to HID input: " +
                            "$nativeAudioReportsSkippedForInput reports, HID age ${hidAgeMs}ms")
                    }
                    continue
                }
                val epoch = outputConnectionEpoch
                if (nativeAudioWakeEpoch != epoch) {
                    val wake = com.example.usbbtonandroid.DualSenseBtAudioBuilder.buildWake(
                        nextOutputSequence()
                    )
                    sendAcl(handle, cid, byteArrayOf(0xa2.toByte()) + wake,
                        AUDIO_WRITE_TIMEOUT_MS, false)
                    nativeAudioWakeEpoch = epoch
                    outputSignal.offer(Unit)
                    onLog("DualSense native Bluetooth audio/haptics path enabled")
                }
                audioPacketCounter = (audioPacketCounter + 1) and 0xff
                val report = com.example.usbbtonandroid.DualSenseBtAudioBuilder.build(
                    audio.haptics, nextOutputSequence(), audioPacketCounter, audio.speakerOpus
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

    private fun aclReadLoop() {
        // Reuse one carry buffer instead of copying the complete pending stream on
        // every report. This keeps GC away from the real-time input edge.
        val pending = ByteArray(MAX_USB_CARRY_BYTES)
        var pendingSize = 0
        while (running.get()) {
            if (pendingSize == pending.size) {
                onLog("ACL carry buffer full ($pendingSize); reset")
                pendingSize = 0
            }
            val size = connection?.bulkTransfer(
                aclIn, pending, pendingSize, pending.size - pendingSize, ACL_READ_TIMEOUT_MS
            ) ?: -1
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
                val cid = if (isStart && aclPayload.size >= 4) aclPayload.le16(2) else null
                val l2capLength = if (isStart && aclPayload.size >= 4) aclPayload.le16(0) else null
                val payload = if (cid != null) aclPayload.copyOfRange(4, aclPayload.size) else aclPayload
                val packet = AclPacket(
                    handle = handle,
                    packetBoundary = packetBoundary,
                    cid = cid,
                    l2capLength = l2capLength,
                    payload = payload,
                    // Full hex conversion on every high-rate gyro report created
                    // avoidable garbage collection pressure.
                    rawHex = ""
                )
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
                if (isHidInput) enqueueInput(packet)
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
    }

    private fun disconnectReason(reason: Int) = when (reason) {
        0x08 -> "kapcsolati időtúllépés"
        0x13 -> "a kontroller bontotta"
        0x14 -> "a helyi host bontotta"
        0x16 -> "a helyi host kikapcsolta"
        0x22 -> "LMP válaszidő-túllépés"
        else -> "Bluetooth bontás"
    }

    private fun requireCommandComplete(opcode: Int, parameters: ByteArray = byteArrayOf()): ByteArray {
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
        }
        error("HCI 0x${opcode.hex4()} időtúllépés")
    }

    private fun scanUntilComplete(): List<InquiryDevice> {
        val discovered = linkedMapOf<String, InquiryDevice>()
        val deadline = System.currentTimeMillis() + INQUIRY_TIMEOUT_MS
        while (running.get() && System.currentTimeMillis() < deadline) {
            val event = readEvent(1000) ?: continue
            when (event.code) {
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

    private fun resolveRemoteNames(devices: List<InquiryDevice>): List<InquiryDevice> {
        val resolved = devices.toMutableList()
        devices.filter { it.name.isNullOrBlank() }.forEachIndexed { index, device ->
            if (!running.get()) return resolved
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
                val event = readEvent(1000) ?: continue
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

    private fun connectAndPair(device: InquiryDevice, incomingAddress: ByteArray? = null) {
        onStatus(text(R.string.dualsense_bridge_status_connecting_device, device.name ?: "DualSense"))
        publishDevice(device, text(R.string.dualsense_bridge_state_connecting))
        if (incomingAddress != null) {
            onLog("Accept Connection Request → ${device.address}")
            sendCommand(0x0409, incomingAddress + byteArrayOf(0x00))
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
            val event = readEvent(1000) ?: continue
            when (event.code) {
                0x03 -> {
                    if (event.parameters.size < 11) continue
                    val status = event.parameters[0].u8()
                    if (status != 0) error("Connection Complete status=0x${status.hex2()}")
                    connectionHandle = event.parameters.le16(1) and 0x0FFF
                    activeHandle = connectionHandle
                    activeDevice = device
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

    private fun sendCommand(opcode: Int, parameters: ByteArray = byteArrayOf()) {
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
        onLog("TX CMD 0x${opcode.hex4()} (${parameters.size} byte)")
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
        val fragmentLimit = aclDataPacketLength.coerceAtLeast(MIN_ACL_DATA_PACKET_LENGTH)
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
            if (pendingChannels.values.none { it.psm == 0x0013 } &&
                channelsByLocalCid.values.none { it.psm == 0x0013 }
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

    private fun hasHidChannelProgress(): Boolean =
        pendingChannels.values.any { it.psm == 0x0011 || it.psm == 0x0013 } ||
            pendingConfigs.values.any { it.psm == 0x0011 || it.psm == 0x0013 } ||
            channelsByLocalCid.values.any { it.psm == 0x0011 || it.psm == 0x0013 }

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
        if (handle != activeHandle || mode == 0 || lastHidInputMs == 0L) return
        val now = System.currentTimeMillis()
        if (now - lastExitLowPowerModeAtMs < EXIT_LOW_POWER_RETRY_MS) return
        lastExitLowPowerModeAtMs = now
        runCatching {
            sendCommand(0x0804, byteArrayOf(handle.toByte(), (handle ushr 8).toByte()))
        }.onSuccess {
            onLog("Live HID entered low-power mode $mode → requesting active mode")
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

    private fun readEvent(timeoutMs: Int): HciEvent? {
        val buffer = ByteArray(260)
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

    private fun recordCompletedAclPackets(parameters: ByteArray) {
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

    private data class HciEvent(val code: Int, val parameters: ByteArray)
    private data class NativeAudioPayload(
        val haptics: ByteArray,
        val speakerOpus: ByteArray?
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
        private const val INQUIRY_TIMEOUT_MS = 15_000
        private const val REMOTE_NAME_TIMEOUT_MS = 8_000
        private const val CONNECTION_TIMEOUT_MS = 45_000
        private const val LINK_CHECK_INTERVAL_MS = 3_000L
        private const val POST_CONNECT_COMMAND_GAP_MS = 500L
        private const val EXIT_LOW_POWER_RETRY_MS = 2_000L
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
