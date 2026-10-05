package com.prash.petcare.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/** Detects the phone being shaken, using the accelerometer. */
class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    private var sensorManager: SensorManager? = null

    /** How many qualifying movements have arrived in the current window. */
    private var movementCount = 0

    /** When the last qualifying movement was seen, as epoch milliseconds. */
    private var lastMovementAt = 0L

    /** Starts listening. */
    fun start(context: Context): Boolean {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return false

        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false

        sensorManager = manager
        resetCounting()

        // SENSOR_DELAY_UI rather than the fastest rate: a shake is a coarse gesture.
        manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        return true
    }

    /** Stops listening. Safe to call when not started. */
    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        resetCounting()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Magnitude across all three axes, expressed in multiples of gravity.
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        if (gForce <= SHAKE_THRESHOLD_G) return

        val now = System.currentTimeMillis()

        // Ignore readings arriving on the heels of the last one: they are the same motion still in progress.
        if (now - lastMovementAt < MIN_MILLIS_BETWEEN_MOVEMENTS) return

        // Too long since the last movement means the earlier ones were unrelated, so counting starts again.
        if (now - lastMovementAt > MOVEMENT_WINDOW_MILLIS) {
            movementCount = 0
        }

        lastMovementAt = now
        movementCount++

        if (movementCount >= REQUIRED_MOVEMENTS) {
            // Cleared before notifying.
            resetCounting()
            onShake()
        }
    }

    /** Required by the interface; the app does not change behaviour with sensor accuracy. */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun resetCounting() {
        movementCount = 0
        lastMovementAt = 0L
    }

    companion object {
        /** How hard the phone must move to count, in multiples of gravity. */
        private const val SHAKE_THRESHOLD_G = 2.2f

        /** How many movements make a shake rather than a bump. */
        private const val REQUIRED_MOVEMENTS = 3

        /** Movements further apart than this are treated as unrelated. */
        private const val MOVEMENT_WINDOW_MILLIS = 1_000L

        /** Readings closer together than this are the same motion, not a new one. */
        private const val MIN_MILLIS_BETWEEN_MOVEMENTS = 150L
    }
}
