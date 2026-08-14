package com.example.usbbtonandroid

import com.limelight.LimeLog

data class DualSenseInput(
    val reportMode: String,
    val leftX: Int,
    val leftY: Int,
    val rightX: Int,
    val rightY: Int,
    val leftTrigger: Int,
    val rightTrigger: Int,
    val pressed: Set<String>,
    val dpadHat: Int = 8,
    val gyro: Triple<Int, Int, Int>? = null,
    val accel: Triple<Int, Int, Int>? = null,
    val touches: List<DualSenseTouchPoint> = emptyList(),
    val batteryPercent: Int = -1,
    val batteryStatus: String = "Unknown"
) {
    companion object {
        val Empty = DualSenseInput("Nincs input", 128, 128, 128, 128, 0, 0, emptySet())
    }
}

data class DualSenseTouchPoint(val id: Int, val x: Int, val y: Int)

object DualSenseInputParser {
    private var lastBatteryRaw = -1

    fun parse(payload: ByteArray): DualSenseInput? {
        if (payload.size < 11 || payload[0].u8() != 0xA1) return null
        return when (payload[1].u8()) {
            0x01 -> parseMinimal(payload)
            0x31 -> parseFull(payload)
            else -> null
        }
    }

    private fun parseMinimal(p: ByteArray): DualSenseInput {
        val buttons0 = p[6].u8()
        val buttons1 = p[7].u8()
        val buttons2 = p[8].u8()
        return DualSenseInput(
            reportMode = "Rövid Bluetooth report (0x01)",
            leftX = p[2].u8(), leftY = p[3].u8(),
            rightX = p[4].u8(), rightY = p[5].u8(),
            leftTrigger = p[9].u8(), rightTrigger = p[10].u8(),
            pressed = decodeButtons(buttons0, buttons1, buttons2, includeMute = false),
            dpadHat = normalizeHat(buttons0 and 0x0F)
        )
    }

    private fun parseFull(p: ByteArray): DualSenseInput? {
        val base = 3
        if (p.size < base + 31) return null
        val buttons0 = p[base + 7].u8()
        val buttons1 = p[base + 8].u8()
        val buttons2 = p[base + 9].u8()
        return DualSenseInput(
            reportMode = "Teljes Bluetooth report (0x31)",
            leftX = p[base].u8(), leftY = p[base + 1].u8(),
            rightX = p[base + 2].u8(), rightY = p[base + 3].u8(),
            leftTrigger = p[base + 4].u8(), rightTrigger = p[base + 5].u8(),
            pressed = decodeButtons(buttons0, buttons1, buttons2, includeMute = true),
            dpadHat = normalizeHat(buttons0 and 0x0F),
            gyro = Triple(p.s16(base + 15), p.s16(base + 17), p.s16(base + 19)),
            accel = Triple(p.s16(base + 21), p.s16(base + 23), p.s16(base + 25)),
            touches = parseTouchPoints(p, base),
            batteryPercent = batteryPercent(p, base),
            batteryStatus = batteryStatus(p, base)
        )
    }

    private fun parseTouchPoints(p: ByteArray, base: Int): List<DualSenseTouchPoint> {
        val result = ArrayList<DualSenseTouchPoint>(2)
        for (offset in intArrayOf(base + 32, base + 36)) {
            if (offset + 3 >= p.size) continue
            val contact = p[offset].u8()
            if (contact and 0x80 != 0) continue
            val x = p[offset + 1].u8() or ((p[offset + 2].u8() and 0x0F) shl 8)
            val y = ((p[offset + 2].u8() and 0xF0) ushr 4) or
                    (p[offset + 3].u8() shl 4)
            result += DualSenseTouchPoint(contact and 0x7F, x, y)
        }
        return result
    }

    // DualSense full input reports expose battery capacity/status in byte 52
    // of the common input payload. Capacity is reported in 10% steps.
    private fun batteryPercent(p: ByteArray, base: Int): Int {
        if (p.size <= base + 52) return -1
        val raw = p[base + 52].u8()
        if (raw != lastBatteryRaw) {
            lastBatteryRaw = raw
            LimeLog.info(
                "DualSense Bridge battery raw=0x${raw.toString(16).padStart(2, '0')} " +
                    "capacity=${raw and 0x0F} state=${(raw ushr 4) and 0x0F}"
            )
        }
        val charging = (raw ushr 4) and 0x0F
        if (charging == 0x2) return 100
        // 0xA/0xB describe an abnormal/not-charging power state. They do not
        // invalidate the capacity nibble, so forcing them to 0% made Bridge
        // controllers appear permanently empty. DualSense reports a 0-10
        // bucket; use the midpoint of each bucket, matching the HID driver.
        return ((raw and 0x0F) * 10 + 5).coerceAtMost(100)
    }

    private fun batteryStatus(p: ByteArray, base: Int): String {
        if (p.size <= base + 52) return "Unknown"
        return when ((p[base + 52].u8() ushr 4) and 0x0F) {
            0x0 -> "Discharging"
            0x1 -> "Charging"
            0x2 -> "Full"
            0xA, 0xB -> "Not charging"
            else -> "Unknown"
        }
    }

    private fun decodeButtons(b0: Int, b1: Int, b2: Int, includeMute: Boolean): Set<String> {
        val result = linkedSetOf<String>()
        when (b0 and 0x0F) {
            0 -> result += "↑"
            1 -> result += listOf("↑", "→")
            2 -> result += "→"
            3 -> result += listOf("→", "↓")
            4 -> result += "↓"
            5 -> result += listOf("↓", "←")
            6 -> result += "←"
            7 -> result += listOf("←", "↑")
        }
        addBits(result, b0, 4, listOf("□", "×", "○", "△"))
        addBits(result, b1, 0, listOf("L1", "R1", "L2", "R2", "Create", "Options", "L3", "R3"))
        if (b2 and 0x01 != 0) result += "PS"
        if (b2 and 0x02 != 0) result += "Touchpad"
        if (includeMute && b2 and 0x04 != 0) result += "Mute"
        return result
    }

    private fun addBits(target: MutableSet<String>, value: Int, start: Int, names: List<String>) {
        names.forEachIndexed { index, name ->
            if (value and (1 shl (start + index)) != 0) target += name
        }
    }

    private fun normalizeHat(value: Int) = if (value in 0..7) value else 8

    private fun Byte.u8() = toInt() and 0xFF
    private fun ByteArray.s16(offset: Int) =
        (this[offset].u8() or (this[offset + 1].u8() shl 8)).toShort().toInt()
}
