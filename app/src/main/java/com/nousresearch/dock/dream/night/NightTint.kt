package com.nousresearch.dock.dream.night

/** Colour-matrix values for the Night Mode tint, in android.graphics.ColorMatrix layout (4x5, row-major). */
object NightTint {

    private const val LUM_R = 0.299f
    private const val LUM_G = 0.587f
    private const val LUM_B = 0.114f

    /** [fraction] 0 leaves colours unchanged; 1 puts each pixel's luminance on the red channel alone. */
    fun matrix(fraction: Float): FloatArray {
        val f = fraction.coerceIn(0f, 1f)
        val keep = 1f - f
        return floatArrayOf(
            keep + f * LUM_R, f * LUM_G, f * LUM_B, 0f, 0f,
            0f, keep, 0f, 0f, 0f,
            0f, 0f, keep, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    }
}
