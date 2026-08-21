// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.limelight.dualsense

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

object DirectDualSenseBtAudioHapticsBuilder
{
    const val REPORT_PERIOD_NANOS = 10_666_666L
    const val REPORT_SIZE = 142
    private const val SEQUENCE_OFFSET = 10
    private const val CRC_OFFSET = 138
    const val SAMPLE_BYTES = 64
    private const val FRAMES_PER_REPORT = SAMPLE_BYTES / 2
    private const val SAMPLE_RATE = 3000.0
    // DSX method: OR this flag into byte[2] of the 0x32 haptics report to route audio to the speaker simultaneously.
    private const val SPEAKER_ROUTE_FLAG = 0x82
    // Speaker volume in byte[6] of the 0x32 report (DSX maps 50-127; 0x7C matches the 0x31 state report default).
    const val DEFAULT_SPEAKER_VOLUME = 0x7C
    private const val DEMO_DURATION_SECONDS = 10.0
    private val CLIP_LEFT = buildClipChannel(phaseOffset = 0.0)
    private val CLIP_RIGHT = buildClipChannel(phaseOffset = PI / 5.0)

    fun buildToneReport(sequence: Int): ByteArray = buildReport(sequence) { frameIndex ->
        val sample = generateSine(frameIndex = frameIndex, frequencyHz = 90.0, amplitude = 110.0)
        sample to sample
    }

    fun buildPulseReport(sequence: Int): ByteArray = buildReport(sequence) { frameIndex ->
        val sample = generatePulse(frameIndex = frameIndex, frequencyHz = 55.0, amplitude = 120)
        sample to sample
    }

    fun buildClipReport(sequence: Int): ByteArray = buildReport(sequence) { frameIndex ->
        val index = frameIndex % CLIP_LEFT.size
        CLIP_LEFT[index] to CLIP_RIGHT[index]
    }

    fun buildContinuousDemoReports(): List<ByteArray>
    {
        val totalFrames = (SAMPLE_RATE * DEMO_DURATION_SECONDS).roundToInt()
        val reports = ArrayList<ByteArray>((totalFrames + FRAMES_PER_REPORT - 1) / FRAMES_PER_REPORT)
        var sequence = 0
        var frameIndex = 0
        while(frameIndex < totalFrames)
        {
            val packed = ByteArray(SAMPLE_BYTES)
            for(i in 0 until FRAMES_PER_REPORT)
            {
                val absoluteFrame = frameIndex + i
                val left = generateHum(frameIndex = absoluteFrame, phaseOffset = 0.0)
                val right = generateHum(frameIndex = absoluteFrame, phaseOffset = PI / 8.0)
                packed[i * 2] = left
                packed[i * 2 + 1] = right
            }
            reports += buildPackedReport(sequence, packed)
            frameIndex += FRAMES_PER_REPORT
            sequence = (sequence + 1) and 0xFF
        }
        return reports
    }

    fun buildVerificationDemoReports(durationSeconds: Double = DEMO_DURATION_SECONDS): List<ByteArray>
    {
        val totalFrames = (SAMPLE_RATE * durationSeconds).roundToInt().coerceAtLeast(FRAMES_PER_REPORT)
        val totalReports = (totalFrames + FRAMES_PER_REPORT - 1) / FRAMES_PER_REPORT
        val reports = ArrayList<ByteArray>(totalReports)
        var sequence = 0
        var frameIndex = 0
        while(frameIndex < totalFrames)
        {
            val packed = ByteArray(SAMPLE_BYTES)
            for(i in 0 until FRAMES_PER_REPORT)
            {
                val absoluteFrame = frameIndex + i
                val left = generateVerificationSweep(
                    frameIndex = absoluteFrame,
                    totalFrames = totalFrames,
                    phaseOffset = 0.0,
                )
                val right = generateVerificationSweep(
                    frameIndex = absoluteFrame,
                    totalFrames = totalFrames,
                    phaseOffset = PI / 7.0,
                )
                packed[i * 2] = left
                packed[i * 2 + 1] = right
            }
            reports += buildPackedReport(sequence, packed)
            frameIndex += FRAMES_PER_REPORT
            sequence = (sequence + 1) and 0xFF
        }
        return reports
    }

    fun buildPureToneLoopReports(
        durationSeconds: Double = 1.0,
        frequencyHz: Double = 300.0,
        amplitude: Int = 100,
    ): List<ByteArray>
    {
        val totalFrames = (SAMPLE_RATE * durationSeconds).roundToInt().coerceAtLeast(FRAMES_PER_REPORT)
        val totalReports = (totalFrames + FRAMES_PER_REPORT - 1) / FRAMES_PER_REPORT
        val reports = ArrayList<ByteArray>(totalReports)
        var sequence = 0
        var frameIndex = 0
        while(frameIndex < totalFrames)
        {
            val packed = ByteArray(SAMPLE_BYTES)
            for(i in 0 until FRAMES_PER_REPORT)
            {
                val absoluteFrame = frameIndex + i
                val sample = generateSine(
                    frameIndex = absoluteFrame,
                    frequencyHz = frequencyHz,
                    amplitude = amplitude.toDouble(),
                )
                packed[i * 2] = sample
                packed[i * 2 + 1] = sample
            }
            reports += buildPackedReport(sequence, packed)
            frameIndex += FRAMES_PER_REPORT
            sequence = (sequence + 1) and 0xFF
        }
        return reports
    }

    fun buildSampleReport(
        sequence: Int,
        interleavedSamples: ByteArray,
        routeSpeaker: Boolean = false,
        speakerVolume: Int = DEFAULT_SPEAKER_VOLUME,
    ): ByteArray = ByteArray(REPORT_SIZE).also { report ->
        writeSampleReport(report, sequence, interleavedSamples, interleavedSamples.size, routeSpeaker, speakerVolume)
    }

    fun buildSilenceReport(
        sequence: Int,
        routeSpeaker: Boolean = false,
        speakerVolume: Int = DEFAULT_SPEAKER_VOLUME,
    ): ByteArray = ByteArray(REPORT_SIZE).also { report ->
        writeSilenceReport(report, sequence, routeSpeaker, speakerVolume)
    }

    fun buildMicControlReport(sequence: Int, open: Boolean): ByteArray =
        buildSilenceReport(sequence).also { report ->
            report[4] = if(open) 0xFF.toByte() else 0xFE.toByte()
            val checksum = DirectDualSenseBtCrc.compute(report, CRC_OFFSET)
            report[CRC_OFFSET + 0] = (checksum and 0xFF).toByte()
            report[CRC_OFFSET + 1] = ((checksum ushr 8) and 0xFF).toByte()
            report[CRC_OFFSET + 2] = ((checksum ushr 16) and 0xFF).toByte()
            report[CRC_OFFSET + 3] = ((checksum ushr 24) and 0xFF).toByte()
        }

    fun writeSampleReport(
        report: ByteArray,
        sequence: Int,
        interleavedSamples: ByteArray,
        sampleLength: Int = interleavedSamples.size,
        routeSpeaker: Boolean = false,
        speakerVolume: Int = DEFAULT_SPEAKER_VOLUME,
    )
    {
        require(report.size == REPORT_SIZE) { "Unexpected BT haptics report size: ${report.size}" }
        require(sampleLength in 0..minOf(interleavedSamples.size, SAMPLE_BYTES)) {
            "Audio haptics sample payload must be between 0 and $SAMPLE_BYTES bytes"
        }
        writePackedReport(report, sequence, interleavedSamples, sampleLength, routeSpeaker, speakerVolume)
    }

    fun writeSilenceReport(
        report: ByteArray,
        sequence: Int,
        routeSpeaker: Boolean = false,
        speakerVolume: Int = DEFAULT_SPEAKER_VOLUME,
    )
    {
        writePackedReport(report, sequence, ByteArray(0), 0, routeSpeaker, speakerVolume)
    }


    fun restampSequence(
        report: ByteArray,
        sequence: Int,
        routeSpeaker: Boolean = false,
        speakerVolume: Int = DEFAULT_SPEAKER_VOLUME,
    ): ByteArray
    {
        require(report.size == REPORT_SIZE) { "Unexpected BT haptics report size: ${report.size}" }
        val stamped = report.copyOf()
        // DSX/probe keeps the plain 0x32 audio-haptics lane at a fixed tag byte.
        // Controller-data/state packets own the shared output sequence; the plain
        // haptics lane does not. Advancing the shared tag here lets haptics traffic
        // interfere with interleaved 0x32 controller-data packets.
        stamped[1] = 0x00
        stamped[2] = (0x91 or if(routeSpeaker) SPEAKER_ROUTE_FLAG else 0).toByte()
        // DSX's plain Bluetooth audio-haptics packet keeps these header bytes at 0x00.
        stamped[5] = 0x00
        stamped[6] = if(routeSpeaker) speakerVolume.coerceIn(0, 0x7F).toByte() else 0x00
        stamped[7] = 0x00
        stamped[8] = 0x00
        stamped[SEQUENCE_OFFSET] = (sequence and 0xFF).toByte()
        val checksum = DirectDualSenseBtCrc.compute(stamped, CRC_OFFSET)
        stamped[CRC_OFFSET + 0] = (checksum and 0xFF).toByte()
        stamped[CRC_OFFSET + 1] = ((checksum ushr 8) and 0xFF).toByte()
        stamped[CRC_OFFSET + 2] = ((checksum ushr 16) and 0xFF).toByte()
        stamped[CRC_OFFSET + 3] = ((checksum ushr 24) and 0xFF).toByte()
        return stamped
    }

    private fun buildReport(sequence: Int, sampleProvider: (Int) -> Pair<Byte, Byte>): ByteArray
    {
        val packed = ByteArray(SAMPLE_BYTES)
        val baseFrame = sequence * FRAMES_PER_REPORT
        for(i in 0 until FRAMES_PER_REPORT)
        {
            val (left, right) = sampleProvider(baseFrame + i)
            packed[i * 2] = left
            packed[i * 2 + 1] = right
        }
        return buildPackedReport(sequence, packed)
    }

    private fun buildPackedReport(sequence: Int, packedSamples: ByteArray): ByteArray = ByteArray(REPORT_SIZE).also { report ->
        writePackedReport(report, sequence, packedSamples, packedSamples.size, routeSpeaker = false, speakerVolume = DEFAULT_SPEAKER_VOLUME)
    }

    private fun writePackedReport(
        report: ByteArray,
        sequence: Int,
        packedSamples: ByteArray,
        sampleLength: Int,
        routeSpeaker: Boolean,
        speakerVolume: Int,
    )
    {
        report.fill(0)
        report[0] = 0x32
        // Plain 0x32 haptics reports use a fixed tag byte in the probe reference.
        report[1] = 0x00
        report[2] = (0x91 or if(routeSpeaker) SPEAKER_ROUTE_FLAG else 0).toByte()
        report[3] = 0x07
        report[4] = 0xFE.toByte()
        // DSX BuildBluetoothAudioHapticsReport: bytes 5..8 stay zero for the plain
        // 0x32 audio-haptics packet family.
        report[5] = 0x00
        report[6] = if(routeSpeaker) speakerVolume.coerceIn(0, 0x7F).toByte() else 0x00
        report[7] = 0x00
        report[8] = 0x00
        report[9] = 0xFF.toByte()
        report[10] = (sequence and 0xFF).toByte()
        report[11] = 0x92.toByte()
        report[12] = 0x40
        if(sampleLength > 0)
            System.arraycopy(packedSamples, 0, report, 13, sampleLength)

        val checksum = DirectDualSenseBtCrc.compute(report, CRC_OFFSET)
        report[CRC_OFFSET + 0] = (checksum and 0xFF).toByte()
        report[CRC_OFFSET + 1] = ((checksum ushr 8) and 0xFF).toByte()
        report[CRC_OFFSET + 2] = ((checksum ushr 16) and 0xFF).toByte()
        report[CRC_OFFSET + 3] = ((checksum ushr 24) and 0xFF).toByte()
    }

    private fun generateSine(frameIndex: Int, frequencyHz: Double, amplitude: Double): Byte
    {
        val phase = 2.0 * PI * frequencyHz * frameIndex / SAMPLE_RATE
        val sample = sin(phase) * amplitude
        return sample.roundToInt().coerceIn(-127, 127).toByte()
    }

    private fun generateHum(frameIndex: Int, phaseOffset: Double): Byte
    {
        val time = frameIndex / SAMPLE_RATE
        val slowWobble = 0.88 + 0.12 * sin(2.0 * PI * 0.35 * time)
        val body = sin(2.0 * PI * 76.0 * time + phaseOffset) * 0.78
        val texture = sin(2.0 * PI * 114.0 * time + phaseOffset * 0.7) * 0.17
        val low = sin(2.0 * PI * 38.0 * time) * 0.11
        val sample = (body + texture + low) * slowWobble * 118.0
        return sample.roundToInt().coerceIn(-127, 127).toByte()
    }

    private fun generatePulse(frameIndex: Int, frequencyHz: Double, amplitude: Int): Byte
    {
        val periodFrames = (SAMPLE_RATE / frequencyHz).roundToInt().coerceAtLeast(1)
        val position = (frameIndex % periodFrames).toDouble() / periodFrames
        val sample = when {
            position < 0.16 -> amplitude
            position < 0.28 -> -amplitude / 2
            position < 0.38 -> amplitude / 3
            else -> 0
        }
        return sample.coerceIn(-127, 127).toByte()
    }

    private fun generateVerificationSweep(frameIndex: Int, totalFrames: Int, phaseOffset: Double): Byte
    {
        val progress = (frameIndex.toDouble() / totalFrames).coerceIn(0.0, 1.0)
        val sweepCycle = 0.5 - 0.5 * kotlin.math.cos(2.0 * PI * progress)
        val baseFrequency = 28.0 + sweepCycle * 130.0
        val shimmerFrequency = baseFrequency * 1.65
        val subFrequency = (baseFrequency * 0.48).coerceAtLeast(12.0)
        val time = frameIndex / SAMPLE_RATE
        val envelope = 0.86 + 0.14 * sin(2.0 * PI * 0.42 * time)
        val body = sin(2.0 * PI * baseFrequency * time + phaseOffset) * 0.72
        val shimmer = sin(2.0 * PI * shimmerFrequency * time + phaseOffset * 0.8) * 0.18
        val sub = sin(2.0 * PI * subFrequency * time + phaseOffset * 0.35) * 0.10
        val sample = (body + shimmer + sub) * envelope * 121.0
        return sample.roundToInt().coerceIn(-127, 127).toByte()
    }

    private fun buildClipChannel(phaseOffset: Double): ByteArray
    {
        val frameCount = 240
        return ByteArray(frameCount) { index ->
            val time = index / SAMPLE_RATE
            val attack = (index / 18.0).coerceAtMost(1.0)
            val decay = (1.0 - (index / frameCount.toDouble())).coerceAtLeast(0.0)
            val envelope = attack * decay
            val body = sin(2.0 * PI * 78.0 * time + phaseOffset) * 0.72
            val texture = sin(2.0 * PI * 146.0 * time + phaseOffset * 0.6) * 0.22
            val thump = sin(2.0 * PI * 34.0 * time) * 0.18
            val sample = (body + texture + thump) * envelope * 122.0
            sample.roundToInt().coerceIn(-127, 127).toByte()
        }
    }
}

