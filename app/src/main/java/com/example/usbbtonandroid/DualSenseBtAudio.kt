package com.example.usbbtonandroid

import java.util.zip.CRC32

/** Builds the DualSense native Bluetooth audio/haptics reports. */
object DualSenseBtAudioBuilder {
    const val HAPTICS_BYTES_PER_REPORT = 64

    /** Reference Android transport: two 10.667 ms media frames per HID write. */
    fun buildTwoFrame(haptics1: ByteArray, haptics2: ByteArray,
                      speakerOpus1: ByteArray, speakerOpus2: ByteArray,
                      sequence: Int, packetCounter: Int,
                      headsetRoute: Boolean = false, latencyScale: Int = 0x80): ByteArray {
        require(haptics1.size == HAPTICS_BYTES_PER_REPORT && haptics2.size == HAPTICS_BYTES_PER_REPORT)
        require(speakerOpus1.size == SPEAKER_BYTES_PER_REPORT && speakerOpus2.size == SPEAKER_BYTES_PER_REPORT)
        val report = ByteArray(547)
        report[0] = 0x39
        report[1] = ((sequence and 0x0f) shl 4).toByte()
        report[2] = 0x91.toByte()
        report[3] = 0x07
        report[4] = 0xfe.toByte()
        report.fill(latencyScale.coerceIn(0, 0xff).toByte(), 5, 10)
        report[10] = packetCounter.toByte()
        // 0xD3 routes both channels to the internal speaker. 0xD6 is full
        // headset stereo. 0xD5 is the broken split speaker/headset mode.
        report[11] = if (headsetRoute) 0xd6.toByte() else 0xd3.toByte()
        report[12] = 200.toByte()
        speakerOpus1.copyInto(report, 13)
        speakerOpus2.copyInto(report, 213)
        report[413] = 0xd2.toByte()
        report[414] = 64
        haptics1.copyInto(report, 415)
        haptics2.copyInto(report, 479)
        fillBluetoothCrc(report)
        return report
    }

    fun build(haptics: ByteArray, sequence: Int, packetCounter: Int,
              speakerOpus: ByteArray? = null, headsetRoute: Boolean = false,
              microphoneEnabled: Boolean = false, latencyScale: Int = 64): ByteArray {
        require(haptics.size == HAPTICS_BYTES_PER_REPORT)
        require(speakerOpus == null || speakerOpus.size == SPEAKER_BYTES_PER_REPORT)

        val report = ByteArray(if (speakerOpus != null) 334 else 206)
        report[0] = if (speakerOpus != null) 0x35 else 0x33
        report[1] = ((sequence and 0x0f) shl 4).toByte()
        report[2] = 0x91.toByte()
        report[3] = 7
        // Bit 0 of the audio configuration mask controls microphone duplex.
        // Every live audio report must preserve it, otherwise starting mirror
        // audio with 0xFE disables an already-running microphone.
        report[4] = if (microphoneEnabled) 0xff.toByte() else 0xfe.toByte()
        // Each native haptics frame below is 64 bytes. Advertising 48 here
        // makes the controller consume a truncated/attenuated haptics block.
        report.fill(latencyScale.coerceIn(0, 0xff).toByte(), 5, 10)
        report[10] = packetCounter.toByte()
        if (speakerOpus != null) {
            // 0x13 is the internal speaker stream; 0x16 is the physical
            // headset-jack stream. Both are framed as a sized 0x80 subpacket.
            report[11] = if (headsetRoute) 0x96.toByte() else 0x93.toByte()
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

    /**
     * Arms the native Bluetooth microphone without submitting speaker media.
     *
     * This is the control-only 0x36 transport used by the known-good
     * DS5Dongle microphone implementation: audio-control bit 0 enables the
     * uplink, a complete SetStateData block keeps the controller state valid,
     * and the silent haptics block deliberately has no speaker sub-report.
     * The caller sends this only while waiting for incoming microphone frames.
     */
    fun buildMicrophoneArm(sequence: Int, packetCounter: Int,
                           config: DualSenseOutputConfig,
                           headsetRoute: Boolean): ByteArray {
        val report = ByteArray(398)
        report[0] = 0x36
        report[1] = ((sequence and 0x0f) shl 4).toByte()
        report[2] = 0x91.toByte()
        report[3] = 7
        report[4] = 0xff.toByte() // audio path plus microphone-enable (bit 0)
        report.fill(64, 5, 10)
        report[10] = packetCounter.toByte()

        // Reuse the exact current output-state encoding instead of emitting a
        // synthetic LED/trigger/motor snapshot. The 0x31 payload after its
        // report header is the 63-byte SetStateData structure expected here.
        val state = DualSenseBtOutputBuilder.build(
            config, sequence, nativeBluetoothAudio = true, headsetRoute = headsetRoute
        )
        // The controller's wireless microphone transport is sensitive to the
        // audio portion of SetStateData. Keep every host-owned field from the
        // current merged state (LEDs, player indicators, triggers and rumble),
        // but apply the known-good duplex profile used by DS5Dongle. In
        // particular, do not inherit the normal report's zero headphone volume
        // or its speaker-only preamp profile into a microphone arm packet.
        //
        // These offsets are relative to the 63-byte SetStateData block, which
        // starts at byte 3 in the regular 0x31 Bluetooth output report.
        state[7] = HEADSET_VOLUME_MAX.toByte()       // state[4]: headphone volume
        state[8] = SPEAKER_VOLUME_SAFE.toByte()      // state[5]: speaker volume
        state[9] = MICROPHONE_VOLUME.toByte()        // state[6]: microphone volume
        state[10] = DUPLEX_AUDIO_CONTROL.toByte()    // state[7]: audio control
        state[42] = SPEAKER_PREAMP_ENABLED.toByte()  // state[39]: speaker preamp
        state[43] = DUPLEX_AUDIO_CONTROL_2.toByte()  // state[40]: audio control 2
        report[11] = 0x90.toByte()
        report[12] = 63
        state.copyInto(report, destinationOffset = 13, startIndex = 3, endIndex = 66)

        report[76] = 0x92.toByte()
        report[77] = HAPTICS_BYTES_PER_REPORT.toByte()
        // report[78..141] intentionally stays zero: silent haptics, no speaker packet.
        fillBluetoothCrc(report)
        return report
    }

    /**
     * Full wireless audio state packet. Bit 0 of the 0x11 config mask enables
     * the controller's Opus microphone duplex stream. It is deliberately sent
     * separately from game feedback so mic state never overwrites LEDs,
     * triggers, or rumble in the regular 0x31 output report.
     */
    fun buildDuplexSetup(sequence: Int, microphoneEnabled: Boolean,
                         headsetRoute: Boolean): ByteArray {
        val report = ByteArray(398)
        report[0] = 0x36
        report[1] = ((sequence and 0x0f) shl 4).toByte()
        report[2] = 0x91.toByte() // 0x11 config sub-packet, sized
        report[3] = 7
        report[4] = if (microphoneEnabled) 0xff.toByte() else 0xfe.toByte()
        report[5] = 64
        report[6] = 64
        report[7] = 64
        report[8] = 64
        report[9] = 64
        report[10] = 0
        // Audio-only snapshot. This runs on jack hotplug, so it must not claim
        // player LED, lightbar, trigger, or rumble fields owned by host output.
        val snapshot = byteArrayOf(
            (if (microphoneEnabled) 0xe0 else 0xa0).toByte(), 0x80.toByte(), 0x00, 0x00,
            HEADSET_VOLUME_MAX.toByte(),
            (if (headsetRoute) SPEAKER_VOLUME_SAFE else LEGACY_SPEAKER_VOLUME).toByte(),
            (if (microphoneEnabled) 0x40 else 0x00),
            if (headsetRoute) 0x10 else LEGACY_SPEAKER_AUDIO_CONTROL.toByte(),
            0x00, if (microphoneEnabled) 0x00 else 0x10, 0x00, 0x00, 0x00, 0x00,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0,
            (if (headsetRoute) SPEAKER_PREAMP_SAFE else LEGACY_SPEAKER_PREAMP).toByte(),
            0, 0, 0, 0, 0, 0, 0
        )
        report[11] = 0x90.toByte() // 0x10 state snapshot, sized
        report[12] = snapshot.size.toByte()
        snapshot.copyInto(report, 13)
        if (microphoneEnabled) {
            report[19] = 0x40 // microphone volume
            report[22] = 0x00 // clear microphone power-save/mute bit
        }
        // A complete 0x36 container has a 64-byte haptics frame at offset 76
        // and a 200-byte speaker frame at offset 142. The former implementation
        // put a speaker tag at offset 76, creating an invalid container that
        // could leave the controller audio engine in a different state after
        // every jack hotplug.
        report[76] = 0x92.toByte()
        report[77] = 64
        report[142] = 0x93.toByte()
        report[143] = 200.toByte()
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
    private const val HEADSET_VOLUME_MAX = 0x7f
    private const val SPEAKER_VOLUME_SAFE = 0x64
    private const val MICROPHONE_VOLUME = 0x40
    private const val DUPLEX_AUDIO_CONTROL = 0x09
    private const val SPEAKER_PREAMP_ENABLED = 0x0a
    private const val DUPLEX_AUDIO_CONTROL_2 = 0x07
    private const val LEGACY_SPEAKER_VOLUME = 0x7f
    private const val LEGACY_SPEAKER_AUDIO_CONTROL = 0xff
    private const val SPEAKER_PREAMP_SAFE = 0x02
    private const val LEGACY_SPEAKER_PREAMP = 0x07

    private fun fillBluetoothCrc(report: ByteArray) {
        val crc = CRC32()
        crc.update(0xa2)
        crc.update(report, 0, report.size - 4)
        val value = crc.value
        report[report.size - 4] = value.toByte()
        report[report.size - 3] = (value ushr 8).toByte()
        report[report.size - 2] = (value ushr 16).toByte()
        report[report.size - 1] = (value ushr 24).toByte()
    }
}
