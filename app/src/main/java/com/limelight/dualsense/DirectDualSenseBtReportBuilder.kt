// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.limelight.dualsense

object DirectDualSenseBtReportBuilder
{
    data class PersistentOutputState(
        val playerLedBrightness: Int,
        val playerLedMask: Int,
        val triggerSoftnessLevel: Int,
        val softRumbleReduce: Int,
        val lightbarRed: Int,
        val lightbarGreen: Int,
        val lightbarBlue: Int,
    )

    data class PersistentVisualState(
        val playerLedBrightness: Int,
        val playerLedMask: Int,
        val lightbarRed: Int,
        val lightbarGreen: Int,
        val lightbarBlue: Int,
    )

    private const val REPORT_SIZE = 78
    private const val PAYLOAD_OFFSET = 2
    private const val CRC_OFFSET = 0x4A
    private const val REGULAR_BT_FLAG_INDEX = 40
    private const val MOTOR_RIGHT_INDEX = PAYLOAD_OFFSET + 2
    private const val MOTOR_LEFT_INDEX = PAYLOAD_OFFSET + 3
    private const val AUDIO_HEADSET_VOLUME_INDEX = PAYLOAD_OFFSET + 4
    private const val AUDIO_SPEAKER_VOLUME_INDEX = PAYLOAD_OFFSET + 5
    private const val AUDIO_MIC_VOLUME_INDEX = PAYLOAD_OFFSET + 6
    private const val AUDIO_MODE_INDEX = PAYLOAD_OFFSET + 7
    private const val AUDIO_MIC_FLAG_INDEX = PAYLOAD_OFFSET + 8
    private const val AUDIO_POWER_SAVE_INDEX = PAYLOAD_OFFSET + 9
    private const val TRIGGER_STATE_OFFSET = PAYLOAD_OFFSET + 10
    private const val TRIGGER_STATE_LENGTH = 22
    private const val REGULAR_LED_READY_INDEX = PAYLOAD_OFFSET + 40
    private const val PLAYER_LED_BRIGHTNESS_INDEX = PAYLOAD_OFFSET + 42
    private const val PLAYER_LED_MASK_INDEX = PAYLOAD_OFFSET + 43
    private const val FEATURE_REDUCE_INDEX = PAYLOAD_OFFSET + 36
    private const val LIGHTBAR_RED_INDEX = PAYLOAD_OFFSET + 44
    private const val LIGHTBAR_GREEN_INDEX = PAYLOAD_OFFSET + 45
    private const val LIGHTBAR_BLUE_INDEX = PAYLOAD_OFFSET + 46
    private const val LIGHTBAR_TAIL_INDEX = PAYLOAD_OFFSET + 47
    private const val DSX_BLUETOOTH_LIGHTBAR_SETUP_WARMUP = 0x01
    private const val DSX_BLUETOOTH_LIGHTBAR_SETUP_READY = 0x02
    private const val DSX_BLUETOOTH_REGULAR_LIGHTBAR_TAIL_MARKER = 0x03
    private const val COMBINED_ONE_FRAME_REPORT_SIZE = 398
    const val COMBINED_CONTROLLER_HAPTICS_REPORT_SIZE = COMBINED_ONE_FRAME_REPORT_SIZE
    private const val COMBINED_ONE_FRAME_AUDIO_CONFIG_OFFSET = 67
    private const val COMBINED_ONE_FRAME_OPUS_OFFSET = 78
    private const val COMBINED_ONE_FRAME_OPUS_BYTES = 200
    private const val COMBINED_ONE_FRAME_HAPTICS_TAIL_OFFSET = 278
    private const val COMBINED_ONE_FRAME_HAPTICS_TAIL_LENGTH = 116
    private const val COMBINED_ONE_FRAME_CRC_OFFSET = 394
    private const val DIRECT_ONE_FRAME_AUDIO_CONFIG_OFFSET = 2
    private const val DIRECT_ONE_FRAME_HAPTICS_HEADER_OFFSET = 11
    private const val DIRECT_ONE_FRAME_HAPTICS_OFFSET = 13
    private const val DIRECT_ONE_FRAME_OPUS_CONFIG_OFFSET = 77
    private const val DIRECT_ONE_FRAME_OPUS_OFFSET = 79
    const val TWO_FRAME_HAPTICS_CATCHUP_REPORT_SIZE = 547
    private const val TWO_FRAME_HAPTICS_CATCHUP_AUDIO_CONFIG_OFFSET = 2
    private const val TWO_FRAME_HAPTICS_CATCHUP_OPUS_OFFSET = 13
    private const val TWO_FRAME_HAPTICS_CATCHUP_OPUS_BYTES = 400
    private const val TWO_FRAME_HAPTICS_CATCHUP_TAIL_OFFSET = 413
    private const val TWO_FRAME_HAPTICS_CATCHUP_TAIL_LENGTH = 130
    private const val TWO_FRAME_HAPTICS_CATCHUP_CRC_OFFSET = 543
    private const val TWO_FRAME_HAPTICS_CATCHUP_MODE = 0xD5
    private const val AUDIO_STATE_CARRIER_REPORT_SIZE = 334
    private const val AUDIO_STATE_CARRIER_AUDIO_CONFIG_OFFSET = 2
    private const val AUDIO_STATE_CARRIER_OPUS_OFFSET = 13
    private const val AUDIO_STATE_CARRIER_CONTROLLER_OFFSET = 213
    private const val AUDIO_STATE_CARRIER_CRC_OFFSET = 330
    // DS5Dongle's working Classic BT 0x36 haptics report uses 0x30 in the five
    // audio buffer/depth bytes. Android's BluetoothHidHost path adds more delivery
    // jitter than the dongle, so test 0x60: DSX/Windows maps latencyScale/12 to
    // internal frame prefill, making 0x30 ~= 4 frames and 0x60 ~= 8 frames.
    private const val COMBINED_ONE_FRAME_LATENCY_SCALE = 0x80
    private const val COMBINED_ONE_FRAME_SPEAKER_MODE = 0x93 // speaker route (0x13|0x80)
    private const val COMBINED_ONE_FRAME_HEADSET_MODE = 0x96 // headset stereo route (0x16|0x80)
    private const val COMBINED_ONE_FRAME_MODE = COMBINED_ONE_FRAME_SPEAKER_MODE
    private const val COMBINED_ONE_FRAME_HAPTICS_ONLY_MODE = 0x96 // headset/non-speaker route (0x16|0x80)
    private const val CONTROLLER_DATA_TAIL_CLEAR_LENGTH = 117
    private const val DEFAULT_AUDIO_MODE = 0x05
    private const val DEFAULT_AUDIO_VOLUME = 0x7C
    private const val SPEAKER_REGULAR_BT_PREP_FLAGS = 0x07
    private const val SPEAKER_ROUTE_DEFAULT_AUDIO_CONTROL = 0x30
    private const val SPEAKER_ROUTE_DEFAULT_AUDIO_CONTROL2 = 0x00
    private const val SPEAKER_ROUTE_DEFAULT_HEADSET_VOLUME = 0x00
    private const val SPEAKER_ROUTE_DEFAULT_SPEAKER_VOLUME = 0xA0
    private const val SPEAKER_ROUTE_DEFAULT_MIC_VOLUME = 0x00
    private const val TESTER_ROUTINE_VALID_FLAG0 = 0x05
    private const val TESTER_PRIME_VALID_FLAG0 = 0xFF
    private const val CRC_SEED = 0xEADA2D49.toInt()
    const val LIGHTBAR_SWEEP_VARIANT_CURRENT = 0
    const val LIGHTBAR_SWEEP_VARIANT_CURRENT_NO_INIT = 1
    const val LIGHTBAR_SWEEP_VARIANT_SHIFTED_07 = 2
    const val LIGHTBAR_SWEEP_VARIANT_SHIFTED_03 = 3
    const val LIGHTBAR_SWEEP_VARIANT_MULTIPLATFORM_REFRESH = 4
    // TriggerEffectGenerator.Off() sets byte[0] = 0x05 — the hardware "no resistance" mode code.
    // Sending 0x00 leaves the mode byte undefined; on some firmware the trigger stays
    // partially actuated. Use 0x05 to match DSX exactly.
    private val CLEAR_TRIGGER = ByteArray(10).also { it[0] = 0x05 }

    // Speaker volume 0-100%, maps to hardware units 50-127 via mapSpeakerVolumePercentToDsxByte.
    // Written into every controller-data subpacket; updated from Preferences when the
    // stream starts or when the user changes the slider in Settings.
    @Volatile var speakerVolumePercent: Int = 100
    /** Volume 0-100% for the 3.5 mm headphone jack output; independent of speaker volume. */
    @Volatile var headphoneVolumePercent: Int = 100
    /** True when audio should route to the 3.5 mm headphone jack; false = internal speaker. */
    @Volatile var headphoneMode: Boolean = false

    private fun currentOneFrameAudioMode(): Int =
        if(headphoneMode) COMBINED_ONE_FRAME_HEADSET_MODE else COMBINED_ONE_FRAME_SPEAKER_MODE
    private val STRONG_RESISTANCE_TRIGGER = createResistanceTrigger(startZone = 0x28, strength = 0xFF)
    private val CLICK_TRIGGER = createGameCubeTrigger()
    @Volatile private var persistentPlayerLedBrightness = 0x00
    @Volatile private var persistentPlayerLedMask = 0x04
    @Volatile private var persistentTriggerSoftnessLevel = 0x00
    @Volatile private var persistentSoftRumbleReduce = 0x00
    @Volatile private var persistentLightbarRed = 0x00
    @Volatile private var persistentLightbarGreen = 0x00
    @Volatile private var persistentLightbarBlue = 0x00
    @Volatile private var outputSeqTag = 0x00
    @Volatile private var singleFrameAudioCounter = 0x00

    private fun normalizePlayerLedMask(mask: Int): Int
    {
        val value = mask and 0x3F
        if(value == 0)
            return 0

        return when(value)
        {
            0x02, 0x04, 0x05, 0x0A, 0x0B, 0x14, 0x15, 0x1B,
            0x21, 0x22, 0x24, 0x25, 0x2A, 0x2B, 0x34, 0x35, 0x3B -> value
            else -> value or 0x20
        }
    }

    fun setPersistentLightbar(red: Int, green: Int, blue: Int)
    {
        persistentLightbarRed = red.coerceIn(0, 255)
        persistentLightbarGreen = green.coerceIn(0, 255)
        persistentLightbarBlue = blue.coerceIn(0, 255)
    }

    fun clearPersistentLightbar()
    {
        setPersistentLightbar(0, 0, 0)
    }

    fun setPersistentPlayerLeds(mask: Int, brightness: Int)
    {
        persistentPlayerLedMask = normalizePlayerLedMask(mask)
        persistentPlayerLedBrightness = brightness.coerceIn(0, 0xFF)
    }

    fun setPersistentDualSenseIntensity(triggerReduce: Int, rumbleReduce: Int)
    {
        persistentTriggerSoftnessLevel = triggerReduce.coerceIn(0, 0x0F)
        persistentSoftRumbleReduce = rumbleReduce.coerceIn(0, 0x0F)
    }

    fun snapshotPersistentOutputState(): PersistentOutputState =
        PersistentOutputState(
            playerLedBrightness = persistentPlayerLedBrightness,
            playerLedMask = persistentPlayerLedMask,
            triggerSoftnessLevel = persistentTriggerSoftnessLevel,
            softRumbleReduce = persistentSoftRumbleReduce,
            lightbarRed = persistentLightbarRed,
            lightbarGreen = persistentLightbarGreen,
            lightbarBlue = persistentLightbarBlue,
        )

    fun snapshotPersistentVisualState(): PersistentVisualState =
        PersistentVisualState(
            playerLedBrightness = persistentPlayerLedBrightness,
            playerLedMask = persistentPlayerLedMask,
            lightbarRed = persistentLightbarRed,
            lightbarGreen = persistentLightbarGreen,
            lightbarBlue = persistentLightbarBlue,
        )

    fun restorePersistentVisualState(state: PersistentVisualState)
    {
        persistentPlayerLedBrightness = state.playerLedBrightness.coerceIn(0, 0xFF)
        persistentPlayerLedMask = normalizePlayerLedMask(state.playerLedMask)
        persistentLightbarRed = state.lightbarRed.coerceIn(0, 255)
        persistentLightbarGreen = state.lightbarGreen.coerceIn(0, 255)
        persistentLightbarBlue = state.lightbarBlue.coerceIn(0, 255)
    }

    fun restorePersistentOutputState(state: PersistentOutputState)
    {
        persistentPlayerLedBrightness = state.playerLedBrightness.coerceIn(0, 0xFF)
        persistentPlayerLedMask = normalizePlayerLedMask(state.playerLedMask)
        persistentTriggerSoftnessLevel = state.triggerSoftnessLevel.coerceIn(0, 0x0F)
        persistentSoftRumbleReduce = state.softRumbleReduce.coerceIn(0, 0x0F)
        persistentLightbarRed = state.lightbarRed.coerceIn(0, 255)
        persistentLightbarGreen = state.lightbarGreen.coerceIn(0, 255)
        persistentLightbarBlue = state.lightbarBlue.coerceIn(0, 255)
    }

    fun resetStreamingState()
    {
        outputSeqTag = 0x00
        singleFrameAudioCounter = 0x00
    }

    fun buildPrimeReport(
        state: PersistentOutputState,
        triggerStateReport: ByteArray? = null,
    ): ByteArray = buildStatefulReport(
        state = state,
        featureMode = 0xFF,
        regularBtOutput = false,
        speakerRegularBtOutput = false,
        triggerStateReport = triggerStateReport,
    )

    fun buildPrimeReport(triggerStateReport: ByteArray? = null): ByteArray =
        buildPrimeReport(snapshotPersistentOutputState(), triggerStateReport)

    fun buildPrimeReportWithFeatureMode(
        state: PersistentOutputState,
        featureMode: Int,
        triggerStateReport: ByteArray? = null,
    ): ByteArray = buildStatefulReport(
        state = state,
        featureMode = featureMode,
        regularBtOutput = false,
        speakerRegularBtOutput = false,
        triggerStateReport = triggerStateReport,
    )

    fun buildPrimeReportWithFeatureMode(featureMode: Int, triggerStateReport: ByteArray? = null): ByteArray =
        buildPrimeReportWithFeatureMode(snapshotPersistentOutputState(), featureMode, triggerStateReport)

    fun buildStateReport(
        state: PersistentOutputState,
        triggerStateReport: ByteArray? = null,
        speakerRegularBtOutput: Boolean = false,
        ledReady: Boolean = false,
    ): ByteArray = buildStatefulReport(
        state = state,
        featureMode = 0x57,
        regularBtOutput = true,
        speakerRegularBtOutput = speakerRegularBtOutput,
        ledReady = ledReady,
        triggerStateReport = triggerStateReport,
    )

    fun buildStateReport(
        triggerStateReport: ByteArray? = null,
        speakerRegularBtOutput: Boolean = false,
        ledReady: Boolean = false,
    ): ByteArray =
        buildStateReport(snapshotPersistentOutputState(), triggerStateReport, speakerRegularBtOutput, ledReady)

    fun buildStateReportWithFeatureMode(
        state: PersistentOutputState,
        featureMode: Int,
        triggerStateReport: ByteArray? = null,
        speakerRegularBtOutput: Boolean = false,
        ledReady: Boolean = false,
    ): ByteArray = buildStatefulReport(
        state = state,
        featureMode = featureMode,
        regularBtOutput = true,
        speakerRegularBtOutput = speakerRegularBtOutput,
        ledReady = ledReady,
        triggerStateReport = triggerStateReport,
    )

    fun buildStateReportWithFeatureMode(
        featureMode: Int,
        triggerStateReport: ByteArray? = null,
        speakerRegularBtOutput: Boolean = false,
        ledReady: Boolean = false,
    ): ByteArray = buildStateReportWithFeatureMode(
        snapshotPersistentOutputState(),
        featureMode,
        triggerStateReport,
        speakerRegularBtOutput,
        ledReady,
    )

    fun buildClassicRumbleReport(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        leftMotor: Int,
        rightMotor: Int,
        triggerStateReport: ByteArray? = null,
        ledReady: Boolean = false,
    ): ByteArray
    {
        val report = buildStatefulReport(
            state = state,
            featureMode = 0x57,
            regularBtOutput = true,
            speakerRegularBtOutput = false,
            ledReady = ledReady,
            triggerStateReport = triggerStateReport,
        )
        // DualSense USB output report 0x02 carries right/weak motor at payload+2 and
        // left/strong motor at payload+3. DS5Dongle wraps that same payload into BT
        // report 0x31, so keep the byte layout identical here.
        report[MOTOR_RIGHT_INDEX] = rightMotor.coerceIn(0, 255).toByte()
        report[MOTOR_LEFT_INDEX] = leftMotor.coerceIn(0, 255).toByte()
        writeChecksum(report)
        return report
    }

    fun buildRightTriggerTestReport(speakerRegularBtOutput: Boolean = false): ByteArray = buildReport(
        featureMode = 0x57,
        rightTrigger = STRONG_RESISTANCE_TRIGGER,
        leftTrigger = CLEAR_TRIGGER,
        regularBtOutput = true,
        speakerRegularBtOutput = speakerRegularBtOutput,
    )

    fun buildRightTriggerTestReport(featureMode: Int, speakerRegularBtOutput: Boolean = false): ByteArray = buildReport(
        featureMode = featureMode,
        rightTrigger = STRONG_RESISTANCE_TRIGGER,
        leftTrigger = CLEAR_TRIGGER,
        regularBtOutput = true,
        speakerRegularBtOutput = speakerRegularBtOutput,
    )

    fun buildLeftTriggerTestReport(speakerRegularBtOutput: Boolean = false): ByteArray = buildReport(
        featureMode = 0x57,
        rightTrigger = CLEAR_TRIGGER,
        leftTrigger = STRONG_RESISTANCE_TRIGGER,
        regularBtOutput = true,
        speakerRegularBtOutput = speakerRegularBtOutput,
    )

    fun buildLeftTriggerTestReport(featureMode: Int, speakerRegularBtOutput: Boolean = false): ByteArray = buildReport(
        featureMode = featureMode,
        rightTrigger = CLEAR_TRIGGER,
        leftTrigger = STRONG_RESISTANCE_TRIGGER,
        regularBtOutput = true,
        speakerRegularBtOutput = speakerRegularBtOutput,
    )

    fun buildRightTriggerClickReport(speakerRegularBtOutput: Boolean = false): ByteArray = buildReport(
        featureMode = 0x57,
        rightTrigger = CLICK_TRIGGER,
        leftTrigger = CLEAR_TRIGGER,
        regularBtOutput = true,
        speakerRegularBtOutput = speakerRegularBtOutput,
    )

    fun buildRightTriggerClickReport(featureMode: Int, speakerRegularBtOutput: Boolean = false): ByteArray = buildReport(
        featureMode = featureMode,
        rightTrigger = CLICK_TRIGGER,
        leftTrigger = CLEAR_TRIGGER,
        regularBtOutput = true,
        speakerRegularBtOutput = speakerRegularBtOutput,
    )

    fun buildLeftTriggerClickReport(speakerRegularBtOutput: Boolean = false): ByteArray = buildReport(
        featureMode = 0x57,
        rightTrigger = CLEAR_TRIGGER,
        leftTrigger = CLICK_TRIGGER,
        regularBtOutput = true,
        speakerRegularBtOutput = speakerRegularBtOutput,
    )

    fun buildLeftTriggerClickReport(featureMode: Int, speakerRegularBtOutput: Boolean = false): ByteArray = buildReport(
        featureMode = featureMode,
        rightTrigger = CLEAR_TRIGGER,
        leftTrigger = CLICK_TRIGGER,
        regularBtOutput = true,
        speakerRegularBtOutput = speakerRegularBtOutput,
    )

    fun buildRegularVisualReport(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        ledReady: Boolean = true,
        regularAudioFlags: Int = 0x03,
        outputModeSlot: Int = 0x00,
    ): ByteArray {
        val report = ByteArray(REPORT_SIZE)
        report[0] = 0x31
        report[1] = nextOutputSeqTag()
        report[PAYLOAD_OFFSET + 0] = 0x0F
        report[PAYLOAD_OFFSET + 1] = 0x55
        report[PAYLOAD_OFFSET + 9] = 0x0F
        report[PAYLOAD_OFFSET + 36] = outputModeSlot.coerceIn(0, 0xFF).toByte()
        report[10] = 0x00
        report[REGULAR_BT_FLAG_INDEX] = regularAudioFlags.coerceIn(0, 0xFF).toByte()
        report[REGULAR_LED_READY_INDEX] = resolveLightbarSetupByte(ledReady)
        report[PLAYER_LED_BRIGHTNESS_INDEX] = state.playerLedBrightness.toByte()
        report[PLAYER_LED_MASK_INDEX] = normalizePlayerLedMask(state.playerLedMask).toByte()
        report[LIGHTBAR_RED_INDEX] = state.lightbarRed.toByte()
        report[LIGHTBAR_GREEN_INDEX] = state.lightbarGreen.toByte()
        report[LIGHTBAR_BLUE_INDEX] = state.lightbarBlue.toByte()
        report[LIGHTBAR_TAIL_INDEX] = DSX_BLUETOOTH_REGULAR_LIGHTBAR_TAIL_MARKER.toByte()
        writeChecksum(report)
        return report
    }

    /**
     * One-shot Bluetooth init packet modeled after Dualsense-Multiplatform's
     * Initialize() path. This is startup-only and should not be used as the
     * recurring visual keepalive.
     */
    fun buildBluetoothInitPacket(): ByteArray {
        val report = ByteArray(REPORT_SIZE)
        report[0] = 0x31
        report[1] = 0x02
        report[PAYLOAD_OFFSET + 0] = 0xFF.toByte()
        report[PAYLOAD_OFFSET + 1] = 0xFF.toByte()
        report[40] = 0x00
        report[46] = 0x00
        report[47] = 0x00
        report[48] = 0x00
        writeChecksum(report)
        return report
    }

    fun describeLightbarSweepVariant(variant: Int): String = when (variant) {
        LIGHTBAR_SWEEP_VARIANT_CURRENT -> "current + bt init"
        LIGHTBAR_SWEEP_VARIANT_CURRENT_NO_INIT -> "current no init"
        LIGHTBAR_SWEEP_VARIANT_SHIFTED_07 -> "shifted 0x32 ctrl=0x07"
        LIGHTBAR_SWEEP_VARIANT_SHIFTED_03 -> "shifted 0x32 ctrl=0x03"
        LIGHTBAR_SWEEP_VARIANT_MULTIPLATFORM_REFRESH -> "multiplatform 0x31 refresh"
        else -> "unknown"
    }

    fun buildLightbarSweepInitPacket(variant: Int): ByteArray? = when (variant) {
        LIGHTBAR_SWEEP_VARIANT_CURRENT,
        LIGHTBAR_SWEEP_VARIANT_SHIFTED_07,
        LIGHTBAR_SWEEP_VARIANT_SHIFTED_03,
        LIGHTBAR_SWEEP_VARIANT_MULTIPLATFORM_REFRESH -> buildBluetoothInitPacket()
        else -> null
    }

    fun buildLightbarSweepControllerDataReport(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        leftTrigger: ByteArray? = null,
        rightTrigger: ByteArray? = null,
        variant: Int = LIGHTBAR_SWEEP_VARIANT_CURRENT,
    ): ByteArray = when (variant) {
        LIGHTBAR_SWEEP_VARIANT_SHIFTED_07 ->
            buildShiftedLightbarExperimentReport(state, leftTrigger, rightTrigger, 0x07)
        LIGHTBAR_SWEEP_VARIANT_SHIFTED_03 ->
            buildShiftedLightbarExperimentReport(state, leftTrigger, rightTrigger, 0x03)
        else ->
            buildControllerDataReport(state, true, leftTrigger, rightTrigger, false)
    }

    fun buildLightbarSweepVisualRefreshReport(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        variant: Int = LIGHTBAR_SWEEP_VARIANT_CURRENT,
    ): ByteArray? = when (variant) {
        LIGHTBAR_SWEEP_VARIANT_CURRENT,
        LIGHTBAR_SWEEP_VARIANT_CURRENT_NO_INIT ->
            buildRegularVisualReport(state, true, 7, 0)
        LIGHTBAR_SWEEP_VARIANT_MULTIPLATFORM_REFRESH ->
            buildMultiplatformRegularVisualReport(state)
        else -> null
    }

    private fun buildMultiplatformRegularVisualReport(
        state: PersistentOutputState = snapshotPersistentOutputState(),
    ): ByteArray {
        val report = ByteArray(REPORT_SIZE)
        report[0] = 0x31
        report[1] = 0x02
        report[PAYLOAD_OFFSET + 0] = 0xFF.toByte()
        report[PAYLOAD_OFFSET + 1] = 0x57
        report[AUDIO_HEADSET_VOLUME_INDEX] = DEFAULT_AUDIO_VOLUME.toByte()
        report[AUDIO_SPEAKER_VOLUME_INDEX] = DEFAULT_AUDIO_VOLUME.toByte()
        report[AUDIO_MIC_VOLUME_INDEX] = DEFAULT_AUDIO_VOLUME.toByte()
        report[AUDIO_MODE_INDEX] = DEFAULT_AUDIO_MODE.toByte()
        report[AUDIO_MIC_FLAG_INDEX] = 0x00
        report[AUDIO_POWER_SAVE_INDEX] = 0x00
        report[FEATURE_REDUCE_INDEX] = (((state.triggerSoftnessLevel and 0x0F) shl 4) or (state.softRumbleReduce and 0x0F)).toByte()
        report[REGULAR_BT_FLAG_INDEX] = nextRegularBtFlag()
        report[44] = state.playerLedBrightness.toByte()
        report[45] = normalizePlayerLedMask(state.playerLedMask).toByte()
        report[46] = state.lightbarRed.toByte()
        report[47] = state.lightbarGreen.toByte()
        report[48] = state.lightbarBlue.toByte()
        writeChecksum(report)
        return report
    }

    private fun buildShiftedLightbarExperimentReport(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        leftTrigger: ByteArray? = null,
        rightTrigger: ByteArray? = null,
        controlByte40: Int,
    ): ByteArray {
        val report = ByteArray(142)
        report[0] = 0x32
        report[1] = nextOutputSeqTag()
        report[2] = 0x90.toByte()
        report[3] = 0x3F
        report[4] = 0xFD.toByte()
        report[5] = 0xF7.toByte()
        report[6] = 0x00
        report[7] = 0x00
        report[8] = if (headphoneMode) mapHeadsetVolumePercentToDsxByte(headphoneVolumePercent) else 0  // routed by headphoneMode
        report[9] = if (!headphoneMode) mapSpeakerVolumePercentToDsxByte(speakerVolumePercent) else 0  // routed by headphoneMode
        report[10] = 0xFF.toByte()
        report[11] = 0x09
        report[12] = 0x00
        report[13] = 0x0F
        if (rightTrigger != null) System.arraycopy(rightTrigger, 0, report, 14, Math.min(rightTrigger.size, 10))
        if (leftTrigger != null) System.arraycopy(leftTrigger, 0, report, 25, Math.min(leftTrigger.size, 10))
        report[40] = controlByte40.toByte()
        report[41] = 0x00
        report[42] = 0x00
        report[43] = 0x01
        report[44] = state.playerLedBrightness.toByte()
        report[45] = normalizePlayerLedMask(state.playerLedMask).toByte()
        report[46] = state.lightbarRed.toByte()
        report[47] = state.lightbarGreen.toByte()
        report[48] = state.lightbarBlue.toByte()
        report[49] = 0x03
        report[50] = 0x00
        val crc = DirectDualSenseBtCrc.compute(report, 138)
        report[138] = (crc and 0xFF).toByte()
        report[139] = ((crc ushr 8) and 0xFF).toByte()
        report[140] = ((crc ushr 16) and 0xFF).toByte()
        report[141] = ((crc ushr 24) and 0xFF).toByte()
        return report
    }

    fun buildSpeakerActivationReport(state: PersistentOutputState = snapshotPersistentOutputState()): ByteArray {
        val report = ByteArray(REPORT_SIZE)
        report[0] = 0x31
        report[1] = nextOutputSeqTag()
        report[PAYLOAD_OFFSET + 0] = 0x0F               // DSX DefaultBluetoothRegularHeaderFlags (was 0xFF)
        report[PAYLOAD_OFFSET + 1] = 0x55               // DSX DefaultBluetoothRegularHeaderMode (was 0x57)
        report[PAYLOAD_OFFSET + 9] = 0x0F               // report[11] — was missing
        report[REGULAR_BT_FLAG_INDEX] = 0x07             // 0x07 only — drop 0xC0 streaming bits from prep (was 0xC7)
        report[REGULAR_LED_READY_INDEX] = 0x02
        report[REGULAR_BT_FLAG_INDEX] = SPEAKER_REGULAR_BT_PREP_FLAGS.toByte()
        report[PLAYER_LED_BRIGHTNESS_INDEX] = state.playerLedBrightness.toByte()
        report[PLAYER_LED_MASK_INDEX] = normalizePlayerLedMask(state.playerLedMask).toByte()
        report[LIGHTBAR_RED_INDEX]   = state.lightbarRed.toByte()
        report[LIGHTBAR_GREEN_INDEX] = state.lightbarGreen.toByte()
        report[LIGHTBAR_BLUE_INDEX]  = state.lightbarBlue.toByte()
        report[LIGHTBAR_TAIL_INDEX] = 0x03
        writeChecksum(report)
        return report
    }

    /**
     * Single-packet arm: collapses prime + speaker-activation into one 0x31 report.
     * payload[0] = 0xFF enables ALL field sections (audio volumes, mode, LEDs, triggers).
     * REGULAR_BT_FLAG_INDEX = 0x07 (SPEAKER_REGULAR_BT_PREP_FLAGS) sets speaker routing.
     * One packet with the right flags — no multi-step sequence needed.
     */
    fun buildCombinedArmPacket(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        triggerStateReport: ByteArray? = null,
    ): ByteArray
    {
        val report = ByteArray(REPORT_SIZE)
        report[0] = 0x31
        report[1] = nextOutputSeqTag()
        report[PAYLOAD_OFFSET + 0] = 0xFF.toByte()   // all field sections enabled
        report[PAYLOAD_OFFSET + 1] = 0x57             // extended BT mode
        report[AUDIO_HEADSET_VOLUME_INDEX] = DEFAULT_AUDIO_VOLUME.toByte()
        report[AUDIO_SPEAKER_VOLUME_INDEX] = if (!headphoneMode) mapSpeakerVolumePercentToDsxByte(speakerVolumePercent) else 0
        report[AUDIO_MIC_VOLUME_INDEX] = 0x00
        report[AUDIO_MODE_INDEX] = DEFAULT_AUDIO_MODE.toByte()
        report[AUDIO_MIC_FLAG_INDEX] = 0x00
        report[AUDIO_POWER_SAVE_INDEX] = 0x00
        report[FEATURE_REDUCE_INDEX] = (((state.triggerSoftnessLevel and 0x0F) shl 4) or (state.softRumbleReduce and 0x0F)).toByte()
        report[REGULAR_BT_FLAG_INDEX] = SPEAKER_REGULAR_BT_PREP_FLAGS.toByte()  // 0x07: speaker routing
        report[REGULAR_LED_READY_INDEX] = resolveLightbarSetupByte(true)
        report[PLAYER_LED_BRIGHTNESS_INDEX] = state.playerLedBrightness.toByte()
        report[PLAYER_LED_MASK_INDEX] = normalizePlayerLedMask(state.playerLedMask).toByte()
        report[LIGHTBAR_RED_INDEX] = state.lightbarRed.toByte()
        report[LIGHTBAR_GREEN_INDEX] = state.lightbarGreen.toByte()
        report[LIGHTBAR_BLUE_INDEX] = state.lightbarBlue.toByte()
        report[LIGHTBAR_TAIL_INDEX] = DSX_BLUETOOTH_REGULAR_LIGHTBAR_TAIL_MARKER.toByte()
        if (triggerStateReport != null && triggerStateReport.size >= TRIGGER_STATE_OFFSET + TRIGGER_STATE_LENGTH)
            System.arraycopy(triggerStateReport, TRIGGER_STATE_OFFSET, report, TRIGGER_STATE_OFFSET, TRIGGER_STATE_LENGTH)
        writeChecksum(report)
        return report
    }

    fun buildSpeakerAudioRouteReport(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        speakerVolume: Int = if (!headphoneMode) SPEAKER_ROUTE_DEFAULT_SPEAKER_VOLUME else 0,
        audioControl: Byte = if (!headphoneMode) SPEAKER_ROUTE_DEFAULT_AUDIO_CONTROL.toByte() else 0,
        audioControl2: Byte = SPEAKER_ROUTE_DEFAULT_AUDIO_CONTROL2.toByte(),
        headsetVolume: Int = if (headphoneMode) SPEAKER_ROUTE_DEFAULT_SPEAKER_VOLUME else SPEAKER_ROUTE_DEFAULT_HEADSET_VOLUME,
        micVolume: Int = SPEAKER_ROUTE_DEFAULT_MIC_VOLUME,
    ): ByteArray
    {
        val usbReport = buildUsbAudioRouteReport(
            state = state,
            headsetVolume = headsetVolume,
            speakerVolume = speakerVolume,
            micVolume = micVolume,
            audioControl = audioControl,
            audioControl2 = audioControl2,
        )
        return buildBluetoothWrappedUsbOutputReport(usbReport, flagsNibble = 0)
    }

    fun buildSpeakerActivationReportFixed(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        ledReady: Boolean = false,
    ): ByteArray = buildRegularVisualReport(
        state = state,
        ledReady = ledReady,
        regularAudioFlags = SPEAKER_REGULAR_BT_PREP_FLAGS,
        outputModeSlot = 0x00,
    )

    fun buildSpeakerAudioRouteReportFixed(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        speakerVolume: Int = SPEAKER_ROUTE_DEFAULT_SPEAKER_VOLUME,
        audioControl: Byte = SPEAKER_ROUTE_DEFAULT_AUDIO_CONTROL.toByte(),
        audioControl2: Byte = SPEAKER_ROUTE_DEFAULT_AUDIO_CONTROL2.toByte(),
        ledReady: Boolean = false,
    ): ByteArray
    {
        val report = ByteArray(REPORT_SIZE)
        report[0] = 0x31
        report[1] = nextOutputSeqTag()
        report[2] = 0x10

        val output = 3
        report[output + 0] = 0xA0.toByte()
        report[output + 1] = 0x80.toByte()
        report[output + 5] = speakerVolume.coerceIn(0, 0xFF).toByte()
        report[output + 7] = audioControl
        report[output + 37] = audioControl2
        report[42] = resolveLightbarSetupByte(ledReady)
        report[43] = state.playerLedBrightness.toByte()
        report[44] = normalizePlayerLedMask(state.playerLedMask).toByte()
        report[45] = state.lightbarRed.toByte()
        report[46] = state.lightbarGreen.toByte()
        report[47] = state.lightbarBlue.toByte()
        report[48] = DSX_BLUETOOTH_REGULAR_LIGHTBAR_TAIL_MARKER.toByte()
        writeChecksum(report)
        return report
    }

    fun buildSpeakerAudioRouteOnlyReportFixed(
        speakerVolume: Int = if (!headphoneMode) mapSpeakerVolumePercentToDsxByte(speakerVolumePercent).toInt() and 0xFF else 0,
        headphoneVolume: Int = if (headphoneMode) mapHeadsetVolumePercentToDsxByte(headphoneVolumePercent).toInt() and 0xFF else 0,
        audioControl: Byte = if (!headphoneMode) SPEAKER_ROUTE_DEFAULT_AUDIO_CONTROL.toByte() else 0,
        audioControl2: Byte = SPEAKER_ROUTE_DEFAULT_AUDIO_CONTROL2.toByte(),
    ): ByteArray
    {
        val report = ByteArray(REPORT_SIZE)
        report[0] = 0x31
        report[1] = nextOutputSeqTag()
        report[2] = 0x10

        val output = 3
        report[output + 0] = (if (!headphoneMode) 0xA0 else 0xB0).toByte()
        report[output + 1] = 0x04
        report[output + 4] = headphoneVolume.coerceIn(0, 0xFF).toByte()
        report[output + 5] = speakerVolume.coerceIn(0, 0xFF).toByte()
        report[output + 7] = audioControl
        report[output + 37] = audioControl2
        writeChecksum(report)
        return report
    }

    /** DSX BuildBluetoothControllerDataOnlyReport — 142-byte 0x32 report. */
    fun buildControllerDataReport(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        ledReady: Boolean = true,
        leftTrigger: ByteArray? = null,
        rightTrigger: ByteArray? = null,
        @Suppress("UNUSED_PARAMETER") speakerRegularBtOutput: Boolean = false,
    ): ByteArray {
        val report = ByteArray(142)
        report[0] = 0x32
        report[1] = nextOutputSeqTag()
        report[2] = 0x90.toByte()
        report[3] = 0x3F
        report[4] = 0xFD.toByte()
        report[5] = 0xF7.toByte()
        report[6] = 0x00
        report[7] = 0x00
        report[8] = if (headphoneMode) mapHeadsetVolumePercentToDsxByte(headphoneVolumePercent) else 0  // routed by headphoneMode
        report[9] = if (!headphoneMode) mapSpeakerVolumePercentToDsxByte(speakerVolumePercent) else 0  // routed by headphoneMode
        report[10] = 0xFF.toByte()
        report[11] = 0x09
        report[12] = 0x00
        report[13] = 0x0F
        if (rightTrigger != null) System.arraycopy(rightTrigger, 0, report, 14, Math.min(rightTrigger.size, 11))
        if (leftTrigger != null) System.arraycopy(leftTrigger, 0, report, 25, Math.min(leftTrigger.size, 11))
        // DSX WriteMinimalControllerDataSubpacket(..., offset=2):
        // absolute indexes 42..52 hold the controller-data visual/control block.
        report[42] = 0x07
        report[43] = 0x00
        report[44] = 0x00
        report[45] = if (ledReady) 0x01 else 0x02
        report[46] = state.playerLedBrightness.toByte()
        report[47] = normalizePlayerLedMask(state.playerLedMask).toByte()
        report[48] = state.lightbarRed.toByte()
        report[49] = state.lightbarGreen.toByte()
        report[50] = state.lightbarBlue.toByte()
        report[51] = 0x03
        report[52] = 0x00
        val crc = DirectDualSenseBtCrc.compute(report, 138)
        report[138] = (crc and 0xFF).toByte(); report[139] = ((crc ushr 8) and 0xFF).toByte()
        report[140] = ((crc ushr 16) and 0xFF).toByte(); report[141] = ((crc ushr 24) and 0xFF).toByte()
        return report
    }

    fun buildControllerDataReportFixed(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        ledReady: Boolean = true,
        leftTrigger: ByteArray? = null,
        rightTrigger: ByteArray? = null,
    ): ByteArray
    {
        val report = ByteArray(142)
        report[0] = 0x32
        report[1] = nextOutputSeqTag()
        writeMinimalControllerDataSubpacket(
            report = report,
            offset = 2,
            state = state,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            ledReady = ledReady,
        )
        val crc = DirectDualSenseBtCrc.compute(report, 138)
        report[138] = (crc and 0xFF).toByte()
        report[139] = ((crc ushr 8) and 0xFF).toByte()
        report[140] = ((crc ushr 16) and 0xFF).toByte()
        report[141] = ((crc ushr 24) and 0xFF).toByte()
        return report
    }

    fun buildControllerDataMuteLedReport(
        muteButtonLed: Int,
        state: PersistentOutputState = snapshotPersistentOutputState(),
        ledReady: Boolean = true,
    ): ByteArray
    {
        val report = ByteArray(142)
        report[0] = 0x32
        report[1] = nextOutputSeqTag()
        writeMinimalControllerDataSubpacket(
            report = report,
            offset = 2,
            state = state,
            leftTrigger = null,
            rightTrigger = null,
            ledReady = ledReady,
            muteButtonLed = muteButtonLed,
        )
        val crc = DirectDualSenseBtCrc.compute(report, 138)
        report[138] = (crc and 0xFF).toByte()
        report[139] = ((crc ushr 8) and 0xFF).toByte()
        report[140] = ((crc ushr 16) and 0xFF).toByte()
        report[141] = ((crc ushr 24) and 0xFF).toByte()
        return report
    }


    fun buildCombinedControllerHapticsReport(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        packedHaptics: ByteArray? = null,
        opusFrame: ByteArray? = null,
        ledReady: Boolean = false,
        leftTrigger: ByteArray? = null,
        rightTrigger: ByteArray? = null,
        latencyScale: Int = COMBINED_ONE_FRAME_LATENCY_SCALE,
        modeByte: Int = currentOneFrameAudioMode(),
    ): ByteArray
    {
        val report = ByteArray(COMBINED_ONE_FRAME_REPORT_SIZE)
        writeCombinedControllerHapticsReport(
            report = report,
            state = state,
            packedHaptics = packedHaptics,
            opusFrame = opusFrame,
            ledReady = ledReady,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            latencyScale = latencyScale,
            modeByte = modeByte,
        )
        return report
    }

    fun writeCombinedControllerHapticsReport(
        report: ByteArray,
        state: PersistentOutputState = snapshotPersistentOutputState(),
        packedHaptics: ByteArray? = null,
        opusFrame: ByteArray? = null,
        ledReady: Boolean = false,
        leftTrigger: ByteArray? = null,
        rightTrigger: ByteArray? = null,
        latencyScale: Int = COMBINED_ONE_FRAME_LATENCY_SCALE,
        modeByte: Int = currentOneFrameAudioMode(),
    )
    {
        require(packedHaptics == null || packedHaptics.size == DirectDualSenseBtAudioHapticsBuilder.SAMPLE_BYTES) {
            "Packed haptics must be exactly ${DirectDualSenseBtAudioHapticsBuilder.SAMPLE_BYTES} bytes"
        }
        require(opusFrame == null || opusFrame.size == COMBINED_ONE_FRAME_OPUS_BYTES) {
            "Opus speaker frame must be exactly $COMBINED_ONE_FRAME_OPUS_BYTES bytes"
        }
        require(report.size == COMBINED_ONE_FRAME_REPORT_SIZE) {
            "Unexpected combined BT report size: ${report.size}"
        }

        report[0] = 0x36
        report[1] = nextOutputSeqTag()
        writeMinimalControllerDataSubpacket(
            report = report,
            offset = 2,
            state = state,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            ledReady = ledReady,
        )
        writeSingleFrameAudioConfig(
            report = report,
            offset = COMBINED_ONE_FRAME_AUDIO_CONFIG_OFFSET,
            latencyScale = latencyScale.coerceIn(0, 0xFF),
            frameCounter = nextSingleFrameAudioCounter(),
            // The haptics tail is fixed after the 200-byte Opus slot. Even when
            // speaker audio is disabled, the controller still needs this config
            // to describe the slot so it can parse the following 0x92 haptics
            // subpacket at byte 278. For haptics-only, do not select any audio
            // route, otherwise the controller may try to play the zeroed Opus slot.
            modeByte = when {
                opusFrame != null -> modeByte.coerceIn(0, 0xFF)
                packedHaptics != null -> COMBINED_ONE_FRAME_HAPTICS_ONLY_MODE
                else -> 0
            },
            opusPayloadBytes = if(packedHaptics != null || opusFrame != null) COMBINED_ONE_FRAME_OPUS_BYTES else 0,
        )
        report.fill(
            0,
            COMBINED_ONE_FRAME_OPUS_OFFSET,
            COMBINED_ONE_FRAME_OPUS_OFFSET + COMBINED_ONE_FRAME_OPUS_BYTES,
        )
        opusFrame?.let {
            System.arraycopy(
                it,
                0,
                report,
                COMBINED_ONE_FRAME_OPUS_OFFSET,
                COMBINED_ONE_FRAME_OPUS_BYTES,
            )
        }
        report.fill(
            0,
            COMBINED_ONE_FRAME_HAPTICS_TAIL_OFFSET,
            COMBINED_ONE_FRAME_HAPTICS_TAIL_OFFSET + COMBINED_ONE_FRAME_HAPTICS_TAIL_LENGTH,
        )
        report[COMBINED_ONE_FRAME_HAPTICS_TAIL_OFFSET + 0] = 0x92.toByte()
        report[COMBINED_ONE_FRAME_HAPTICS_TAIL_OFFSET + 1] = 0x40
        packedHaptics?.let {
            System.arraycopy(
                it,
                0,
                report,
                COMBINED_ONE_FRAME_HAPTICS_TAIL_OFFSET + 2,
                it.size,
            )
        }
        val crc = DirectDualSenseBtCrc.compute(report, COMBINED_ONE_FRAME_CRC_OFFSET)
        report[COMBINED_ONE_FRAME_CRC_OFFSET + 0] = (crc and 0xFF).toByte()
        report[COMBINED_ONE_FRAME_CRC_OFFSET + 1] = ((crc ushr 8) and 0xFF).toByte()
        report[COMBINED_ONE_FRAME_CRC_OFFSET + 2] = ((crc ushr 16) and 0xFF).toByte()
        report[COMBINED_ONE_FRAME_CRC_OFFSET + 3] = ((crc ushr 24) and 0xFF).toByte()
    }

    fun buildDirectAudioHapticsReport(
        packedHaptics: ByteArray? = null,
        opusFrame: ByteArray? = null,
        latencyScale: Int = COMBINED_ONE_FRAME_LATENCY_SCALE,
        modeByte: Int = currentOneFrameAudioMode(),
    ): ByteArray
    {
        val report = ByteArray(COMBINED_ONE_FRAME_REPORT_SIZE)
        writeDirectAudioHapticsReport(
            report = report,
            packedHaptics = packedHaptics,
            opusFrame = opusFrame,
            latencyScale = latencyScale,
            modeByte = modeByte,
        )
        return report
    }

    fun writeDirectAudioHapticsReport(
        report: ByteArray,
        packedHaptics: ByteArray? = null,
        opusFrame: ByteArray? = null,
        latencyScale: Int = COMBINED_ONE_FRAME_LATENCY_SCALE,
        modeByte: Int = currentOneFrameAudioMode(),
    )
    {
        require(packedHaptics == null || packedHaptics.size == DirectDualSenseBtAudioHapticsBuilder.SAMPLE_BYTES) {
            "Packed haptics must be exactly ${DirectDualSenseBtAudioHapticsBuilder.SAMPLE_BYTES} bytes"
        }
        require(opusFrame == null || opusFrame.size == COMBINED_ONE_FRAME_OPUS_BYTES) {
            "Opus speaker frame must be exactly $COMBINED_ONE_FRAME_OPUS_BYTES bytes"
        }
        require(report.size == COMBINED_ONE_FRAME_REPORT_SIZE) {
            "Unexpected direct BT report size: ${report.size}"
        }

        report.fill(0)
        report[0] = 0x36
        report[1] = nextOutputSeqTag()
        writeSingleFrameAudioTimingConfig(
            report = report,
            offset = DIRECT_ONE_FRAME_AUDIO_CONFIG_OFFSET,
            latencyScale = latencyScale.coerceIn(0, 0xFF),
            frameCounter = nextSingleFrameAudioCounter(),
        )
        packedHaptics?.let {
            report[DIRECT_ONE_FRAME_HAPTICS_HEADER_OFFSET + 0] = 0x92.toByte()
            report[DIRECT_ONE_FRAME_HAPTICS_HEADER_OFFSET + 1] = DirectDualSenseBtAudioHapticsBuilder.SAMPLE_BYTES.toByte()
            System.arraycopy(
                it,
                0,
                report,
                DIRECT_ONE_FRAME_HAPTICS_OFFSET,
                it.size,
            )
        }
        opusFrame?.let {
            report[DIRECT_ONE_FRAME_OPUS_CONFIG_OFFSET + 0] = modeByte.coerceIn(0, 0xFF).toByte()
            report[DIRECT_ONE_FRAME_OPUS_CONFIG_OFFSET + 1] = COMBINED_ONE_FRAME_OPUS_BYTES.toByte()
            System.arraycopy(
                it,
                0,
                report,
                DIRECT_ONE_FRAME_OPUS_OFFSET,
                COMBINED_ONE_FRAME_OPUS_BYTES,
            )
        }
        val crc = DirectDualSenseBtCrc.compute(report, COMBINED_ONE_FRAME_CRC_OFFSET)
        report[COMBINED_ONE_FRAME_CRC_OFFSET + 0] = (crc and 0xFF).toByte()
        report[COMBINED_ONE_FRAME_CRC_OFFSET + 1] = ((crc ushr 8) and 0xFF).toByte()
        report[COMBINED_ONE_FRAME_CRC_OFFSET + 2] = ((crc ushr 16) and 0xFF).toByte()
        report[COMBINED_ONE_FRAME_CRC_OFFSET + 3] = ((crc ushr 24) and 0xFF).toByte()
    }

    fun buildTwoFrameHapticsCatchupReport(
        packedHaptics1: ByteArray,
        packedHaptics2: ByteArray,
        latencyScale: Int = COMBINED_ONE_FRAME_LATENCY_SCALE,
    ): ByteArray
    {
        require(packedHaptics1.size == DirectDualSenseBtAudioHapticsBuilder.SAMPLE_BYTES) {
            "Packed haptics frame 1 must be exactly ${DirectDualSenseBtAudioHapticsBuilder.SAMPLE_BYTES} bytes"
        }
        require(packedHaptics2.size == DirectDualSenseBtAudioHapticsBuilder.SAMPLE_BYTES) {
            "Packed haptics frame 2 must be exactly ${DirectDualSenseBtAudioHapticsBuilder.SAMPLE_BYTES} bytes"
        }

        val report = ByteArray(TWO_FRAME_HAPTICS_CATCHUP_REPORT_SIZE)
        report[0] = 0x39
        report[1] = nextOutputSeqTag()
        writeSingleFrameAudioConfig(
            report = report,
            offset = TWO_FRAME_HAPTICS_CATCHUP_AUDIO_CONFIG_OFFSET,
            latencyScale = latencyScale.coerceIn(0, 0xFF),
            frameCounter = nextAudioFrameCounter(2),
            modeByte = TWO_FRAME_HAPTICS_CATCHUP_MODE,
        )
        report.fill(
            0,
            TWO_FRAME_HAPTICS_CATCHUP_OPUS_OFFSET,
            TWO_FRAME_HAPTICS_CATCHUP_OPUS_OFFSET + TWO_FRAME_HAPTICS_CATCHUP_OPUS_BYTES,
        )
        report.fill(
            0,
            TWO_FRAME_HAPTICS_CATCHUP_TAIL_OFFSET,
            TWO_FRAME_HAPTICS_CATCHUP_TAIL_OFFSET + TWO_FRAME_HAPTICS_CATCHUP_TAIL_LENGTH,
        )
        report[TWO_FRAME_HAPTICS_CATCHUP_TAIL_OFFSET + 0] = 0xD2.toByte()
        report[TWO_FRAME_HAPTICS_CATCHUP_TAIL_OFFSET + 1] = 0x40
        System.arraycopy(
            packedHaptics1,
            0,
            report,
            TWO_FRAME_HAPTICS_CATCHUP_TAIL_OFFSET + 2,
            packedHaptics1.size,
        )
        System.arraycopy(
            packedHaptics2,
            0,
            report,
            TWO_FRAME_HAPTICS_CATCHUP_TAIL_OFFSET + 66,
            packedHaptics2.size,
        )
        val crc = DirectDualSenseBtCrc.compute(report, TWO_FRAME_HAPTICS_CATCHUP_CRC_OFFSET)
        report[TWO_FRAME_HAPTICS_CATCHUP_CRC_OFFSET + 0] = (crc and 0xFF).toByte()
        report[TWO_FRAME_HAPTICS_CATCHUP_CRC_OFFSET + 1] = ((crc ushr 8) and 0xFF).toByte()
        report[TWO_FRAME_HAPTICS_CATCHUP_CRC_OFFSET + 2] = ((crc ushr 16) and 0xFF).toByte()
        report[TWO_FRAME_HAPTICS_CATCHUP_CRC_OFFSET + 3] = ((crc ushr 24) and 0xFF).toByte()
        return report
    }

    fun buildAudioStateCarrierReport(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        ledReady: Boolean = false,
        leftTrigger: ByteArray? = null,
        rightTrigger: ByteArray? = null,
        latencyScale: Int = COMBINED_ONE_FRAME_LATENCY_SCALE,
        modeByte: Int = COMBINED_ONE_FRAME_MODE,
    ): ByteArray
    {
        val report = ByteArray(AUDIO_STATE_CARRIER_REPORT_SIZE)
        report[0] = 0x35
        report[1] = nextOutputSeqTag()
        writeSingleFrameAudioConfig(
            report = report,
            offset = AUDIO_STATE_CARRIER_AUDIO_CONFIG_OFFSET,
            latencyScale = latencyScale.coerceIn(0, 0xFF),
            frameCounter = nextSingleFrameAudioCounter(),
            modeByte = 0,
            opusPayloadBytes = 0,
        )
        report.fill(
            0,
            AUDIO_STATE_CARRIER_OPUS_OFFSET,
            AUDIO_STATE_CARRIER_OPUS_OFFSET + COMBINED_ONE_FRAME_OPUS_BYTES,
        )
        writeMinimalControllerDataSubpacket(
            report = report,
            offset = AUDIO_STATE_CARRIER_CONTROLLER_OFFSET,
            state = state,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            ledReady = ledReady,
        )
        val crc = DirectDualSenseBtCrc.compute(report, AUDIO_STATE_CARRIER_CRC_OFFSET)
        report[AUDIO_STATE_CARRIER_CRC_OFFSET + 0] = (crc and 0xFF).toByte()
        report[AUDIO_STATE_CARRIER_CRC_OFFSET + 1] = ((crc ushr 8) and 0xFF).toByte()
        report[AUDIO_STATE_CARRIER_CRC_OFFSET + 2] = ((crc ushr 16) and 0xFF).toByte()
        report[AUDIO_STATE_CARRIER_CRC_OFFSET + 3] = ((crc ushr 24) and 0xFF).toByte()
        return report
    }

    fun buildClearTriggerReport(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        speakerRegularBtOutput: Boolean = false,
    ): ByteArray = buildReport(
        state = state,
        featureMode = 0x57,
        rightTrigger = CLEAR_TRIGGER,
        leftTrigger = CLEAR_TRIGGER,
        regularBtOutput = true,
        speakerRegularBtOutput = speakerRegularBtOutput,
    )

    fun buildClearTriggerReport(featureMode: Int, speakerRegularBtOutput: Boolean = false): ByteArray = buildReport(
        featureMode = featureMode,
        rightTrigger = CLEAR_TRIGGER,
        leftTrigger = CLEAR_TRIGGER,
        regularBtOutput = true,
        speakerRegularBtOutput = speakerRegularBtOutput,
    )

    fun buildTesterPrimeReport(triggerStateReport: ByteArray? = null): ByteArray = buildTesterStatefulReport(
        featureMode = 0xFF,
        regularBtOutput = false,
        triggerStateReport = triggerStateReport,
    )

    fun buildTesterPrimeReportWithFeatureMode(featureMode: Int, triggerStateReport: ByteArray? = null): ByteArray = buildTesterStatefulReport(
        featureMode = featureMode,
        regularBtOutput = false,
        triggerStateReport = triggerStateReport,
    )

    fun buildTesterStateReportWithFeatureMode(featureMode: Int, triggerStateReport: ByteArray? = null): ByteArray = buildTesterStatefulReport(
        featureMode = featureMode,
        regularBtOutput = true,
        triggerStateReport = triggerStateReport,
    )

    fun buildTesterRightTriggerTestReport(featureMode: Int = 0x57): ByteArray = buildTesterReport(
        featureMode = featureMode,
        rightTrigger = STRONG_RESISTANCE_TRIGGER,
        leftTrigger = CLEAR_TRIGGER,
        regularBtOutput = true,
        validFlag0 = TESTER_ROUTINE_VALID_FLAG0,
        includeAudioFields = false,
    )

    fun buildTesterLeftTriggerTestReport(featureMode: Int = 0x57): ByteArray = buildTesterReport(
        featureMode = featureMode,
        rightTrigger = CLEAR_TRIGGER,
        leftTrigger = STRONG_RESISTANCE_TRIGGER,
        regularBtOutput = true,
        validFlag0 = TESTER_ROUTINE_VALID_FLAG0,
        includeAudioFields = false,
    )

    fun buildTesterClearTriggerReport(featureMode: Int = 0x57): ByteArray = buildTesterReport(
        featureMode = featureMode,
        rightTrigger = CLEAR_TRIGGER,
        leftTrigger = CLEAR_TRIGGER,
        regularBtOutput = true,
        validFlag0 = TESTER_ROUTINE_VALID_FLAG0,
        includeAudioFields = false,
    )

    /**
     * Convenience overload: each 10-byte array has [0]=effect type, [1..9]=params.
     * Used by turbo/cycle trigger tests where type and params are packed together.
     */
    fun buildTriggerEffectsReport(
        state: PersistentOutputState,
        leftTrigger: ByteArray,
        rightTrigger: ByteArray,
        speakerRegularBtOutput: Boolean = false,
    ): ByteArray {
        require(leftTrigger.size == 10 && rightTrigger.size == 10)
        val lType = leftTrigger[0].toInt() and 0xFF
        val lData = ByteArray(10).also { System.arraycopy(leftTrigger, 1, it, 0, 9) }
        val rType = rightTrigger[0].toInt() and 0xFF
        val rData = ByteArray(10).also { System.arraycopy(rightTrigger, 1, it, 0, 9) }
        return buildTriggerEffectsReport(state, lType, lData, rType, rData, speakerRegularBtOutput)
    }

    fun buildTriggerEffectsReport(
        leftTrigger: ByteArray,
        rightTrigger: ByteArray,
        speakerRegularBtOutput: Boolean = false,
    ): ByteArray {
        return buildTriggerEffectsReport(
            snapshotPersistentOutputState(),
            leftTrigger,
            rightTrigger,
            speakerRegularBtOutput,
        )
    }

    fun buildTriggerEffectsReport(
        state: PersistentOutputState,
        leftType: Int,
        leftData: ByteArray,
        rightType: Int,
        rightData: ByteArray,
        speakerRegularBtOutput: Boolean = false,
    ): ByteArray
    {
        require(leftData.size == 10) { "Left trigger data must be exactly 10 bytes" }
        require(rightData.size == 10) { "Right trigger data must be exactly 10 bytes" }

        val report = buildReport(
            state = state,
            featureMode = 0x57,
            rightTrigger = CLEAR_TRIGGER,
            leftTrigger = CLEAR_TRIGGER,
            regularBtOutput = true,
            speakerRegularBtOutput = speakerRegularBtOutput,
        )
        report[PAYLOAD_OFFSET + 10] = rightType.toByte()
        System.arraycopy(rightData, 0, report, PAYLOAD_OFFSET + 11, rightData.size)
        report[PAYLOAD_OFFSET + 21] = leftType.toByte()
        System.arraycopy(leftData, 0, report, PAYLOAD_OFFSET + 22, leftData.size)
        writeChecksum(report)
        return report
    }

    fun buildTriggerEffectsReport(
        leftType: Int,
        leftData: ByteArray,
        rightType: Int,
        rightData: ByteArray,
        speakerRegularBtOutput: Boolean = false,
    ): ByteArray = buildTriggerEffectsReport(
        snapshotPersistentOutputState(),
        leftType,
        leftData,
        rightType,
        rightData,
        speakerRegularBtOutput,
    )

    fun buildControllerDataTriggerEffectsReport(
        state: PersistentOutputState,
        leftType: Int,
        leftData: ByteArray,
        rightType: Int,
        rightData: ByteArray,
        ledReady: Boolean = true,
        speakerRegularBtOutput: Boolean = false,
    ): ByteArray
    {
        require(leftData.size == 10) { "Left trigger data must be exactly 10 bytes" }
        require(rightData.size == 10) { "Right trigger data must be exactly 10 bytes" }

        return buildControllerDataReport(
            state = state,
            ledReady = ledReady,
            leftTrigger = packControllerDataTrigger(leftType, leftData),
            rightTrigger = packControllerDataTrigger(rightType, rightData),
            speakerRegularBtOutput = speakerRegularBtOutput,
        )
    }

    fun buildControllerDataTriggerEffectsReport(
        leftType: Int,
        leftData: ByteArray,
        rightType: Int,
        rightData: ByteArray,
        ledReady: Boolean = true,
        speakerRegularBtOutput: Boolean = false,
    ): ByteArray = buildControllerDataTriggerEffectsReport(
        snapshotPersistentOutputState(),
        leftType,
        leftData,
        rightType,
        rightData,
        ledReady,
        speakerRegularBtOutput,
    )

    private fun buildStatefulReport(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        featureMode: Int,
        regularBtOutput: Boolean,
        speakerRegularBtOutput: Boolean,
        ledReady: Boolean = false,
        triggerStateReport: ByteArray?,
    ): ByteArray
    {
        val report = buildReport(
            state = state,
            featureMode = featureMode,
            rightTrigger = CLEAR_TRIGGER,
            leftTrigger = CLEAR_TRIGGER,
            regularBtOutput = regularBtOutput,
            speakerRegularBtOutput = speakerRegularBtOutput,
            ledReady = ledReady,
        )
        if(triggerStateReport != null && triggerStateReport.size >= TRIGGER_STATE_OFFSET + TRIGGER_STATE_LENGTH)
        {
            System.arraycopy(triggerStateReport, TRIGGER_STATE_OFFSET, report, TRIGGER_STATE_OFFSET, TRIGGER_STATE_LENGTH)
            writeChecksum(report)
        }
        return report
    }

    private fun buildTesterStatefulReport(featureMode: Int, regularBtOutput: Boolean, triggerStateReport: ByteArray?): ByteArray
    {
        val report = buildTesterReport(
            featureMode = featureMode,
            rightTrigger = CLEAR_TRIGGER,
            leftTrigger = CLEAR_TRIGGER,
            regularBtOutput = regularBtOutput,
            validFlag0 = if(regularBtOutput) TESTER_ROUTINE_VALID_FLAG0 else TESTER_PRIME_VALID_FLAG0,
            includeAudioFields = !regularBtOutput,
        )
        if(triggerStateReport != null && triggerStateReport.size >= TRIGGER_STATE_OFFSET + TRIGGER_STATE_LENGTH)
        {
            System.arraycopy(triggerStateReport, TRIGGER_STATE_OFFSET, report, TRIGGER_STATE_OFFSET, TRIGGER_STATE_LENGTH)
            writeChecksum(report)
        }
        return report
    }

    private fun buildReport(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        featureMode: Int,
        rightTrigger: ByteArray,
        leftTrigger: ByteArray,
        regularBtOutput: Boolean,
        speakerRegularBtOutput: Boolean,
        ledReady: Boolean = false,
    ): ByteArray
    {
        val report = ByteArray(REPORT_SIZE)
        report[0] = 0x31
        report[1] = nextOutputSeqTag()
        if(regularBtOutput)
        {
            report[PAYLOAD_OFFSET + 0] = 0x0F
            report[PAYLOAD_OFFSET + 1] = 0x55
            report[10] = 0x00
            report[11] = 0x0F
            report[FEATURE_REDUCE_INDEX] = (((state.triggerSoftnessLevel and 0x0F) shl 4) or (state.softRumbleReduce and 0x0F)).toByte()
            report[REGULAR_BT_FLAG_INDEX] = if(speakerRegularBtOutput) {
                SPEAKER_REGULAR_BT_PREP_FLAGS.toByte()
            } else {
                nextRegularBtFlag()
            }
            report[41] = 0x00
            report[REGULAR_LED_READY_INDEX] = resolveLightbarSetupByte(ledReady)
            report[PLAYER_LED_BRIGHTNESS_INDEX] = state.playerLedBrightness.toByte()
            report[PLAYER_LED_MASK_INDEX] = normalizePlayerLedMask(state.playerLedMask).toByte()
            report[LIGHTBAR_RED_INDEX] = state.lightbarRed.toByte()
            report[LIGHTBAR_GREEN_INDEX] = state.lightbarGreen.toByte()
            report[LIGHTBAR_BLUE_INDEX] = state.lightbarBlue.toByte()
            report[LIGHTBAR_TAIL_INDEX] = DSX_BLUETOOTH_REGULAR_LIGHTBAR_TAIL_MARKER.toByte()
            System.arraycopy(rightTrigger, 0, report, TRIGGER_STATE_OFFSET, minOf(10, rightTrigger.size))
            System.arraycopy(leftTrigger, 0, report, TRIGGER_STATE_OFFSET + 11, minOf(10, leftTrigger.size))
        }
        else
        {
            report[PAYLOAD_OFFSET + 0] = 0xFF.toByte()
            report[PAYLOAD_OFFSET + 1] = featureMode.toByte()
            report[REGULAR_BT_FLAG_INDEX] = 0x00
            report[AUDIO_HEADSET_VOLUME_INDEX] = DEFAULT_AUDIO_VOLUME.toByte()
            report[AUDIO_SPEAKER_VOLUME_INDEX] = DEFAULT_AUDIO_VOLUME.toByte()
            report[AUDIO_MIC_VOLUME_INDEX] = DEFAULT_AUDIO_VOLUME.toByte()
            report[AUDIO_MODE_INDEX] = DEFAULT_AUDIO_MODE.toByte()
            report[AUDIO_MIC_FLAG_INDEX] = 0x00
            report[AUDIO_POWER_SAVE_INDEX] = 0x00
            report[FEATURE_REDUCE_INDEX] = (((state.triggerSoftnessLevel and 0x0F) shl 4) or (state.softRumbleReduce and 0x0F)).toByte()
            report[REGULAR_LED_READY_INDEX] = resolveLightbarSetupByte(ledReady)
            report[PLAYER_LED_BRIGHTNESS_INDEX] = state.playerLedBrightness.toByte()
            report[PLAYER_LED_MASK_INDEX] = normalizePlayerLedMask(state.playerLedMask).toByte()
            report[LIGHTBAR_RED_INDEX] = state.lightbarRed.toByte()
            report[LIGHTBAR_GREEN_INDEX] = state.lightbarGreen.toByte()
            report[LIGHTBAR_BLUE_INDEX] = state.lightbarBlue.toByte()
            report[LIGHTBAR_TAIL_INDEX] = DSX_BLUETOOTH_REGULAR_LIGHTBAR_TAIL_MARKER.toByte()
            System.arraycopy(rightTrigger, 0, report, PAYLOAD_OFFSET + 10, minOf(10, rightTrigger.size))
            System.arraycopy(leftTrigger, 0, report, PAYLOAD_OFFSET + 21, minOf(10, leftTrigger.size))
        }
        writeChecksum(report)
        return report
    }

    private fun buildUsbAudioRouteReport(
        state: PersistentOutputState = snapshotPersistentOutputState(),
        headsetVolume: Int,
        speakerVolume: Int,
        micVolume: Int,
        audioControl: Byte,
        audioControl2: Byte,
    ): ByteArray
    {
        val usbReport = ByteArray(48)
        val speakerWaveOut = (audioControl.toInt() and 0x30) == 0x30
        usbReport[0] = 0x02
        usbReport[1] = if(speakerWaveOut) 0xA0.toByte() else 0xB0.toByte()
        usbReport[2] = 0x04
        usbReport[5] = headsetVolume.coerceIn(0, 0xFF).toByte()
        usbReport[6] = speakerVolume.coerceIn(0, 0xFF).toByte()
        usbReport[7] = micVolume.coerceIn(0, 0xFF).toByte()
        usbReport[8] = audioControl
        usbReport[38] = audioControl2
        usbReport[43] = state.playerLedBrightness.toByte()
        usbReport[44] = normalizePlayerLedMask(state.playerLedMask).toByte()
        usbReport[45] = state.lightbarRed.toByte()
        usbReport[46] = state.lightbarGreen.toByte()
        usbReport[47] = state.lightbarBlue.toByte()
        return usbReport
    }

    private fun buildBluetoothWrappedUsbOutputReport(usbReport: ByteArray, flagsNibble: Int = 0): ByteArray
    {
        require(usbReport.size >= 48 && usbReport[0] == 0x02.toByte()) {
            "Expected a 48-byte DualSense USB output report with ID 0x02"
        }

        val report = ByteArray(REPORT_SIZE)
        report[0] = 0x31
        report[1] = nextOutputSeqTag(flagsNibble)
        report[2] = 0x10
        System.arraycopy(usbReport, 1, report, 3, 47)
        writeChecksum(report)
        return report
    }

    private fun buildTesterReport(
        featureMode: Int,
        rightTrigger: ByteArray,
        leftTrigger: ByteArray,
        regularBtOutput: Boolean,
        validFlag0: Int,
        includeAudioFields: Boolean,
    ): ByteArray
    {
        val report = ByteArray(REPORT_SIZE)
        report[0] = 0x31
        report[1] = nextOutputSeqTag()
        report[PAYLOAD_OFFSET + 0] = validFlag0.toByte()
        report[PAYLOAD_OFFSET + 1] = featureMode.toByte()
        report[REGULAR_BT_FLAG_INDEX] = if(regularBtOutput) nextRegularBtFlag() else 0x00
        if(includeAudioFields)
        {
            report[AUDIO_HEADSET_VOLUME_INDEX] = DEFAULT_AUDIO_VOLUME.toByte()
            report[AUDIO_SPEAKER_VOLUME_INDEX] = DEFAULT_AUDIO_VOLUME.toByte()
            report[AUDIO_MIC_VOLUME_INDEX] = DEFAULT_AUDIO_VOLUME.toByte()
            report[AUDIO_MODE_INDEX] = DEFAULT_AUDIO_MODE.toByte()
            report[AUDIO_MIC_FLAG_INDEX] = 0x00
            report[AUDIO_POWER_SAVE_INDEX] = 0x00
        }
        report[FEATURE_REDUCE_INDEX] = (((persistentTriggerSoftnessLevel and 0x0F) shl 4) or (persistentSoftRumbleReduce and 0x0F)).toByte()
        report[REGULAR_LED_READY_INDEX] = resolveLightbarSetupByte(false)
        report[PLAYER_LED_BRIGHTNESS_INDEX] = persistentPlayerLedBrightness.toByte()
        report[PLAYER_LED_MASK_INDEX] = normalizePlayerLedMask(persistentPlayerLedMask).toByte()
        report[LIGHTBAR_RED_INDEX] = persistentLightbarRed.toByte()
        report[LIGHTBAR_GREEN_INDEX] = persistentLightbarGreen.toByte()
        report[LIGHTBAR_BLUE_INDEX] = persistentLightbarBlue.toByte()
        report[LIGHTBAR_TAIL_INDEX] = DSX_BLUETOOTH_REGULAR_LIGHTBAR_TAIL_MARKER.toByte()

        System.arraycopy(rightTrigger, 0, report, PAYLOAD_OFFSET + 10, 10)
        System.arraycopy(leftTrigger, 0, report, PAYLOAD_OFFSET + 21, 10)
        writeChecksum(report)
        return report
    }

    @Synchronized
    private fun nextRegularBtFlag(): Byte
    {
        return 0x03
    }

    @Synchronized
    fun nextOutputSeqTag(flagsNibble: Int = 0): Byte
    {
        val tag = (((outputSeqTag and 0x0F) shl 4) or (flagsNibble and 0x0F)).toByte()
        outputSeqTag = (outputSeqTag + 1) and 0xFF
        return tag
    }

    @Synchronized
    private fun nextSingleFrameAudioCounter(): Int
    {
        return nextAudioFrameCounter(1)
    }

    @Synchronized
    private fun nextAudioFrameCounter(framesPerPacket: Int): Int
    {
        val current = singleFrameAudioCounter and 0xFF
        singleFrameAudioCounter = (singleFrameAudioCounter + framesPerPacket.coerceIn(1, 2)) and 0xFF
        return current
    }

    private fun writeChecksum(report: ByteArray)
    {
        val checksum = computeChecksum(report, 74)
        report[CRC_OFFSET + 0] = (checksum and 0xFF).toByte()
        report[CRC_OFFSET + 1] = ((checksum ushr 8) and 0xFF).toByte()
        report[CRC_OFFSET + 2] = ((checksum ushr 16) and 0xFF).toByte()
        report[CRC_OFFSET + 3] = ((checksum ushr 24) and 0xFF).toByte()
    }

    private fun resolveLightbarSetupByte(ledReady: Boolean): Byte =
        if(ledReady) DSX_BLUETOOTH_LIGHTBAR_SETUP_READY.toByte() else DSX_BLUETOOTH_LIGHTBAR_SETUP_WARMUP.toByte()

    private fun composeBeamformingByte(beamforming: Int = 0x02): Byte =
        (0x08 or (beamforming and 0x07)).toByte()

    private fun mapVolumePercentToDsxByte(volumePercent: Int, minValue: Int, maxValue: Int): Byte
    {
        if(volumePercent <= 0)
            return 0x00
        val clamped = volumePercent.coerceIn(0, 100)
        return (minValue + ((clamped * (maxValue - minValue)) / 100)).toByte()
    }

    private fun mapHeadsetVolumePercentToDsxByte(volumePercent: Int): Byte =
        mapVolumePercentToDsxByte(volumePercent, minValue = 64, maxValue = 127)

    // DSX maps speaker volume 0-100% ? hardware units 50-127 (not 64-127).
    // Using 64 as the floor meant full volume only reached ~78% of hardware capability.
    private fun mapSpeakerVolumePercentToDsxByte(volumePercent: Int): Byte =
        mapVolumePercentToDsxByte(volumePercent, minValue = 50, maxValue = 127)

    private fun writeSingleFrameAudioConfig(
        report: ByteArray,
        offset: Int,
        latencyScale: Int,
        frameCounter: Int,
        modeByte: Int,
        opusPayloadBytes: Int = COMBINED_ONE_FRAME_OPUS_BYTES,
    )
    {
        writeSingleFrameAudioTimingConfig(
            report = report,
            offset = offset,
            latencyScale = latencyScale,
            frameCounter = frameCounter,
        )
        report[offset + 9] = modeByte.coerceIn(0, 0xFF).toByte()
        report[offset + 10] = opusPayloadBytes.coerceIn(0, 0xFF).toByte()
    }

    private fun writeSingleFrameAudioTimingConfig(
        report: ByteArray,
        offset: Int,
        latencyScale: Int,
        frameCounter: Int,
    )
    {
        val latency = latencyScale.coerceIn(0, 0xFF).toByte()
        report[offset + 0] = 0x91.toByte()
        report[offset + 1] = 0x07
        report[offset + 2] = 0xFE.toByte()
        report[offset + 3] = latency
        report[offset + 4] = latency
        report[offset + 5] = latency
        report[offset + 6] = latency
        report[offset + 7] = latency
        report[offset + 8] = frameCounter.coerceIn(0, 0xFF).toByte()
    }

    private fun writeMinimalControllerDataSubpacket(
        report: ByteArray,
        offset: Int,
        state: PersistentOutputState,
        leftTrigger: ByteArray?,
        rightTrigger: ByteArray?,
        ledReady: Boolean,
        muteButtonLed: Int = 0,
    )
    {
        val clearEnd = minOf(report.size, offset + CONTROLLER_DATA_TAIL_CLEAR_LENGTH)
        report.fill(0, offset, clearEnd)
        report[offset + 0] = 0x90.toByte()
        report[offset + 1] = 0x3F
        report[offset + 2] = 0xFD.toByte()
        report[offset + 3] = 0xF7.toByte()
        report[offset + 4] = 0x00
        report[offset + 5] = 0x00
        report[offset + 6] = if (headphoneMode) mapHeadsetVolumePercentToDsxByte(headphoneVolumePercent) else 0
        report[offset + 7] = if (!headphoneMode) mapSpeakerVolumePercentToDsxByte(speakerVolumePercent) else 0
        report[offset + 8] = 0xFF.toByte()
        report[offset + 9] = 0x09
        report[offset + 10] = muteButtonLed.coerceIn(0, 0xFF).toByte()
        report[offset + 11] = 0x0F
        rightTrigger?.let {
            System.arraycopy(it, 0, report, offset + 12, minOf(it.size, 11))
        }
        leftTrigger?.let {
            System.arraycopy(it, 0, report, offset + 23, minOf(it.size, 11))
        }

        val visualOffset = offset + 38
        report[visualOffset + 0] = 0x00
        report[visualOffset + 1] = composeBeamformingByte()
        report[visualOffset + 2] = 0x07
        report[visualOffset + 3] = 0x00
        report[visualOffset + 4] = 0x00
        report[visualOffset + 5] = resolveLightbarSetupByte(ledReady)
        report[visualOffset + 6] = state.playerLedBrightness.toByte()
        report[visualOffset + 7] = normalizePlayerLedMask(state.playerLedMask).toByte()
        report[visualOffset + 8] = state.lightbarRed.toByte()
        report[visualOffset + 9] = state.lightbarGreen.toByte()
        report[visualOffset + 10] = state.lightbarBlue.toByte()
        report[visualOffset + 11] = 0x00
        report[visualOffset + 12] = 0x00
    }

    private fun packControllerDataTrigger(type: Int, data: ByteArray): ByteArray
    {
        val packed = ByteArray(11)
        packed[0] = type.toByte()
        val copyLength = minOf(10, data.size)
        if(copyLength > 0)
            System.arraycopy(data, 0, packed, 1, copyLength)
        return packed
    }

    private fun createResistanceTrigger(startZone: Int, strength: Int): ByteArray
    {
        val trigger = ByteArray(10)
        trigger[0] = 0x01
        trigger[1] = startZone.toByte()
        trigger[2] = strength.toByte()
        return trigger
    }

    private fun createGameCubeTrigger(): ByteArray
    {
        val trigger = ByteArray(10)
        trigger[0] = 0x02
        trigger[1] = 0x90.toByte()
        trigger[2] = 0x0A
        trigger[3] = 0xFF.toByte()
        return trigger
    }

    private fun computeChecksum(buffer: ByteArray, length: Int): Int
    {
        var result = CRC_SEED
        for(i in 0 until length)
        {
            val index = (result xor (buffer[i].toInt() and 0xFF)) and 0xFF
            result = CRC_TABLE[index] xor (result ushr 8)
        }
        return result
    }

    private val CRC_TABLE = intArrayOf(
        0xD202EF8D.toInt(), 0xA505DF1B.toInt(), 0x3C0C8EA1, 0x4B0BBE37,
        0xD56F2B94.toInt(), 0xA2681B02.toInt(), 0x3B614AB8, 0x4C667A2E,
        0xDCD967BF.toInt(), 0xABDE5729.toInt(), 0x32D70693, 0x45D03605,
        0xDBB4A3A6.toInt(), 0xACB39330.toInt(), 0x35BAC28A, 0x42BDF21C,
        0xCFB5FFE9.toInt(), 0xB8B2CF7F.toInt(), 0x21BB9EC5, 0x56BCAE53,
        0xC8D83BF0.toInt(), 0xBFDF0B66.toInt(), 0x26D65ADC, 0x51D16A4A,
        0xC16E77DB.toInt(), 0xB669474D.toInt(), 0x2F6016F7, 0x58672661,
        0xC603B3C2.toInt(), 0xB1048354.toInt(), 0x280DD2EE, 0x5F0AE278,
        0xE96CCF45.toInt(), 0x9E6BFFD3.toInt(), 0x0762AE69, 0x70659EFF,
        0xEE010B5C.toInt(), 0x99063BCA.toInt(), 0x000F6A70, 0x77085AE6,
        0xE7B74777.toInt(), 0x90B077E1.toInt(), 0x09B9265B, 0x7EBE16CD,
        0xE0DA836E.toInt(), 0x97DDB3F8.toInt(), 0x0ED4E242, 0x79D3D2D4,
        0xF4DBDF21.toInt(), 0x83DCEFB7.toInt(), 0x1AD5BE0D, 0x6DD28E9B,
        0xF3B61B38.toInt(), 0x84B12BAE.toInt(), 0x1DB87A14, 0x6ABF4A82,
        0xFA005713.toInt(), 0x8D076785.toInt(), 0x140E363F, 0x630906A9,
        0xFD6D930A.toInt(), 0x8A6AA39C.toInt(), 0x1363F226, 0x6464C2B0,
        0xA4DEAE1D.toInt(), 0xD3D99E8B.toInt(), 0x4AD0CF31, 0x3DD7FFA7,
        0xA3B36A04.toInt(), 0xD4B45A92.toInt(), 0x4DBD0B28, 0x3ABA3BBE,
        0xAA05262F.toInt(), 0xDD0216B9.toInt(), 0x440B4703, 0x330C7795,
        0xAD68E236.toInt(), 0xDA6FD2A0.toInt(), 0x4366831A, 0x3461B38C,
        0xB969BE79.toInt(), 0xCE6E8EEF.toInt(), 0x5767DF55, 0x2060EFC3,
        0xBE047A60.toInt(), 0xC9034AF6.toInt(), 0x500A1B4C, 0x270D2BDA,
        0xB7B2364B.toInt(), 0xC0B506DD.toInt(), 0x59BC5767, 0x2EBB67F1,
        0xB0DFF252.toInt(), 0xC7D8C2C4.toInt(), 0x5ED1937E, 0x29D6A3E8,
        0x9FB08ED5.toInt(), 0xE8B7BE43.toInt(), 0x71BEEFF9, 0x06B9DF6F,
        0x98DD4ACC.toInt(), 0xEFDA7A5A.toInt(), 0x76D32BE0, 0x01D41B76,
        0x916B06E7.toInt(), 0xE66C3671.toInt(), 0x7F6567CB, 0x0862575D,
        0x9606C2FE.toInt(), 0xE101F268.toInt(), 0x7808A3D2, 0x0F0F9344,
        0x82079EB1.toInt(), 0xF500AE27.toInt(), 0x6C09FF9D, 0x1B0ECF0B,
        0x856A5AA8.toInt(), 0xF26D6A3E.toInt(), 0x6B643B84, 0x1C630B12,
        0x8CDC1683.toInt(), 0xFBDB2615.toInt(), 0x62D277AF, 0x15D54739,
        0x8BB1D29A.toInt(), 0xFCB6E20C.toInt(), 0x65BFB3B6, 0x12B88320,
        0x3FBA6CAD, 0x48BD5C3B, 0xD1B40D81.toInt(), 0xA6B33D17.toInt(),
        0x38D7A8B4, 0x4FD09822, 0xD6D9C998.toInt(), 0xA1DEF90E.toInt(),
        0x3161E49F, 0x4666D409, 0xDF6F85B3.toInt(), 0xA868B525.toInt(),
        0x360C2086, 0x410B1010, 0xD80241AA.toInt(), 0xAF05713C.toInt(),
        0x220D7CC9, 0x550A4C5F, 0xCC031DE5.toInt(), 0xBB042D73.toInt(),
        0x2560B8D0, 0x52678846, 0xCB6ED9FC.toInt(), 0xBC69E96A.toInt(),
        0x2CD6F4FB, 0x5BD1C46D, 0xC2D895D7.toInt(), 0xB5DFA541.toInt(),
        0x2BBB30E2, 0x5CBC0074, 0xC5B551CE.toInt(), 0xB2B26158.toInt(),
        0x04D44C65, 0x73D37CF3, 0xEADA2D49.toInt(), 0x9DDD1DDF.toInt(),
        0x03B9887C, 0x74BEB8EA, 0xEDB7E950.toInt(), 0x9AB0D9C6.toInt(),
        0x0A0FC457, 0x7D08F4C1, 0xE401A57B.toInt(), 0x930695ED.toInt(),
        0x0D62004E, 0x7A6530D8, 0xE36C6162.toInt(), 0x946B51F4.toInt(),
        0x19635C01, 0x6E646C97, 0xF76D3D2D.toInt(), 0x806A0DBB.toInt(),
        0x1E0E9818, 0x6909A88E, 0xF000F934.toInt(), 0x8707C9A2.toInt(),
        0x17B8D433, 0x60BFE4A5, 0xF9B6B51F.toInt(), 0x8EB18589.toInt(),
        0x10D5102A, 0x67D220BC, 0xFEDB7106.toInt(), 0x89DC4190.toInt(),
        0x49662D3D, 0x3E611DAB, 0xA7684C11.toInt(), 0xD06F7C87.toInt(),
        0x4E0BE924, 0x390CD9B2, 0xA0058808.toInt(), 0xD702B89E.toInt(),
        0x47BDA50F, 0x30BA9599, 0xA9B3C423.toInt(), 0xDEB4F4B5.toInt(),
        0x40D06116, 0x37D75180, 0xAEDE003A.toInt(), 0xD9D930AC.toInt(),
        0x54D13D59, 0x23D60DCF, 0xBADF5C75.toInt(), 0xCDD86CE3.toInt(),
        0x53BCF940, 0x24BBC9D6, 0xBDB2986C.toInt(), 0xCAB5A8FA.toInt(),
        0x5A0AB56B, 0x2D0D85FD, 0xB404D447.toInt(), 0xC303E4D1.toInt(),
        0x5D677172, 0x2A6041E4, 0xB369105E.toInt(), 0xC46E20C8.toInt(),
        0x72080DF5, 0x050F3D63, 0x9C066CD9.toInt(), 0xEB015C4F.toInt(),
        0x7565C9EC, 0x0262F97A, 0x9B6BA8C0.toInt(), 0xEC6C9856.toInt(),
        0x7CD385C7, 0x0BD4B551, 0x92DDE4EB.toInt(), 0xE5DAD47D.toInt(),
        0x7BBE41DE, 0x0CB97148, 0x95B020F2.toInt(), 0xE2B71064.toInt(),
        0x6FBF1D91, 0x18B82D07, 0x81B17CBD.toInt(), 0xF6B64C2B.toInt(),
        0x68D2D988, 0x1FD5E91E, 0x86DCB8A4.toInt(), 0xF1DB8832.toInt(),
        0x616495A3, 0x1663A535, 0x8F6AF48F.toInt(), 0xF86DC419.toInt(),
        0x660951BA, 0x110E612C, 0x88073096.toInt(), 0xFF000000.toInt(),
    )
}

