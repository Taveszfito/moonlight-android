package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.dualsense.DualSenseWiredOutput;
import com.limelight.dualsense.DualSenseAudioBridge;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import java.util.concurrent.atomic.AtomicBoolean;

/** Exclusive raw USB driver for directly connected DualSense controllers. */
public final class DualSenseController extends AbstractController {
    private static volatile DualSenseController activeController;

    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private final UsbInterface hidInterface;
    private final UsbEndpoint inputEndpoint;
    private final UsbEndpoint outputEndpoint;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread inputThread;
    private final boolean[] touchActive = new boolean[2];
    private boolean headphonesConnected;
    private boolean audioRouteInitialized;
    private long inputPacketCount;
    private long inputReadErrors;
    private volatile long lastInputAtMs;
    private volatile int batteryPercent = -1;

    public static boolean canClaimDevice(UsbDevice device) {
        return device.getVendorId() == 0x054c &&
                (device.getProductId() == 0x0ce6 || device.getProductId() == 0x0df2);
    }

    public static boolean hasActiveController() {
        DualSenseController active = activeController;
        return active != null && active.running.get();
    }

    public static long getActiveInputPacketCount() {
        DualSenseController active = activeController;
        return active != null ? active.inputPacketCount : 0;
    }

    public static long getActiveInputAgeMs() {
        DualSenseController active = activeController;
        if (active == null || active.lastInputAtMs == 0) return -1;
        return Math.max(0, SystemClock.elapsedRealtime() - active.lastInputAtMs);
    }

    public static long getActiveInputReadErrors() {
        DualSenseController active = activeController;
        return active != null ? active.inputReadErrors : 0;
    }

    public static int getActiveBatteryPercent() {
        DualSenseController active = activeController;
        return active != null ? active.batteryPercent : -1;
    }

    public static boolean getActiveHeadphonesConnected() {
        DualSenseController active = activeController;
        return active != null && active.headphonesConnected;
    }

    public DualSenseController(UsbDevice device, UsbDeviceConnection connection,
                               int deviceId, UsbDriverListener listener) {
        super(deviceId, listener, device.getVendorId(), device.getProductId());
        this.device = device;
        this.connection = connection;
        UsbInterface foundInterface = null;
        UsbEndpoint foundIn = null;
        UsbEndpoint foundOut = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_HID) continue;
            for (int j = 0; j < intf.getEndpointCount(); j++) {
                UsbEndpoint ep = intf.getEndpoint(j);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) foundIn = ep;
                else foundOut = ep;
            }
            if (foundIn != null && foundOut != null) {
                foundInterface = intf;
                break;
            }
        }
        hidInterface = foundInterface;
        inputEndpoint = foundIn;
        outputEndpoint = foundOut;
        type = MoonBridge.LI_CTYPE_PS5;
        capabilities = (short) (MoonBridge.LI_CCAP_ANALOG_TRIGGERS |
                MoonBridge.LI_CCAP_RUMBLE | MoonBridge.LI_CCAP_ACCEL |
                MoonBridge.LI_CCAP_GYRO | MoonBridge.LI_CCAP_TOUCHPAD |
                MoonBridge.LI_CCAP_RGB_LED | MoonBridge.LI_CCAP_BATTERY_STATE);
        supportedButtonFlags = ControllerPacket.A_FLAG | ControllerPacket.B_FLAG |
                ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG |
                ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG |
                ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
                ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG |
                ControllerPacket.PLAY_FLAG | ControllerPacket.BACK_FLAG |
                ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG |
                ControllerPacket.SPECIAL_BUTTON_FLAG | ControllerPacket.TOUCHPAD_FLAG |
                ControllerPacket.MISC_FLAG;
    }

    @Override public boolean start() {
        if (hidInterface == null || inputEndpoint == null || outputEndpoint == null ||
                !connection.claimInterface(hidInterface, true)) return false;
        activeController = this;
        running.set(true);
        inputThread = new Thread(this::readLoop, "DualSenseUsbInput");
        inputThread.setDaemon(true);
        inputThread.start();
        notifyDeviceAdded();
        LimeLog.info("DualSense raw USB driver active: " + device.getDeviceName());
        DualSenseAudioBridge.onWiredControllerConnected();
        return true;
    }

    private void readLoop() {
        byte[] report = new byte[64];
        while (running.get()) {
            int read = connection.bulkTransfer(inputEndpoint, report, report.length, 250);
            if (read == 64 && (report[0] & 0xff) == 0x01) {
                inputPacketCount++;
                lastInputAtMs = SystemClock.elapsedRealtime();
                parseReport(report);
            }
            else if (read < 0 && running.get()) {
                inputReadErrors++;
                Thread.yield();
            }
        }
    }

    private void parseReport(byte[] r) {
        leftStickX = axis(r[1]); leftStickY = axis(r[2]);
        rightStickX = axis(r[3]); rightStickY = axis(r[4]);
        leftTrigger = (r[5] & 0xff) / 255f;
        rightTrigger = (r[6] & 0xff) / 255f;
        int b0 = r[8] & 0xff, b1 = r[9] & 0xff, b2 = r[10] & 0xff;
        buttonFlags = 0;
        int hat = b0 & 0x0f;
        if (hat == 0 || hat == 1 || hat == 7) buttonFlags |= ControllerPacket.UP_FLAG;
        if (hat == 1 || hat == 2 || hat == 3) buttonFlags |= ControllerPacket.RIGHT_FLAG;
        if (hat == 3 || hat == 4 || hat == 5) buttonFlags |= ControllerPacket.DOWN_FLAG;
        if (hat == 5 || hat == 6 || hat == 7) buttonFlags |= ControllerPacket.LEFT_FLAG;
        if ((b0 & 0x10) != 0) buttonFlags |= ControllerPacket.X_FLAG;
        if ((b0 & 0x20) != 0) buttonFlags |= ControllerPacket.A_FLAG;
        if ((b0 & 0x40) != 0) buttonFlags |= ControllerPacket.B_FLAG;
        if ((b0 & 0x80) != 0) buttonFlags |= ControllerPacket.Y_FLAG;
        if ((b1 & 0x01) != 0) buttonFlags |= ControllerPacket.LB_FLAG;
        if ((b1 & 0x02) != 0) buttonFlags |= ControllerPacket.RB_FLAG;
        if ((b1 & 0x10) != 0) buttonFlags |= ControllerPacket.BACK_FLAG;
        if ((b1 & 0x20) != 0) buttonFlags |= ControllerPacket.PLAY_FLAG;
        if ((b1 & 0x40) != 0) buttonFlags |= ControllerPacket.LS_CLK_FLAG;
        if ((b1 & 0x80) != 0) buttonFlags |= ControllerPacket.RS_CLK_FLAG;
        if ((b2 & 0x01) != 0) buttonFlags |= ControllerPacket.SPECIAL_BUTTON_FLAG;
        if ((b2 & 0x02) != 0) buttonFlags |= ControllerPacket.TOUCHPAD_FLAG;
        if ((b2 & 0x04) != 0) buttonFlags |= ControllerPacket.MISC_FLAG;
        reportInput();

        gyroX = s16(r, 16) / 16f; gyroY = s16(r, 18) / 16f; gyroZ = s16(r, 20) / 16f;
        accelX = s16(r, 22); accelY = s16(r, 24); accelZ = s16(r, 26);
        reportMotion();
        parseTouch(r, 0, 33); parseTouch(r, 1, 37);

        boolean jack = (r[54] & 0x01) != 0;
        int capacity = (r[53] & 0x0f);
        batteryPercent = Math.min(100, capacity * 100 / 8);
        if (!audioRouteInitialized || jack != headphonesConnected) {
            audioRouteInitialized = true;
            headphonesConnected = jack;
            DualSenseWiredOutput.setHeadphonesConnected(jack);
        }
    }

    private void parseTouch(byte[] r, int pointer, int offset) {
        boolean active = (r[offset] & 0x80) == 0;
        int x = (r[offset + 1] & 0xff) | ((r[offset + 2] & 0x0f) << 8);
        int y = ((r[offset + 2] & 0xf0) >> 4) | ((r[offset + 3] & 0xff) << 4);
        if (active || touchActive[pointer]) reportTouch(pointer, active, x, y);
        touchActive[pointer] = active;
    }

    private static float axis(byte value) {
        int raw = value & 0xff;
        return Math.max(-1f, Math.min(1f, (raw - 128) / 127f));
    }
    private static short s16(byte[] r, int o) {
        return (short) ((r[o] & 0xff) | (r[o + 1] << 8));
    }

    public static boolean sendActiveReport(byte[] report) {
        DualSenseController active = activeController;
        return active != null && active.sendReport(report);
    }

    private synchronized boolean sendReport(byte[] report) {
        if (!running.get()) return false;
        return connection.bulkTransfer(outputEndpoint, report, report.length, 100) == report.length;
    }

    @Override public void rumble(short lowFreqMotor, short highFreqMotor) {
        // Merge compatible rumble into the same native HID state used for LEDs,
        // triggers, and audio routing. This avoids Android's basic vibrator path
        // and preserves the raw USB/ISO connection.
        DualSenseWiredOutput.sendRumble(lowFreqMotor, highFreqMotor);
    }
    @Override public void rumbleTriggers(short leftTrigger, short rightTrigger) { }

    @Override public void stop() {
        running.set(false);
        if (activeController == this) activeController = null;
        DualSenseAudioBridge.onWiredControllerDisconnected();
        if (inputThread != null) inputThread.interrupt();
        try { connection.releaseInterface(hidInterface); } catch (Throwable ignored) { }
        connection.close();
        notifyDeviceRemoved();
    }
}
