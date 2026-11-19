package com.woodlandweave.run.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import com.woodlandweave.run.sim.tiltFromGravity

/**
 * Reads the gravity component along the screen's horizontal axis. Unlike the old web
 * DeviceOrientation path there is no gimbal flip: the value is continuous in every rotation.
 */
class TiltSensor(
    context: Context,
    private val rotation: () -> Int,
    private val onSample: (raw: Double, angleDegrees: Int, nowMs: Double) -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val smoothing = sensor?.type == Sensor.TYPE_ACCELEROMETER
    private var fx = 0f
    private var fy = 0f
    val available get() = sensor != null

    fun start() { sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        val x: Float; val y: Float
        if (smoothing) { fx += (e.values[0] - fx) * 0.2f; fy += (e.values[1] - fy) * 0.2f; x = fx; y = fy }
        else { x = e.values[0]; y = e.values[1] }
        val rot = rotation()
        // Specific force along the screen's right edge for each display rotation.
        val right = when (rot) {
            Surface.ROTATION_0 -> x
            Surface.ROTATION_90 -> -y
            Surface.ROTATION_180 -> -x
            else -> y
        }
        // Right edge down = positive steering.
        onSample(tiltFromGravity(-right.toDouble()), rot * 90, SystemClock.uptimeMillis().toDouble())
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
}
