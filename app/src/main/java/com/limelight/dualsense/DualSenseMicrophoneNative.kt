package com.limelight.dualsense

/** Native Opus encoder and encrypted Apollo Extended microphone transport. */
object DualSenseMicrophoneNative {
    @JvmStatic external fun start(): Boolean
    @JvmStatic external fun startBluetooth(): Boolean
    @JvmStatic external fun stop()
    @JvmStatic external fun encodeAndSend(samples: ShortArray): Int
    @JvmStatic external fun decodeBluetoothAndSend(opus: ByteArray): Int
}
