package com.example.usbbtonandroid.hci

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Dedicated transport profile for the antenna-style Realtek adapter
 * (USB 0BDA:A760).
 *
 * Unlike the proven Baseus generic path, this controller accepts optional
 * controller-to-host ACL flow control and then can leave a reconnecting
 * DualSense waiting for its HID control channel.  Keeping that optional HCI
 * feature off restores the normal, immediate HID reconnect sequence without
 * changing Profile 1 behaviour for Baseus and other generic adapters.
 */
class RealtekHciUsbController(
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
    private var sdpDiagnosticsRemaining = 8

    override fun configureControllerToHostAclFlowControl() {
        // Leave the HCI default (unlimited controller-to-host ACL delivery).
        // Do not alter the Baseus Profile 1 initialisation sequence.
        onLog("Realtek Profile 3: controller→host ACL flow control disabled for reliable reconnect")
    }

    override fun hidStartTimeoutMs(): Long = 25_000L

    override fun hidInterruptStageTimeoutMs(): Long = 8_000L

    // Baseus receives a Role Change before its fast HID reconnect. This Realtek
    // adapter does not, so request the local HCI host/master role explicitly.
    override fun incomingConnectionRole(): Byte = 0x00

    // This adapter ignores controller-initiated reconnects for the duration of
    // an Inquiry.  Stay in Page Scan after a disconnect so the DualSense can
    // reconnect immediately; the user can still start a bounded scan manually.
    override fun runStartupInquiry(): Boolean = false

    override fun onNonHidL2capPayload(
        handle: Int,
        cid: Int,
        remoteCid: Int?,
        psm: Int?,
        payload: ByteArray
    ) {
        if (psm != 0x0001 || sdpDiagnosticsRemaining-- <= 0) return
        val preview = payload.take(48).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        onLog("Realtek SDP RX → cid=0x${cid.toString(16).padStart(4, '0')} " +
            "len=${payload.size} data=$preview")

        // ServiceSearchAttributeRequest. The bridge exposes no SDP services;
        // acknowledge that fact instead of making the DualSense wait for its
        // SDP transaction timeout before it permits HID Control.
        if (remoteCid != null && payload.size >= 5 && (payload[0].toInt() and 0xFF) == 0x06) {
            sendNonHidL2capPayload(handle, remoteCid, byteArrayOf(
                0x07, payload[1], payload[2], // response PDU + transaction id
                0x00, 0x03,                   // parameter length
                0x00, 0x00, 0x00              // zero attributes + empty continuation
            ))
            onLog("Realtek SDP → empty service response sent; continuing HID immediately")
        }
    }

}
