package com.example.usbbtonandroid

import java.util.zip.CRC32

data class DualSenseOutputConfig(
    val red: Int = 0, val green: Int = 80, val blue: Int = 255,
    val playerLeds: Int = 0x04, val micLed: Boolean = false,
    val leftRumble: Int = 0, val rightRumble: Int = 0,
    val leftTriggerMode: TriggerMode = TriggerMode.OFF,
    val rightTriggerMode: TriggerMode = TriggerMode.OFF,
    val triggerStrength: Int = 100
)

enum class TriggerMode { OFF, RESISTANCE, VIBRATION }

object DualSenseBtOutputBuilder {
    fun build(config: DualSenseOutputConfig, sequence: Int): ByteArray {
        val report = ByteArray(78)
        report[0] = 0x31
        report[1] = ((sequence and 0x0F) shl 4).toByte()
        report[2] = 0x10
        report[3] = 0x0F
        report[4] = 0x15
        report[5] = config.rightRumble.coerceIn(0, 255).toByte()
        report[6] = config.leftRumble.coerceIn(0, 255).toByte()
        report[11] = if (config.micLed) 1 else 0
        writeTrigger(report, 13, config.rightTriggerMode, config.triggerStrength)
        writeTrigger(report, 24, config.leftTriggerMode, config.triggerStrength)
        report[41] = 0x03
        report[44] = 0x02
        report[45] = 0
        report[46] = ((config.playerLeds and 0x1F) or 0x20).toByte()
        report[47] = config.red.coerceIn(0, 255).toByte()
        report[48] = config.green.coerceIn(0, 255).toByte()
        report[49] = config.blue.coerceIn(0, 255).toByte()

        val crc = CRC32()
        crc.update(0xA2)
        crc.update(report, 0, 74)
        val value = crc.value
        report[74] = value.toByte()
        report[75] = (value ushr 8).toByte()
        report[76] = (value ushr 16).toByte()
        report[77] = (value ushr 24).toByte()
        return report
    }

    private fun writeTrigger(report: ByteArray, offset: Int, mode: TriggerMode, percent: Int) {
        repeat(11) { report[offset + it] = 0 }
        val strength = percent.coerceIn(0, 100) * 255 / 100
        when (mode) {
            TriggerMode.OFF -> Unit
            TriggerMode.RESISTANCE -> {
                report[offset] = 0x01
                report[offset + 2] = strength.toByte()
            }
            TriggerMode.VIBRATION -> {
                val amplitude = (percent.coerceIn(0, 100) * 7 / 100).coerceAtLeast(1)
                report[offset] = 0x27
                report[offset + 1] = 0xFF.toByte()
                report[offset + 2] = 0x03
                report[offset + 3] = (amplitude or (amplitude shl 3)).toByte()
                report[offset + 4] = 0x40
                report[offset + 5] = 0x04
            }
        }
    }
}
