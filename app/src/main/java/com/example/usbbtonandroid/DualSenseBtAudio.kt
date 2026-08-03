package com.example.usbbtonandroid

import java.util.zip.CRC32

/** Builds the DualSense native Bluetooth audio/haptics report (HID report 0x39). */
object DualSenseBtAudioBuilder {
    const val HAPTICS_BYTES_PER_REPORT = 128

    fun build(haptics: ByteArray, sequence: Int, packetCounter: Int): ByteArray {
        require(haptics.size == HAPTICS_BYTES_PER_REPORT)

        // This layout is the controller's native wireless transport: two
        // consecutive 64-byte, signed 8-bit, 3 kHz stereo haptic blocks.
        val report = ByteArray(547)
        report[0] = 0x39
        report[1] = ((sequence and 0x0f) shl 4).toByte()
        report[2] = 0x91.toByte()
        report[3] = 0x06
        report[4] = 0x7e
        report[5] = 0x40
        report[6] = 0x40
        report[7] = 0x40
        report[8] = 0x40
        report[9] = packetCounter.toByte()
        report[10] = 0xd2.toByte()
        report[11] = 64
        haptics.copyInto(report, 12)

        // The final four bytes are the standard DualSense Bluetooth output CRC.
        val crc = CRC32()
        crc.update(0xa2)
        crc.update(report, 0, report.size - 4)
        val value = crc.value
        report[report.size - 4] = value.toByte()
        report[report.size - 3] = (value ushr 8).toByte()
        report[report.size - 2] = (value ushr 16).toByte()
        report[report.size - 1] = (value ushr 24).toByte()
        return report
    }

    /** Enables the controller's wireless audio/haptics processing path. */
    fun buildWake(sequence: Int): ByteArray {
        val report = ByteArray(142)
        report[0] = 0x32
        report[1] = ((sequence and 0x0f) shl 4).toByte()
        report[2] = 0x90.toByte()
        report[3] = 0x3f
        report[4] = 0x80.toByte() // SetStateData.AllowAudioControl

        val crc = CRC32()
        crc.update(0xa2)
        crc.update(report, 0, report.size - 4)
        val value = crc.value
        report[report.size - 4] = value.toByte()
        report[report.size - 3] = (value ushr 8).toByte()
        report[report.size - 2] = (value ushr 16).toByte()
        report[report.size - 1] = (value ushr 24).toByte()
        return report
    }
}
