package net.asksakis.massdroidv2.data.proximity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MotionGate"
private const val MOVEMENT_WINDOW_MS = 30_000L

@Singleton
class MotionGate @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _isMoving = MutableStateFlow(false)
    val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var windowJob: Job? = null
    private var triggerListener: TriggerEventListener? = null
    private var stepListener: SensorEventListener? = null
    private var started = false
    private var stepCount = 0

    fun start() {
        if (started || sensorManager == null) return
        if (!hasMotionPermission()) {
            Log.w(TAG, "Start skipped: ACTIVITY_RECOGNITION permission missing")
            return
        }
        started = true
        stepCount = 0

        try {
            val motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
            if (motionSensor != null) {
                triggerListener = object : TriggerEventListener() {
                    override fun onTrigger(event: TriggerEvent?) {
                        if (!started) return
                        Log.d(TAG, "Significant motion")
                        openMovementWindow()
                        sensorManager.requestTriggerSensor(this, motionSensor)
                    }
                }
                sensorManager.requestTriggerSensor(triggerListener, motionSensor)
            }

            val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            if (stepSensor != null) {
                stepListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        if (!started) return
                        stepCount++
                        openMovementWindow()
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sensorManager.registerListener(stepListener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
            }

            Log.d(TAG, "Started (motion=${motionSensor != null}, steps=${stepSensor != null})")
        } catch (e: SecurityException) {
            cleanupRegisteredSensors()
            started = false
            Log.w(TAG, "Start failed: missing activity permission", e)
        } catch (e: Exception) {
            cleanupRegisteredSensors()
            started = false
            Log.w(TAG, "Start failed: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        val motionSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (motionSensor != null && triggerListener != null) {
            sensorManager?.cancelTriggerSensor(triggerListener, motionSensor)
        }
        triggerListener = null
        stepListener?.let { sensorManager?.unregisterListener(it) }
        stepListener = null
        windowJob?.cancel()
        windowJob = null
        _isMoving.value = false
        stepCount = 0
        Log.d(TAG, "Stopped")
    }

    private fun cleanupRegisteredSensors() {
        val motionSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (motionSensor != null && triggerListener != null) {
            sensorManager?.cancelTriggerSensor(triggerListener, motionSensor)
        }
        triggerListener = null
        stepListener?.let { sensorManager?.unregisterListener(it) }
        stepListener = null
    }

    private fun openMovementWindow() {
        val wasMoving = _isMoving.value
        _isMoving.value = true
        windowJob?.cancel()
        if (!wasMoving) Log.d(TAG, "Window opened (steps=$stepCount)")
        windowJob = scope.launch {
            delay(MOVEMENT_WINDOW_MS)
            _isMoving.value = false
            Log.d(TAG, "Window closed (steps=$stepCount)")
        }
    }

    private fun hasMotionPermission(): Boolean {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    }
}
