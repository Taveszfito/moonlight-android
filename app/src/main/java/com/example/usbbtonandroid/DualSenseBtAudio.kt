package com.example.usbbtonandroid

import java.util.zip.CRC32

/** Builds the DualSense native Bluetooth audio/haptics reports. */
object DualSenseBtAudioBuilder {
    const val HAPTICS_BYTES_PER_REPORT = 64

    fun build(haptics: ByteArray, sequence: Int, packetCounter: Int,
              speakerOpus: ByteArray? = null): ByteArray {
        require(haptics.size == HAPTICS_BYTES_PER_REPORT)
        require(speakerOpus == null || speakerOpus.size == SPEAKER_BYTES_PER_REPORT)

        val report = ByteArray(if (speakerOpus != null) 334 else 206)
        report[0] = if (speakerOpus != null) 0x35 else 0x33
        report[1] = ((sequence and 0x0f) shl 4).toByte()
        report[2] = 0x91.toByte()
        report[3] = 7
        report[4] = 0xfe.toByte()
        report.fill(48, 5, 10)
        report[10] = packetCounter.toByte()
        if (speakerOpus != null) {
            report[11] = 0x93.toByte() // Built-in speaker Opus packet
            report[12] = 200.toByte()
            speakerOpus.copyInto(report, 13)
            report[213] = 0x92.toByte() // Native 3 kHz stereo haptics packet
            report[214] = 64
            haptics.copyInto(report, 215)
        } else {
            report[11] = 0x92.toByte()
            report[12] = 64
            haptics.copyInto(report, 13)
        }

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
        report[3] = 63
        report[4] = 0xfd.toByte()
        report[5] = 0xf7.toByte()
        report[8] = 0x7f
        report[9] = 0x7f
        report[10] = 0xff.toByte()
        report[11] = 0x09
        report[13] = 0x0f
        report[39] = 0x0a
        report[40] = 0x07
        report[43] = 0x02
        report[44] = 0x01
        report[46] = 0xff.toByte()
        report[47] = 0xd7.toByte()

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

    private const val SPEAKER_BYTES_PER_REPORT = 200
}
