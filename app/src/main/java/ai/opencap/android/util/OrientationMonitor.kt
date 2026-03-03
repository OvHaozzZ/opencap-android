package ai.opencap.android.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs

enum class PhysicalOrientation(val rotation: Float) {
    PORTRAIT(0f),
    PORTRAIT_UPSIDE_DOWN(180f),
    LANDSCAPE_LEFT(90f),
    LANDSCAPE_RIGHT(-90f),
    UNKNOWN(0f)
}

class OrientationMonitor(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var callback: ((PhysicalOrientation) -> Unit)? = null
    private var lastOrientation: PhysicalOrientation = PhysicalOrientation.UNKNOWN
    private var lastDispatchMs: Long = 0

    fun start(onChanged: (PhysicalOrientation) -> Unit) {
        callback = onChanged
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        callback = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDispatchMs < 2000) return
        val x = event.values[0]
        val y = event.values[1]
        val split = 7.35f
        val orientation = when {
            x >= split -> PhysicalOrientation.LANDSCAPE_LEFT
            x <= -split -> PhysicalOrientation.LANDSCAPE_RIGHT
            y <= -split -> PhysicalOrientation.PORTRAIT
            y >= split -> PhysicalOrientation.PORTRAIT_UPSIDE_DOWN
            else -> PhysicalOrientation.UNKNOWN
        }
        if (orientation != PhysicalOrientation.UNKNOWN && orientation != lastOrientation) {
            lastOrientation = orientation
            lastDispatchMs = now
            callback?.invoke(orientation)
        } else if (abs(x) < split && abs(y) < split) {
            lastDispatchMs = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
