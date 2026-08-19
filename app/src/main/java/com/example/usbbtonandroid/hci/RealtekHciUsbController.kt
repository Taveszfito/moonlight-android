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
    override fun configureControllerToHostAclFlowControl() {
        // Leave the HCI default (unlimited controller-to-host ACL delivery).
        // Do not alter the Baseus Profile 1 initialisation sequence.
        onLog("Realtek Profile 3: controller→host ACL flow control disabled for reliable reconnect")
    }

    override fun hidStartTimeoutMs(): Long = 25_000L

    override fun hidInterruptStageTimeoutMs(): Long = 8_000L

    // This adapter ignores controller-initiated reconnects for the duration of
    // an Inquiry.  Stay in Page Scan after a disconnect so the DualSense can
    // reconnect immediately; the user can still start a bounded scan manually.
    override fun runStartupInquiry(): Boolean = false
}
