package com.prash.petcare.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Detects the phone being shaken, using the accelerometer.
 *
 * **How a shake is recognised.** The accelerometer reports acceleration on three axes, and at rest
 * that is not zero — gravity alone registers about 9.81 m/s² on whichever axis points down. Taking
 * the magnitude across all three axes and dividing by gravity gives a figure that sits near 1.0
 * when the phone is still, however it happens to be lying. Anything well above that is real
 * movement, which is why the threshold is expressed in multiples of gravity rather than raw
 * readings.
 *
 * **Why it counts several movements.** One spike is a bump — setting the phone down, or a jolt in a
 * pocket. A shake is a repeated back-and-forth, so a gesture only counts once [REQUIRED_MOVEMENTS]
 * spikes arrive close together. [MIN_MILLIS_BETWEEN_MOVEMENTS] then stops a single fast motion
 * registering several times, since the sensor reports far quicker than a wrist can change direction.
 *
 * **Battery.** A registered sensor listener keeps hardware powered and the process busy, so the
 * caller must [stop] it whenever the screen is not in front of the user — pairing [start] with
 * `onResume` and [stop] with `onPause`.
 *
 * @param onShake Called on the main thread when a shake is recognised.
 */
class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    private var sensorManager: SensorManager? = null

    /** How many qualifying movements have arrived in the current window. */
    private var movementCount = 0

    /** When the last qualifying movement was seen, as epoch milliseconds. */
    private var lastMovementAt = 0L

    /**
     * Starts listening.
     *
     * Returns false if the device has no accelerometer — a real possibility on cheap hardware and
     * on emulators — so the caller can leave the gesture out rather than wait for an event that
     * will never arrive.
     */
    fun start(context: Context): Boolean {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return false

        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false

        sensorManager = manager
        resetCounting()

        // SENSOR_DELAY_UI rather than the fastest rate: a shake is a coarse gesture, and sampling
        // as fast as the hardware allows would burn battery for readings this never looks at.
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

        // Magnitude across all three axes, expressed in multiples of gravity. Combining the axes
        // this way makes the result independent of how the phone is being held.
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        if (gForce <= SHAKE_THRESHOLD_G) return

        val now = System.currentTimeMillis()

        // Ignore readings arriving on the heels of the last one: they are the same motion still in
        // progress, not a separate back-and-forth.
        if (now - lastMovementAt < MIN_MILLIS_BETWEEN_MOVEMENTS) return

        // Too long since the last movement means the earlier ones were unrelated, so counting
        // starts again rather than accumulating stray bumps over minutes into a false positive.
        if (now - lastMovementAt > MOVEMENT_WINDOW_MILLIS) {
            movementCount = 0
        }

        lastMovementAt = now
        movementCount++

        if (movementCount >= REQUIRED_MOVEMENTS) {
            // Cleared before notifying, so the movements that triggered this gesture cannot also
            // count towards the next one.
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
        /**
         * How hard the phone must move to count, in multiples of gravity.
         *
         * At rest the reading is about 1.0, so 2.2 is comfortably clear of normal handling —
         * walking with the phone or putting it down will not reach it — while still being easy to
         * produce deliberately.
         */
        private const val SHAKE_THRESHOLD_G = 2.2f

        /** How many movements make a shake rather than a bump. */
        private const val REQUIRED_MOVEMENTS = 3

        /** Movements further apart than this are treated as unrelated. */
        private const val MOVEMENT_WINDOW_MILLIS = 1_000L

        /** Readings closer together than this are the same motion, not a new one. */
        private const val MIN_MILLIS_BETWEEN_MOVEMENTS = 150L
    }
}
