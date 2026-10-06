package com.nousresearch.dock.dream.night

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NightTintTest {

    private val identity = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    private val red = floatArrayOf(
        0.299f, 0.587f, 0.114f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    @Test
    fun zeroIsIdentity() {
        assertArrayEquals(identity, NightTint.matrix(0f), 0.0001f)
    }

    @Test
    fun oneMapsLuminanceToRedOnly() {
        assertArrayEquals(red, NightTint.matrix(1f), 0.0001f)
    }

    @Test
    fun halfwayScalesGreen() {
        assertEquals(0.5f, NightTint.matrix(0.5f)[6], 0.0001f)
    }

    @Test
    fun outOfRangeClamps() {
        assertArrayEquals(identity, NightTint.matrix(-1f), 0.0001f)
        assertArrayEquals(red, NightTint.matrix(2f), 0.0001f)
    }
}
