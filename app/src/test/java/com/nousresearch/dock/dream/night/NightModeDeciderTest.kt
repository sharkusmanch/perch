package com.nousresearch.dock.dream.night

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeDeciderTest {

    @Test
    fun noSamplesMeansOff() {
        val d = NightModeDecider()
        assertFalse(d.stateAt(10_000))
        assertNull(d.nextDeadlineMs())
    }

    @Test
    fun firstSampleAppliedImmediately() {
        val dark = NightModeDecider().apply { onSample(1f, 0) }
        assertTrue(dark.stateAt(0))

        val bright = NightModeDecider().apply { onSample(100f, 0) }
        assertFalse(bright.stateAt(0))

        val inBand = NightModeDecider().apply { onSample(10f, 0) }
        assertFalse(inBand.stateAt(0))
    }

    // The light sensor is on-change: one reading, then nothing while the room
    // stays dark. Time alone has to carry the decider past the dwell.
    @Test
    fun singleSampleThenSilenceFlipsAtDeadline() {
        val d = NightModeDecider()
        d.onSample(100f, 0)
        d.onSample(1f, 1000)
        assertFalse(d.stateAt(5999))
        assertEquals(6000L, d.nextDeadlineMs())
        assertTrue(d.stateAt(6000))
        assertNull(d.nextDeadlineMs())
    }

    @Test
    fun flickerShorterThanDwellIsIgnored() {
        val d = NightModeDecider()
        d.onSample(100f, 0)
        d.onSample(1f, 1000)
        d.onSample(100f, 3000)
        assertFalse(d.stateAt(10_000))
        assertNull(d.nextDeadlineMs())
    }

    @Test
    fun hysteresisBandHoldsState() {
        val d = NightModeDecider()
        d.onSample(1f, 0)
        d.onSample(10f, 1000)
        assertTrue(d.stateAt(20_000))
    }

    @Test
    fun exitsAfterBrightDwell() {
        val d = NightModeDecider()
        d.onSample(1f, 0)
        d.onSample(50f, 1000)
        assertTrue(d.stateAt(5999))
        assertFalse(d.stateAt(6000))
    }

    @Test
    fun lateSampleAfterExpiredDwellStillFlips() {
        val d = NightModeDecider()
        d.onSample(100f, 0)
        d.onSample(1f, 1000)
        d.onSample(1f, 9000)
        assertTrue(d.stateAt(9000))
    }
}
