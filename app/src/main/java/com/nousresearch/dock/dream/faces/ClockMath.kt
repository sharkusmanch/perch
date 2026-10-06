package com.nousresearch.dock.dream.faces

/** Pure time maths shared by the clock faces. Angles are degrees clockwise from 12 o'clock. */
object ClockMath {

    fun hourAngle(hour: Int, minute: Int, second: Int): Float =
        (hour % 12) * 30f + minute * 0.5f + second / 120f

    fun minuteAngle(minute: Int, second: Int): Float = minute * 6f + second * 0.1f

    fun secondAngle(second: Int): Float = second * 6f

    /** "9:41" style: no leading zero on the hour; 12-hour mode shows 12 for midnight and noon. */
    fun formatTime(hour24: Int, minute: Int, is24Hour: Boolean): String {
        val hour = if (is24Hour) hour24 else (hour24 % 12).let { if (it == 0) 12 else it }
        return "$hour:${minute.toString().padStart(2, '0')}"
    }

    fun msUntilNextMinute(nowMs: Long): Long = 60_000L - Math.floorMod(nowMs, 60_000L)

    fun msUntilNextSecond(nowMs: Long): Long = 1_000L - Math.floorMod(nowMs, 1_000L)
}
