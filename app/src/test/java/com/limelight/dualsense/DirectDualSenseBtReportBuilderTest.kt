package com.limelight.dualsense

import com.example.usbbtonandroid.DualSenseBtOutputBuilder
import com.example.usbbtonandroid.DualSenseOutputConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectDualSenseBtReportBuilderTest {
    private val state = DirectDualSenseBtReportBuilder.PersistentOutputState(
        playerLedBrightness = 1,
        playerLedMask = 0x15,
        triggerSoftnessLevel = 0,
        softRumbleReduce = 0,
        lightbarRed = 0x12,
        lightbarGreen = 0x34,
        lightbarBlue = 0x56,
    )

    @Test fun stateReportKeepsVisualAndRumbleChannelsSeparate() {
        val report = DirectDualSenseBtReportBuilder.buildClassicRumbleReport(
            state = state, leftMotor = 0x44, rightMotor = 0x22, ledReady = true)
        assertEquals(78, report.size)
        assertEquals(0x31, report[0].toInt() and 0xff)
        assertEquals(0x22, report[4].toInt() and 0xff)
        assertEquals(0x44, report[5].toInt() and 0xff)
        assertEquals(0x01, report[44].toInt() and 0xff)
        assertEquals(0x15, report[45].toInt() and 0xff)
        assertEquals(0x12, report[46].toInt() and 0xff)
        assertEquals(0x34, report[47].toInt() and 0xff)
        assertEquals(0x56, report[48].toInt() and 0xff)
    }

    @Test fun adaptiveTriggerPayloadUsesTheCorrectSideOffsets() {
        val left = ByteArray(10) { (0x10 + it).toByte() }
        val right = ByteArray(10) { (0x20 + it).toByte() }
        val report = DirectDualSenseBtReportBuilder.buildTriggerEffectsReport(
            state, 0x26, left, 0x27, right)
        assertEquals(0x27, report[12].toInt() and 0xff)
        assertEquals(0x20, report[13].toInt() and 0xff)
        assertEquals(0x26, report[23].toInt() and 0xff)
        assertEquals(0x10, report[24].toInt() and 0xff)
    }

    @Test fun combinedWirelessReportCarriesControllerAudioAndHaptics() {
        val report = DirectDualSenseBtReportBuilder.buildCombinedControllerHapticsReport(
            state = state,
            packedHaptics = ByteArray(64) { it.toByte() },
            opusFrame = ByteArray(200) { 0x5a },
            ledReady = true,
        )
        assertEquals(398, report.size)
        assertEquals(0x36, report[0].toInt() and 0xff)
        assertEquals(0x5a, report[78].toInt() and 0xff)
        assertEquals(0x92, report[278].toInt() and 0xff)
        assertEquals(0x40, report[279].toInt() and 0xff)
    }

    @Test fun audioStateCarrierCarriesPlayerLedBrightnessAndMask() {
        val report = DirectDualSenseBtReportBuilder.buildAudioStateCarrierReport(
            state = state,
            ledReady = true,
        )
        assertEquals(0x35, report[0].toInt() and 0xff)
        // The embedded controller block starts at byte 213; its visual block
        // begins 38 bytes later, with brightness/mask at +6/+7.
        assertEquals(0x01, report[257].toInt() and 0xff)
        assertEquals(0x15, report[258].toInt() and 0xff)
    }

    @Test fun provenBridgeStateLayoutCarriesPlayerMaskAndRgb() {
        val report = DualSenseBtOutputBuilder.build(
            DualSenseOutputConfig(red = 0x12, green = 0x34, blue = 0x56,
                playerLeds = 0x0a),
            sequence = 3,
        )
        assertEquals(0x31, report[0].toInt() and 0xff)
        assertEquals(0x2a, report[46].toInt() and 0xff)
        assertEquals(0x12, report[47].toInt() and 0xff)
        assertEquals(0x34, report[48].toInt() and 0xff)
        assertEquals(0x56, report[49].toInt() and 0xff)
    }
}
