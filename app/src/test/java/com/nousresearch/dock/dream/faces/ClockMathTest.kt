package com.nousresearch.dock.dream.faces

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockMathTest {

    @Test
    fun hourAngle() {
        assertEquals(90f, ClockMath.hourAngle(3, 0, 0), 0.001f)
        assertEquals(90f, ClockMath.hourAngle(15, 0, 0), 0.001f)
        assertEquals(15f, ClockMath.hourAngle(0, 30, 0), 0.001f)
        assertEquals(0f, ClockMath.hourAngle(12, 0, 0), 0.001f)
    }

    @Test
    fun minuteAndSecondAngles() {
        assertEquals(90f, ClockMath.minuteAngle(15, 0), 0.001f)
        assertEquals(3f, ClockMath.minuteAngle(0, 30), 0.001f)
        assertEquals(270f, ClockMath.secondAngle(45), 0.001f)
    }

    @Test
    fun twelveHourMidnightAndNoon() {
        assertEquals("12:05", ClockMath.formatTime(0, 5, false))
        assertEquals("12:00", ClockMath.formatTime(12, 0, false))
        assertEquals("1:07", ClockMath.formatTime(13, 7, false))
    }

    @Test
    fun twentyFourHour() {
        assertEquals("0:05", ClockMath.formatTime(0, 5, true))
        assertEquals("14:30", ClockMath.formatTime(14, 30, true))
    }

    @Test
    fun boundaryDelays() {
        assertEquals(60_000L, ClockMath.msUntilNextMinute(60_000L * 5))
        assertEquals(1L, ClockMath.msUntilNextMinute(60_000L * 5 + 59_999))
        assertEquals(750L, ClockMath.msUntilNextSecond(1_000L * 7 + 250))
    }
}
