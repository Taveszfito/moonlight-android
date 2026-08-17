package com.limelight.dualsense

/** Native Opus encoder and encrypted Apollo Extended microphone transport. */
object DualSenseMicrophoneNative {
    @JvmStatic external fun start(): Boolean
    @JvmStatic external fun startBluetooth(): Boolean
    @JvmStatic external fun stop()
    @JvmStatic external fun encodeAndSend(samples: ShortArray): Int
    @JvmStatic external fun decodeBluetoothAndSend(opus: ByteArray): Int
    @JvmStatic external fun forwardBluetoothOpus(opus: ByteArray): Int
    @JvmStatic external fun bluetoothOpusFrameSamples(opus: ByteArray): Int
    /**
     * Diagnostic-only decoder. It intentionally owns a separate Opus state so
     * listening to a BT microphone capture cannot perturb the stream path.
     */
    @JvmStatic external fun decodeBluetoothOpusForMonitor(opus: ByteArray): ShortArray?
    @JvmStatic external fun resetBluetoothOpusMonitor()
}
