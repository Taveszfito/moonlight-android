package com.example.usbbtonandroid.hci

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeoutException

/**
 * Profile 2 for the widespread CSR-compatible USB adapters that identify as
 * 0A12:0001.  It deliberately owns only CSR command transport behaviour; all
 * proven Generic HCI radio, HID, audio, and recovery logic stays in Profile 1.
 * All CSR-specific compatibility work must remain isolated in this class:
 * Profile 1 must not be changed for CSR support.
 *
 * Some CSR clones reject the USB class/interface recipient used by the generic
 * HCI profile before they answer their first HCI Reset.  Their documented HCI
 * packets are unchanged, so this profile tries the device-recipient fallback
 * only after the standard request has failed.  It never falls back to profile
 * 1 and never writes arbitrary vendor commands.
 */
class CsrHciUsbController(
    usbManager: UsbManager,
    device: UsbDevice,
    stringProvider: (Int, Array<out Any>) -> String,
    onLog: (String) -> Unit,
    onStatus: (String) -> Unit,
    onDevice: (HciDevice) -> Unit,
    onAclPacket: (AclPacket) -> Unit,
    onDualSenseMicrophoneFrame: (ByteArray, Int) -> Unit,
    loadLinkKey: (String) -> String?,
    saveLinkKey: (String, String) -> Unit
) : HciUsbController(
    usbManager, device, stringProvider, onLog, onStatus, onDevice, onAclPacket,
    onDualSenseMicrophoneFrame, loadLinkKey, saveLinkKey
) {
    @Volatile private var lastDiscoveryRequestAtMs = 0L
    private val deferredDiscoveryRequested = AtomicBoolean(false)
    private val deferredDiscoveryWorkerRunning = AtomicBoolean(false)
    private var pendingL2capHandle = -1
    private var pendingL2capCid = -1
    private var pendingL2capExpectedBytes = 0
    private var pendingL2capPayload = ByteArray(0)

    /**
     * A subset of 0A12:0001 clones drops the first HCI Reset after their USB
     * interface is claimed. Linux applies a brief CSR-specific settle cycle
     * for the same family. Retrying only Reset is safe and avoids changing the
     * proven Profile 1 command sequence.
     */
    override fun requireCommandComplete(opcode: Int, parameters: ByteArray): ByteArray {
        if (opcode != HCI_RESET) return super.requireCommandComplete(opcode, parameters)

        return try {
            super.requireCommandComplete(opcode, parameters)
        } catch (first: TimeoutException) {
            onLog("CSR Profile 2: initial HCI Reset timed out; settling and retrying once")
            SystemClock.sleep(CSR_RESET_SETTLE_MS)
            super.requireCommandComplete(opcode, parameters)
        }
    }

    // Linux btusb applies this exact workaround to 0A12:0001: the common
    // counterfeit CSR family does not complete short interrupt transfers.
    // Android's bulkTransfer() uses the supplied buffer size as its request
    // length, so use the endpoint's actual packet size rather than 260 bytes.
    override fun eventReadBufferBytes(): Int = eventIn?.maxPacketSize?.coerceAtLeast(2) ?: 64

    /**
     * The fake-CSR interrupt endpoint uses fixed max-packet transfers. Large
     * HCI events (notably Extended Inquiry Result) therefore arrive as several
     * 16-byte fragments instead of one short transfer. Reassemble only in
     * Profile 2; Profile 1 keeps its established event reader untouched.
     */
    override fun readEvent(timeoutMs: Int): HciEvent? {
        val conn = connection ?: return null
        val endpoint = eventIn ?: return null
        val packetBytes = eventReadBufferBytes()
        val first = ByteArray(packetBytes)
        val firstSize = conn.bulkTransfer(endpoint, first, first.size, timeoutMs)
        if (firstSize <= 0) return null
        check(firstSize >= 2) { "CSR Profile 2 received a short HCI event header: $firstSize byte" }

        val eventCode = first[0].toInt() and 0xFF
        val parameterBytes = first[1].toInt() and 0xFF
        val totalBytes = parameterBytes + 2
        val event = ByteArray(totalBytes)
        first.copyInto(event, endIndex = minOf(firstSize, totalBytes))
        var received = minOf(firstSize, totalBytes)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs

        while (received < totalBytes) {
            val remainingTimeout = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val fragment = ByteArray(packetBytes)
            val fragmentSize = conn.bulkTransfer(endpoint, fragment, fragment.size, remainingTimeout)
            check(fragmentSize > 0) {
                "CSR Profile 2 incomplete HCI event: $received/$totalBytes byte"
            }
            val copied = minOf(fragmentSize, totalBytes - received)
            fragment.copyInto(event, destinationOffset = received, endIndex = copied)
            received += copied
        }

        val parameters = event.copyOfRange(2, totalBytes)
        // Unlike Profile 1, this reader has to reassemble fixed-size USB event
        // fragments itself. Keep the normal host-to-controller ACL credits in
        // sync here too; otherwise the reported eight-packet ACL pool appears
        // permanently full after the first audio report.
        if (eventCode == HCI_NUMBER_OF_COMPLETED_PACKETS) {
            recordCompletedAclPackets(parameters)
        }
        return HciEvent(eventCode, parameters)
    }

    /**
     * The inexpensive CSR-compatible firmware reports a modern HCI version,
     * but a number of samples never complete Write Scan Enable (0x0C1A).
     * Inquiry itself does not require it, so keep discovery alive and give up
     * only passive controller-initiated reconnects for this profile.
     */
    override fun enableIncomingConnections() {
        runCatching { super.enableIncomingConnections() }
            .onFailure {
                onLog(
                    "CSR Profile 2: Write Scan Enable unavailable; continuing with active discovery " +
                        "(${it.message ?: it.javaClass.simpleName})"
                )
            }
    }

    /**
     * Fake CSR controllers commonly advertise BR/EDR flow-control commands
     * they do not implement. Sending Host Buffer Size / Set Controller-to-Host
     * Flow Control can leave the radio unable to complete the next Inquiry.
     * Profile 2 therefore uses the HCI default delivery mode.
     */
    override fun configureControllerToHostAclFlowControl() {
        onLog("CSR Profile 2: skipping unsupported controller→host ACL flow control")
    }

    // CSR event/ACL fragments make a legitimate HID Interrupt configuration
    // slower than Profile 1. Do not create a duplicate channel while the first
    // one is still being authorized/configured.
    override fun hidInterruptStageTimeoutMs(): Long = CSR_HID_INTERRUPT_TIMEOUT_MS

    // Link checks are USB control traffic. On this clone they can delay the
    // final L2CAP fragments, so only issue one once no HID setup is in flight.
    override fun allowIdleLinkCheck(): Boolean = !hasHidChannelProgress()

    // The clone may physically re-enumerate after repeated Inquiry cancel/start
    // cycles. Stay pageable at startup; explicit Search starts one bounded scan.
    override fun runStartupInquiry(): Boolean = false

    override fun requestDiscovery() {
        val now = SystemClock.elapsedRealtime()
        if (isLiveDiscoveryInProgress() || isDiscoveryQueued() ||
            now - lastDiscoveryRequestAtMs < CSR_DISCOVERY_RESTART_GUARD_MS
        ) {
            // Do not cancel/restart the clone's current Inquiry.  This is a
            // queued Search, not an ignored one: one clean follow-up pass is
            // scheduled after the current radio operation has settled.
            deferredDiscoveryRequested.set(true)
            onLog("CSR Profile 2: Search queued until the active Inquiry settles")
            scheduleDeferredDiscovery()
            return
        }
        lastDiscoveryRequestAtMs = now
        super.requestDiscovery()
    }

    private fun scheduleDeferredDiscovery() {
        if (!deferredDiscoveryWorkerRunning.compareAndSet(false, true)) return
        Thread({
            try {
                while (deferredDiscoveryRequested.get()) {
                    SystemClock.sleep(CSR_DISCOVERY_RETRY_POLL_MS)
                    val now = SystemClock.elapsedRealtime()
                    if (isLiveDiscoveryInProgress() || isDiscoveryQueued() ||
                        now - lastDiscoveryRequestAtMs < CSR_DISCOVERY_RESTART_GUARD_MS
                    ) {
                        continue
                    }
                    if (deferredDiscoveryRequested.compareAndSet(true, false)) {
                        lastDiscoveryRequestAtMs = now
                        onLog("CSR Profile 2: running queued Search after radio settle")
                        super.requestDiscovery()
                    }
                }
            } finally {
                deferredDiscoveryWorkerRunning.set(false)
                if (deferredDiscoveryRequested.get()) scheduleDeferredDiscovery()
            }
        }, "csr-hci-deferred-search").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * This adapter limits incoming ACL payloads to small pieces. A DualSense
     * report commonly arrives as a start fragment plus one or more continuations.
     * Never forward the start fragment by itself: its missing tail otherwise
     * decodes as random buttons, sticks and motion values.
     */
    override fun normalizeIncomingAclPacket(packet: AclPacket): AclPacket? {
        val isStart = packet.packetBoundary == ACL_START_NON_FLUSHABLE ||
            packet.packetBoundary == ACL_START_FLUSHABLE
        if (isStart && packet.cid != null && packet.l2capLength != null) {
            clearPendingL2cap()
            if (packet.payload.size >= packet.l2capLength) return packet
            pendingL2capHandle = packet.handle
            pendingL2capCid = packet.cid
            pendingL2capExpectedBytes = packet.l2capLength
            pendingL2capPayload = packet.payload.copyOf()
            return null
        }

        if (packet.packetBoundary != ACL_CONTINUATION || pendingL2capHandle < 0 ||
            packet.handle != pendingL2capHandle
        ) {
            return packet
        }

        pendingL2capPayload += packet.payload
        if (pendingL2capPayload.size < pendingL2capExpectedBytes) return null
        if (pendingL2capPayload.size > pendingL2capExpectedBytes) {
            onLog(
                "CSR Profile 2: L2CAP reassembly overflow " +
                    "${pendingL2capPayload.size}/$pendingL2capExpectedBytes; dropping"
            )
            clearPendingL2cap()
            return null
        }

        val complete = AclPacket(
            handle = pendingL2capHandle,
            packetBoundary = ACL_START_NON_FLUSHABLE,
            cid = pendingL2capCid,
            l2capLength = pendingL2capExpectedBytes,
            payload = pendingL2capPayload,
            rawHex = ""
        )
        clearPendingL2cap()
        return complete
    }

    private fun clearPendingL2cap() {
        pendingL2capHandle = -1
        pendingL2capCid = -1
        pendingL2capExpectedBytes = 0
        pendingL2capPayload = ByteArray(0)
    }

    /**
     * CSR clones often enumerate several look-alike HCI interfaces.  Probe
     * their standard class/interface control pipe before the normal workers
     * start; the selected interface is then opened by the inherited pipeline.
     */
    override fun selectHciInterface(
        connection: UsbDeviceConnection,
        interfaces: List<UsbInterface>
    ): UsbInterface {
        val candidates = interfaces.filter {
            it.interfaceClass == 0xE0 && it.interfaceSubclass == 0x01 && it.interfaceProtocol == 0x01
        }
        if (candidates.isEmpty()) error("CSR Profile 2 found no Bluetooth HCI interface")
        val reset = byteArrayOf(0x03, 0x0C, 0x00)
        for (candidate in candidates) {
            if (!connection.claimInterface(candidate, true)) {
                onLog("CSR Profile 2 probe: interface ${candidate.id} cannot be claimed")
                continue
            }
            val written = connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or USB_RECIP_INTERFACE,
                0, 0, candidate.id, reset, reset.size, 1_000
            )
            connection.releaseInterface(candidate)
            onLog("CSR Profile 2 probe: interface ${candidate.id} standard reset $written/${reset.size}")
            if (written == reset.size) return candidate
        }
        // Preserve a deterministic endpoint layout for diagnostics. The
        // command override will report both CSR transport attempts explicitly.
        return candidates.first()
    }

    override fun sendCommand(opcode: Int, parameters: ByteArray, logPacket: Boolean) {
        val packet = ByteArray(3 + parameters.size)
        packet[0] = (opcode and 0xFF).toByte()
        packet[1] = (opcode ushr 8).toByte()
        packet[2] = parameters.size.toByte()
        parameters.copyInto(packet, 3)
        val conn = connection ?: error("CSR Profile 2 USB connection is not open")
        val interfaceId = hciInterface?.id ?: 0
        val standard = conn.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or
                USB_RECIP_INTERFACE,
            0, 0, interfaceId, packet, packet.size, 2_000
        )
        if (standard == packet.size) {
            if (logPacket) onLog("CSR Profile 2 TX HCI 0x${opcode.toString(16)} (interface recipient)")
            return
        }

        // Isolated CSR fallback: same standard HCI command packet, but device
        // recipient.  This is intentionally not added to Generic Profile 1.
        val deviceRecipient = conn.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or
                USB_RECIP_DEVICE,
            0, 0, 0, packet, packet.size, 2_000
        )
        if (deviceRecipient == packet.size) {
            onLog("CSR Profile 2 command fallback accepted for HCI 0x${opcode.toString(16)}")
            return
        }
        error("CSR Profile 2 HCI command write failed: interface=$standard/${packet.size}, device=$deviceRecipient/${packet.size}")
    }

    private companion object {
        private const val HCI_RESET = 0x0C03
        private const val HCI_NUMBER_OF_COMPLETED_PACKETS = 0x13
        private const val CSR_RESET_SETTLE_MS = 220L
        private const val USB_RECIP_DEVICE = 0x00
        private const val USB_RECIP_INTERFACE = 0x01
        private const val ACL_CONTINUATION = 0x01
        private const val ACL_START_FLUSHABLE = 0x00
        private const val ACL_START_NON_FLUSHABLE = 0x02
        private const val CSR_HID_INTERRUPT_TIMEOUT_MS = 9_000L
        private const val CSR_DISCOVERY_RESTART_GUARD_MS = 2_500L
        private const val CSR_DISCOVERY_RETRY_POLL_MS = 250L
    }
}
