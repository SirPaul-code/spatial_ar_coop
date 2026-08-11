package com.sirpaul.spatialarcoop.ar

import android.app.Activity
import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import com.sirpaul.spatialarcoop.ArActivity
import com.sirpaul.spatialarcoop.util.FileLogger

/**
 * Temporary workaround for the ARCore 1.54 Samsung/Android 16 Session.resume() regression tracked
 * upstream in google-ar/arcore-android-sdk#1762/#1763. On affected Samsung HALs ARCore can fail to
 * register the uncalibrated IMU sensors unless they are already streaming before Session.resume().
 *
 * Keep this narrowly scoped to Samsung API 36+ and only while an [ArActivity] is started. The
 * listener is intentionally a no-op: its only job is to keep the HAL sensor queues in continuous
 * streaming mode before ARCore opens its own sensor sources.
 */
class SamsungArCoreSensorKeepalive private constructor(
    private val application: Application,
    private val logger: FileLogger
) : Application.ActivityLifecycleCallbacks, SensorEventListener {
    private val sensorManager = application.getSystemService(SensorManager::class.java)
    private var workerThread: HandlerThread? = null
    private var active = false
    private val registeredSensors = mutableListOf<Sensor>()

    init {
        application.registerActivityLifecycleCallbacks(this)
        logger.info(
            "Samsung ARCore sensor keepalive installed",
            mapOf("device" to "${Build.MANUFACTURER} ${Build.MODEL}", "sdk" to Build.VERSION.SDK_INT)
        )
    }

    @Synchronized
    private fun start() {
        if (active) return
        val manager = sensorManager ?: run {
            logger.warn("Samsung ARCore sensor keepalive unavailable", mapOf("reason" to "SensorManager missing"))
            return
        }
        val thread = HandlerThread("spatial-arcore-sensor-keepalive").also { it.start() }
        val handler = Handler(thread.looper)
        registeredSensors.clear()

        listOf(
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED
        ).forEach { type ->
            val sensor = manager.getDefaultSensor(type)
            if (sensor == null) {
                logger.warn("Samsung ARCore keepalive sensor missing", mapOf("sensorType" to type))
                return@forEach
            }
            val registered = runCatching {
                manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
            }.getOrElse { error ->
                logger.warn(
                    "Samsung ARCore keepalive sensor registration failed",
                    mapOf("sensorType" to type, "sensor" to sensor.name, "error" to error.message)
                )
                false
            }
            if (registered) registeredSensors += sensor
        }

        if (registeredSensors.isEmpty()) {
            thread.quitSafely()
            logger.warn("Samsung ARCore sensor keepalive could not start")
            return
        }

        workerThread = thread
        active = true
        logger.info(
            "Samsung ARCore sensor keepalive started",
            mapOf(
                "count" to registeredSensors.size,
                "sensors" to registeredSensors.joinToString { "${it.name} (${it.vendor})" }
            )
        )
    }

    @Synchronized
    private fun stop() {
        if (!active) return
        runCatching { sensorManager?.unregisterListener(this) }
        registeredSensors.clear()
        workerThread?.quitSafely()
        workerThread = null
        active = false
        logger.info("Samsung ARCore sensor keepalive stopped")
    }

    override fun onSensorChanged(event: SensorEvent?) = Unit
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onActivityStarted(activity: Activity) {
        if (activity is ArActivity) start()
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity is ArActivity) stop()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (activity is ArActivity) stop()
    }

    companion object {
        internal fun isApplicable(manufacturer: String, sdkInt: Int): Boolean =
            manufacturer.equals("samsung", ignoreCase = true) && sdkInt >= 36

        fun installIfNeeded(application: Application, logger: FileLogger): SamsungArCoreSensorKeepalive? {
            if (!isApplicable(Build.MANUFACTURER, Build.VERSION.SDK_INT)) return null
            return SamsungArCoreSensorKeepalive(application, logger)
        }
    }
}
