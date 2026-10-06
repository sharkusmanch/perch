package com.nousresearch.dock.dream.night

/**
 * Decides whether Night Mode is on from ambient light readings.
 *
 * The light sensor is on-change: it reports once when registered and then
 * stays silent while the room is steady, so the dwell is measured against
 * the clock ([stateAt]) rather than against a stream of samples. Callers
 * re-query at [nextDeadlineMs].
 *
 * The first sample is applied immediately. After that the state flips only
 * once the light has stayed past the opposite threshold for [dwellMs];
 * between the two thresholds it holds.
 */
class NightModeDecider(
    private val enterLux: Float = 5f,
    private val exitLux: Float = 15f,
    private val dwellMs: Long = 5_000L
) {
    private var on = false
    private var hasSample = false
    private var pendingSinceMs: Long? = null

    fun onSample(lux: Float, nowMs: Long) {
        if (!hasSample) {
            hasSample = true
            on = lux < enterLux
            return
        }
        // A dwell that ran out before this sample arrived held for its whole
        // duration, so it counts even if this sample contradicts it.
        advance(nowMs)
        val wantsFlip = if (on) lux > exitLux else lux < enterLux
        if (!wantsFlip) {
            pendingSinceMs = null
        } else if (pendingSinceMs == null) {
            pendingSinceMs = nowMs
        }
    }

    fun stateAt(nowMs: Long): Boolean {
        advance(nowMs)
        return on
    }

    /** When a pending flip takes effect, or null if nothing is pending. */
    fun nextDeadlineMs(): Long? = pendingSinceMs?.plus(dwellMs)

    private fun advance(nowMs: Long) {
        val since = pendingSinceMs ?: return
        if (nowMs - since >= dwellMs) {
            on = !on
            pendingSinceMs = null
        }
    }
}
