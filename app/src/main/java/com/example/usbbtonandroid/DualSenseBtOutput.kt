package com.example.usbbtonandroid

import java.util.zip.CRC32

data class DualSenseOutputConfig(
    val red: Int = 0, val green: Int = 80, val blue: Int = 255,
    val playerLeds: Int = 0x04, val micLed: Boolean = false,
    val leftRumble: Int = 0, val rightRumble: Int = 0,
    val leftTriggerMode: TriggerMode = TriggerMode.OFF,
    val rightTriggerMode: TriggerMode = TriggerMode.OFF,
    val triggerStrength: Int = 100,
    val leftTriggerEffect: ByteArray? = null,
    val rightTriggerEffect: ByteArray? = null
)

enum class TriggerMode { OFF, RESISTANCE, VIBRATION }

object DualSenseBtOutputBuilder {
    fun build(config: DualSenseOutputConfig, sequence: Int,
              nativeBluetoothAudio: Boolean = false,
              headsetRoute: Boolean = false): ByteArray {
        val report = ByteArray(78)
        report[0] = 0x31
        report[1] = ((sequence and 0x0F) shl 4).toByte()
        report[2] = 0x10
        val compatibleRumbleActive = config.leftRumble != 0 || config.rightRumble != 0
        // Native Bluetooth audio normally owns the haptics path. If the host
        // sends ordinary rumble, temporarily select compatible vibration too;
        // the next zero-motor report automatically returns to native HD haptics.
        report[3] = if (nativeBluetoothAudio) {
            (if (compatibleRumbleActive) 0xFF else 0xFC).toByte()
        } else {
            0x0F
        }
        report[4] = if (nativeBluetoothAudio) 0xD5.toByte() else 0x15
        report[5] = config.rightRumble.coerceIn(0, 255).toByte()
        report[6] = config.leftRumble.coerceIn(0, 255).toByte()
        if (nativeBluetoothAudio) {
            // Keep the control-state profile byte-for-byte aligned with the
            // verified Windows BT client. The 0xD5 mask and these audio fields
            // are what make the controller produce a continuous 10 ms Opus
            // microphone stream; the media reports themselves remain owned by
            // the separate native audio path.
            report[7] = if (headsetRoute) HEADSET_VOLUME_MAX.toByte() else 0
            report[8] = SPEAKER_VOLUME_SAFE.toByte()
            report[9] = 0xFF.toByte()
            report[10] = if (headsetRoute) 0x01 else 0x09
            report[39] = 0x0A
            report[40] = 0x03
        }
        report[11] = if (config.micLed) 1 else 0
        writeTrigger(report, 13, config.rightTriggerMode, config.triggerStrength,
            config.rightTriggerEffect)
        writeTrigger(report, 24, config.leftTriggerMode, config.triggerStrength,
            config.leftTriggerEffect)
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

    /**
     * Narrow equivalent of DS5_Bridge's send_speaker_output_state(). This is
     * the distinct amplified internal-speaker profile, not merely a volume
     * value. It intentionally leaves LEDs, player indicators, triggers and
     * motors untouched.
     */
    fun buildAmplifiedSpeakerSetup(sequence: Int, microphoneEnabled: Boolean): ByteArray {
        val report = ByteArray(78)
        report[0] = 0x31
        report[1] = ((sequence and 0x0f) shl 4).toByte()
        report[2] = 0x10
        // speaker-volume + audio-control; add mic-volume only while duplex is on
        report[3] = (if (microphoneEnabled) 0xe0 else 0xa0).toByte()
        report[4] = 0x80.toByte() // audio-control2 / speaker preamp enable
        report[8] = LEGACY_SPEAKER_VOLUME.toByte()
        if (microphoneEnabled) report[9] = 0x40
        report[10] = LEGACY_SPEAKER_AUDIO_CONTROL.toByte()
        report[40] = LEGACY_SPEAKER_PREAMP.toByte()
        fillCrc(report)
        return report
    }

    private fun fillCrc(report: ByteArray) {
        val crc = CRC32()
        crc.update(0xA2)
        crc.update(report, 0, 74)
        val value = crc.value
        report[74] = value.toByte()
        report[75] = (value ushr 8).toByte()
        report[76] = (value ushr 16).toByte()
        report[77] = (value ushr 24).toByte()
    }

    private const val HEADSET_VOLUME_MAX = 0x7f
    private const val SPEAKER_VOLUME_SAFE = 0x64
    // Audio fields recovered from the deleted alpha's loud Bluetooth speaker
    // state. They are used only for the internal membrane, never for jack I/O.
    private const val LEGACY_SPEAKER_VOLUME = 0x7f
    private const val LEGACY_SPEAKER_AUDIO_CONTROL = 0xff
    private const val SPEAKER_PREAMP_SAFE = 0x02
    private const val LEGACY_SPEAKER_PREAMP = 0x07

    private fun writeTrigger(report: ByteArray, offset: Int, mode: TriggerMode, percent: Int,
                             rawEffect: ByteArray?) {
        repeat(11) { report[offset + it] = 0 }
        if (rawEffect != null && rawEffect.size >= 11) {
            rawEffect.copyInto(report, offset, 0, 11)
            return
        }
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
