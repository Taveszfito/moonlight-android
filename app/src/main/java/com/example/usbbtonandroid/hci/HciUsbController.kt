package com.example.usbbtonandroid.hci

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class HciUsbController(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val onLog: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onDevice: (HciDevice) -> Unit,
    private val onAclPacket: (AclPacket) -> Unit,
    private val loadLinkKey: (String) -> String?,
    private val saveLinkKey: (String, String) -> Unit
) : Closeable {
    private val running = AtomicBoolean(false)
    private var connection: UsbDeviceConnection? = null
    private var hciInterface: UsbInterface? = null
    private var eventIn: UsbEndpoint? = null
    private var aclIn: UsbEndpoint? = null
    private var aclOut: UsbEndpoint? = null
    private var worker: Thread? = null
    private var aclWorker: Thread? = null
    private var inputWorker: Thread? = null
    private val inputQueue = ArrayBlockingQueue<AclPacket>(INPUT_QUEUE_CAPACITY)
    private val discoveredDevices = linkedMapOf<String, InquiryDevice>()
    @Volatile private var pendingConnection: InquiryDevice? = null
    @Volatile private var activeDevice: InquiryDevice? = null
    @Volatile private var activeHandle: Int? = null
    @Volatile private var encryptedAtMs = 0L
    @Volatile private var lastHidInputMs = 0L
    @Volatile private var hidOpenAttempted = false
    @Volatile private var hidOpenAttemptAtMs = 0L
    @Volatile private var hidChannelsReady = false
    private var hidOpenRetries = 0
    private var nextSignalId = 0x40
    private var nextLocalCid = 0x0040
    @Volatile private var hidInterruptRemoteCid: Int? = null
    private var outputSequence = 0
    private var droppedInputPackets = 0L
    @Volatile private var lastActiveDevicePublishMs = 0L
    @Volatile private var lastAclLogMs = 0L
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
            onStatus("HCI Reset…")
            requireCommandComplete(0x0C03)
            onLog("HCI Reset → Success")

            val version = requireCommandComplete(0x1001)
            if (version.size >= 9) {
                onLog("Local Version → HCI ${version[1].u8()}.${version[2].u8()}, manufacturer ${version.le16(6)}")
            }

            val address = requireCommandComplete(0x1009)
            if (address.size >= 7) onLog("Local Address → ${formatAddress(address, 1)}")

            onStatus("Bluetooth Classic eszközök keresése…")
            sendCommand(0x0401, byteArrayOf(0x33, 0x8B.toByte(), 0x9E.toByte(), 0x08, 0x00))
            val discovered = resolveRemoteNames(scanUntilComplete())
            discoveredDevices.clear()
            discovered.forEach { discoveredDevices[it.address] = it }
            enableIncomingConnections()
            onStatus("A keresés kész. Válassz eszközt, vagy kapcsold be a párosított kontrollert.")
            hostLoop()
        } catch (t: Throwable) {
            if (running.get()) {
                onLog("HIBA: ${t.message ?: t.javaClass.simpleName}")
                onStatus("A próba sikertelen.")
            }
        }
    }

    fun connect(address: String, name: String = "Párosított eszköz") {
        val current = activeDevice
        val handle = activeHandle
        if (handle != null && current?.address == address) {
            if (hidChannelsReady) {
                onStatus("A DualSense már stabilan kapcsolódik.")
            } else if (!hasHidChannelProgress()) {
                onStatus("A rádiós link él; HID csatornák helyreállítása…")
                requestHidChannels(handle)
            } else {
                onStatus("A kapcsolat felépítése már folyamatban van…")
            }
            return
        }
        pendingConnection = discoveredDevices[address] ?: InquiryDevice(
            address = address,
            addressLittleEndian = addressToLittleEndian(address),
            pageScanRepetitionMode = 0x01,
            clockOffsetLow = 0,
            clockOffsetHigh = 0,
            deviceClass = "2508ED",
            rssi = null,
            name = name
        )
        onStatus("Kapcsolódási kérelem előkészítve…")
    }

    @Volatile private var pendingDisconnectAddress: String? = null

    fun disconnect(address: String) {
        pendingDisconnectAddress = address.uppercase()
    }

    fun sendOutput(config: com.example.usbbtonandroid.DualSenseOutputConfig): Boolean {
        val handle = activeHandle ?: return false
        val cid = hidInterruptRemoteCid ?: return false
        val report = com.example.usbbtonandroid.DualSenseBtOutputBuilder.build(config, outputSequence++)
        sendAcl(handle, cid, byteArrayOf(0xA2.toByte()) + report)
        onLog("DualSense BT output → CID=0x${cid.hex4()}")
        return true
    }

    private fun enableIncomingConnections() {
        requireCommandComplete(0x0C1A, byteArrayOf(0x02))
        onLog("Page Scan enabled → párosított eszközök visszakapcsolódhatnak")
    }

    private fun hostLoop() {
        var lastLinkCheck = 0L
        while (running.get()) {
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
                    onStatus("A kapcsolódás sikertelen.")
                    publishDevice(target, "Sikertelen")
                }
            }
            val now = System.currentTimeMillis()
            val active = activeHandle
            if (active != null && encryptedAtMs > 0 && !hidOpenAttempted &&
                now - encryptedAtMs >= HID_OPEN_DELAY_MS
            ) {
                onStatus("Rádiós link él; HID csatornák felépítése…")
                requestHidChannels(active)
            }
            if (active != null && hidOpenAttempted && !hidChannelsReady &&
                lastHidInputMs == 0L && !hasHidChannelProgress() &&
                now - hidOpenAttemptAtMs >= HID_RETRY_INTERVAL_MS &&
                hidOpenRetries < HID_MAX_RETRIES
            ) {
                hidOpenRetries++
                onStatus("HID csatornanyitás újrapróbálása ($hidOpenRetries/$HID_MAX_RETRIES)…")
                pendingChannels.clear()
                pendingConfigs.clear()
                requestHidChannels(active)
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
                onStatus("A link él, de a HID adatfolyam még nem indult el.")
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
                        null, "Párosított DualSense"
                    )
                    onLog("Incoming Connection Request ← $address")
                    connectAndPair(known, incomingAddress = addressBytes)
                }
                0x05 -> handleDisconnection(event.parameters)
                0x0E -> handleLinkCheckResult(event.parameters)
            }
        }
    }

    private fun handleLinkCheckResult(parameters: ByteArray) {
        if (parameters.size < 7 || parameters.le16(1) != 0x1405) return
        val status = parameters[3].u8()
        val device = activeDevice ?: return
        if (status == 0) {
            val rssi = parameters[6].toInt()
            onDevice(HciDevice(
                device.address, device.name ?: "DualSense", device.deviceClass,
                rssi, paired = true, state = "Kapcsolódva • élő"
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
        if (activeHandle == handle) markDisconnected("Szétkapcsolva: $description (0x${reason.hex2()})")
    }

    private fun markDisconnected(reason: String) {
        val device = activeDevice
        activeHandle = null
        activeDevice = null
        hidInterruptRemoteCid = null
        encryptedAtMs = 0
        lastHidInputMs = 0
        hidOpenAttempted = false
        hidOpenAttemptAtMs = 0
        hidChannelsReady = false
        hidOpenRetries = 0
        pendingChannels.clear()
        pendingConfigs.clear()
        channelsByLocalCid.clear()
        onStatus(reason)
        if (device != null) onDevice(HciDevice(
            device.address, device.name ?: "DualSense", device.deviceClass,
            null, paired = loadLinkKey(device.address) != null, state = "Nincs kapcsolat"
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
            priority = Thread.NORM_PRIORITY
            start()
        }
        aclWorker = Thread(::aclReadLoop, "usb-hci-acl-in").apply {
            // HCI/L2CAP reception is the real-time edge of the pipeline. It must
            // not lose time to UI rendering or Wi-Fi/HID relay work.
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun inputDispatchLoop() {
        while (running.get()) {
            try {
                val packet = inputQueue.take()
                val startedAt = System.currentTimeMillis()
                onAclPacket(packet)
                val duration = System.currentTimeMillis() - startedAt
                if (duration >= INPUT_DISPATCH_STALL_LOG_MS) {
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
        if (droppedInputPackets == 1L || droppedInputPackets % 100L == 0L) {
            onLog("Input queue overrun: $droppedInputPackets packet(s) dropped")
        }
    }

    private fun aclReadLoop() {
        val buffer = ByteArray(4096)
        var pending = ByteArray(0)
        while (running.get()) {
            val size = connection?.bulkTransfer(aclIn, buffer, buffer.size, 500) ?: -1
            if (size <= 0) continue
            pending += buffer.copyOf(size)
            var offset = 0
            while (offset + 4 <= pending.size) {
                val handleAndFlags = pending.le16(offset)
                val dataLength = pending.le16(offset + 2)
                if (dataLength > MAX_ACL_PAYLOAD) {
                    onLog("Érvénytelen ACL hossz=$dataLength; USB stream újraszinkronizálva")
                    pending = ByteArray(0)
                    offset = 0
                    break
                }
                val packetEnd = offset + 4 + dataLength
                if (packetEnd > pending.size) break
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
                if (cid == 0x0001) handleL2capSignaling(handle, payload)
                if (cid != null && cid != 0x0001 && payload.firstOrNull()?.u8() == 0xA1) {
                    val previousHidInputMs = lastHidInputMs
                    lastHidInputMs = now
                    if (previousHidInputMs != 0L && now - previousHidInputMs >= HID_GAP_LOG_MS) {
                        onLog("HID radio/USB input gap: ${now - previousHidInputMs} ms")
                    }
                    hidChannelsReady = true
                    if (now - lastActiveDevicePublishMs >= DEVICE_PUBLISH_INTERVAL_MS) {
                        lastActiveDevicePublishMs = now
                        activeDevice?.let { publishDevice(it, "Kapcsolódva • HID aktív", paired = true) }
                    }
                }
                enqueueInput(packet)
                offset = packetEnd
            }
            if (offset > 0) {
                pending = if (offset == pending.size) {
                    ByteArray(0)
                } else {
                    pending.copyOfRange(offset, pending.size)
                }
            }
            if (pending.size > MAX_USB_CARRY_BYTES) {
                onLog("ACL carry buffer túlcsordult (${pending.size}); törölve")
                pending = ByteArray(0)
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
            onStatus("Eszköznév lekérése (${index + 1}/${devices.size})…")
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
        onStatus("Kapcsolódás: ${device.name}…")
        publishDevice(device, "Kapcsolódás…")
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
                    if (event.parameters[10].u8() != 0) {
                        encryptedAtMs = System.currentTimeMillis()
                        lastHidInputMs = 0
                        hidOpenAttempted = false
                        hidChannelsReady = false
                        hidOpenRetries = 0
                        onStatus("A visszaállított link már titkosított; HID indítása…")
                        publishDevice(device, "Titkosítva • HID-re vár", paired = true)
                        return
                    }
                    onStatus("DualSense kapcsolódott; párosítás…")
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
                            hidOpenRetries = 0
                            onStatus("Titkosított link kész; HID indítása…")
                            publishDevice(device, "Titkosítva • HID-re vár", paired = true)
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
                    markDisconnected("Szétkapcsolva (0x${reason.hex2()})")
                    error("Disconnected: reason=0x${reason.hex2()}")
                }
            }
        }
        if (pairingComplete) {
            onStatus("DualSense párosítva; titkosítási eseményre vár.")
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
            append(name ?: "Ismeretlen eszköz")
            append(" — $address, class=$deviceClass")
            if (rssi != null) append(", RSSI=$rssi dBm")
        }
        onDevice(HciDevice(
            address, name ?: "Ismeretlen eszköz", deviceClass, rssi,
            paired = loadLinkKey(address) != null, state = "Elérhető"
        ))
        onLog("Inquiry Result → $label")
    }

    private fun publishDevice(
        device: InquiryDevice,
        state: String,
        paired: Boolean = loadLinkKey(device.address) != null
    ) {
        onDevice(HciDevice(
            device.address, device.name ?: "Ismeretlen eszköz", device.deviceClass,
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
    private fun sendAcl(handle: Int, cid: Int, payload: ByteArray) {
        val l2cap = ByteArray(4 + payload.size)
        l2cap[0] = payload.size.toByte()
        l2cap[1] = (payload.size ushr 8).toByte()
        l2cap[2] = cid.toByte()
        l2cap[3] = (cid ushr 8).toByte()
        payload.copyInto(l2cap, 4)
        val packet = ByteArray(4 + l2cap.size)
        val handleAndFlags = handle or 0x2000
        packet[0] = handleAndFlags.toByte()
        packet[1] = (handleAndFlags ushr 8).toByte()
        packet[2] = l2cap.size.toByte()
        packet[3] = (l2cap.size ushr 8).toByte()
        l2cap.copyInto(packet, 4)
        val sent = connection?.bulkTransfer(aclOut, packet, packet.size, COMMAND_TIMEOUT_MS) ?: -1
        check(sent == packet.size) { "ACL OUT write: $sent/${packet.size}" }
        onLog("TX ACL handle=0x${handle.hex4()} cid=0x${cid.hex4()} len=${payload.size}")
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
                    val channel = L2capChannel(psm, localCid, remoteCid)
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
                    val configId = nextSignalId++ and 0xFF
                    sendAcl(handle, 0x0001, byteArrayOf(
                        0x04, configId.toByte(), 0x04, 0x00,
                        remoteCid.toByte(), (remoteCid ushr 8).toByte(),
                        0x00, 0x00
                    ))
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
                                    0x0001 -> "HID csatorna: hitelesítés folyamatban…"
                                    0x0002 -> "HID csatorna: engedélyezés folyamatban…"
                                    else -> "HID csatorna függőben…"
                                }
                            )
                        }
                        else -> {
                            pendingChannels.remove(id)
                            onStatus("HID csatornanyitás elutasítva: 0x${result.hex4()}")
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
                        if (channel.psm == 0x0011) {
                            onStatus("HID Control kész; Interrupt csatorna nyitása…")
                            if (pendingChannels.values.none { it.psm == 0x0013 } &&
                                channelsByLocalCid.values.none { it.psm == 0x0013 }
                            ) requestL2capChannel(handle, 0x0013)
                        } else if (channel.psm == 0x0013) {
                            hidInterruptRemoteCid = channel.remoteCid
                            hidChannelsReady = true
                            onStatus("HID csatornák készek; input reportokra vár…")
                            channelsByLocalCid.values.firstOrNull { it.psm == 0x0011 }
                                ?.remoteCid?.let { controlCid ->
                                    sendAcl(handle, controlCid, byteArrayOf(0x71))
                                    onLog("HIDP Set Protocol → Report mode")
                                }
                        }
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
                else -> onLog("L2CAP signaling code=0x${code.hex2()} id=$id len=$length")
            }
            offset += 4 + length
        }
    }

    private fun requestHidChannels(handle: Int) {
        if (hidChannelsReady || hasHidChannelProgress()) return
        hidOpenAttempted = true
        hidOpenAttemptAtMs = System.currentTimeMillis()
        requestL2capChannel(handle, 0x0011)
    }

    private fun hasHidChannelProgress(): Boolean =
        pendingChannels.values.any { it.psm == 0x0011 || it.psm == 0x0013 } ||
            pendingConfigs.values.any { it.psm == 0x0011 || it.psm == 0x0013 } ||
            channelsByLocalCid.values.any { it.psm == 0x0011 || it.psm == 0x0013 }

    private fun requestL2capChannel(handle: Int, psm: Int) {
        val localCid = nextLocalCid++
        val id = nextSignalId++ and 0xFF
        pendingChannels[id] = L2capChannel(psm, localCid, null)
        onLog("L2CAP Connection Request → PSM=0x${psm.hex4()} SCID=0x${localCid.hex4()}")
        sendAcl(handle, 0x0001, byteArrayOf(
            0x02, id.toByte(), 0x04, 0x00,
            psm.toByte(), (psm ushr 8).toByte(),
            localCid.toByte(), (localCid ushr 8).toByte()
        ))
    }

    private fun sendConfigRequest(handle: Int, remoteCid: Int, channel: L2capChannel) {
        val id = nextSignalId++ and 0xFF
        pendingConfigs[id] = channel
        sendAcl(handle, 0x0001, byteArrayOf(
            0x04, id.toByte(), 0x04, 0x00,
            remoteCid.toByte(), (remoteCid ushr 8).toByte(),
            0x00, 0x00
        ))
    }

    private fun readEvent(timeoutMs: Int): HciEvent? {
        val buffer = ByteArray(260)
        val size = connection?.bulkTransfer(eventIn, buffer, buffer.size, timeoutMs) ?: -1
        if (size <= 0) return null
        check(size >= 2) { "Túl rövid HCI event: $size byte" }
        val parameterLength = buffer[1].u8()
        check(size >= parameterLength + 2) { "Hiányos HCI event: $size/${parameterLength + 2}" }
        val event = HciEvent(buffer[0].u8(), buffer.copyOfRange(2, 2 + parameterLength))
        onLog("RX EVT 0x${event.code.hex2()} ($parameterLength byte)")
        return event
    }

    override fun close() {
        running.set(false)
        connection?.close()
        worker?.interrupt()
        aclWorker?.interrupt()
        inputWorker?.interrupt()
        inputQueue.clear()
    }

    private fun release() {
        val conn = connection
        val iface = hciInterface
        if (conn != null && iface != null) runCatching { conn.releaseInterface(iface) }
        runCatching { conn?.close() }
        connection = null
    }

    private data class HciEvent(val code: Int, val parameters: ByteArray)
    private data class L2capChannel(
        val psm: Int,
        val localCid: Int,
        var remoteCid: Int?
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

    companion object {
        private const val COMMAND_TIMEOUT_MS = 5_000
        private const val INQUIRY_TIMEOUT_MS = 15_000
        private const val REMOTE_NAME_TIMEOUT_MS = 8_000
        private const val CONNECTION_TIMEOUT_MS = 45_000
        private const val LINK_CHECK_INTERVAL_MS = 3_000L
        private const val HID_OPEN_DELAY_MS = 750L
        private const val HID_START_TIMEOUT_MS = 6_000L
        private const val HID_RETRY_INTERVAL_MS = 3_000L
        private const val HID_MAX_RETRIES = 3
        private const val MAX_ACL_PAYLOAD = 4096
        private const val MAX_USB_CARRY_BYTES = 8192
        private const val ACL_LOG_INTERVAL_MS = 1_000L
        private const val DEVICE_PUBLISH_INTERVAL_MS = 500L
        private const val HID_GAP_LOG_MS = 250L
        private const val INPUT_DISPATCH_STALL_LOG_MS = 100L
        private const val INPUT_QUEUE_CAPACITY = 32
        private const val USB_RECIP_INTERFACE = 0x01

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
