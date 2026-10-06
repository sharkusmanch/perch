package com.nousresearch.dock.dream.night

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Feeds the ambient light sensor into a [NightModeDecider] and reports when
 * Night Mode should switch. Without a light sensor it never reports.
 */
class NightModeController(
    context: Context,
    private val onChange: (Boolean) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val lightSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val handler = Handler(Looper.getMainLooper())
    private val evaluateRunnable = Runnable { evaluate() }
    private var decider = NightModeDecider()
    private var reported = false
    private var running = false

    fun start() {
        val sensor = lightSensor ?: return
        decider = NightModeDecider()
        reported = false
        running = true
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        running = false
        sensorManager?.unregisterListener(this)
        handler.removeCallbacks(evaluateRunnable)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // An event already queued when stop() ran can still be delivered.
        if (!running || event.sensor.type != Sensor.TYPE_LIGHT) return
        decider.onSample(event.values[0], SystemClock.elapsedRealtime())
        evaluate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // The sensor only reports changes, so a pending switch is completed by
    // the clock: re-check when the decider's dwell runs out.
    private fun evaluate() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        val state = decider.stateAt(now)
        if (state != reported) {
            reported = state
            onChange(state)
        }
        handler.removeCallbacks(evaluateRunnable)
        decider.nextDeadlineMs()?.let { deadline ->
            handler.postDelayed(evaluateRunnable, (deadline - now).coerceAtLeast(0L))
        }
    }
}
