package com.sirpaul.spatialnomap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lightweight local sensor fusion used as an absolute prior for AR-to-AR
 * registration. ARCore remains the metric local tracker; these measurements do
 * not replace it, they constrain yaw/translation and reject visual outliers.
 */
@SuppressLint("MissingPermission")
class SpatialSensorFusion(private val context: Context) : SensorEventListener, LocationListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val location = context.getSystemService(LocationManager::class.java)

    private val rotationSensor: Sensor? =
        sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensors?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val pressureSensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val gyroSensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile private var started = false
    @Volatile private var headingDeg = Float.NaN
    @Volatile private var pitchDeg = Float.NaN
    @Volatile private var rollDeg = Float.NaN
    @Volatile private var orientationQuality = 0f
    @Volatile private var pressureHpa = Float.NaN
    @Volatile private var gyro = floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
    @Volatile private var latestLocation: Location? = null
    @Volatile private var rotationAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

    fun start() {
        if (started) return
        started = true
        rotationSensor?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        pressureSensor?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyroSensor?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        startLocationIfPermitted()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { sensors?.unregisterListener(this) }
        runCatching { location?.removeUpdates(this) }
    }

    fun restartLocation() {
        if (!started) return
        runCatching { location?.removeUpdates(this) }
        startLocationIfPermitted()
    }

    fun snapshot(): SensorSnapshot {
        val loc = latestLocation
        return SensorSnapshot(
            elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
            headingDeg = headingDeg,
            pitchDeg = pitchDeg,
            rollDeg = rollDeg,
            orientationQuality = orientationQuality,
            latitudeDeg = loc?.latitude ?: Double.NaN,
            longitudeDeg = loc?.longitude ?: Double.NaN,
            altitudeM = loc?.takeIf { it.hasAltitude() }?.altitude ?: Double.NaN,
            horizontalAccuracyM = loc?.takeIf { it.hasAccuracy() }?.accuracy ?: Float.POSITIVE_INFINITY,
            verticalAccuracyM = loc?.takeIf { it.hasVerticalAccuracy() }?.verticalAccuracyMeters ?: Float.POSITIVE_INFINITY,
            pressureHpa = pressureHpa,
            gyroRadS = gyro.copyOf(),
        )
    }

    fun summary(): String {
        val s = snapshot()
        val heading = if (s.hasHeading) "head %.0f°".format(s.headingDeg) else "head —"
        val gps = if (s.hasLocation) "gps ±%.0fm".format(s.horizontalAccuracyM) else "gps —"
        val pressure = if (s.pressureHpa.isFinite()) "baro ✓" else "baro —"
        return "$heading • $gps • $pressure"
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> updateOrientation(event.values)
            Sensor.TYPE_PRESSURE -> pressureHpa = event.values.firstOrNull() ?: Float.NaN
            Sensor.TYPE_GYROSCOPE -> if (event.values.size >= 3) {
                gyro = floatArrayOf(event.values[0], event.values[1], event.values[2])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR || sensor?.type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) {
            rotationAccuracy = accuracy
        }
    }

    private fun updateOrientation(values: FloatArray) {
        val r = FloatArray(9)
        runCatching { SensorManager.getRotationMatrixFromVector(r, values) }.getOrElse { return }

        // Android Earth frame: +X east, +Y geomagnetic north, +Z sky.
        // Device rear camera looks approximately along device -Z.
        val fx = -r[2]
        val fy = -r[5]
        val fz = -r[8]
        val horizontal = sqrt(fx * fx + fy * fy)
        if (horizontal < 0.08f) {
            orientationQuality = 0f
            return
        }

        var heading = Math.toDegrees(atan2(fx.toDouble(), fy.toDouble())).toFloat()
        if (heading < 0f) heading += 360f
        headingDeg = heading
        pitchDeg = Math.toDegrees(asin(fz.coerceIn(-1f, 1f).toDouble())).toFloat()

        // Device +Y projected around the camera-forward axis gives a stable
        // advisory roll value; exact roll is not used to force registration.
        val ux = r[1]
        val uy = r[4]
        rollDeg = Math.toDegrees(atan2(ux.toDouble(), max(1e-6f, uy).toDouble())).toFloat()

        val accuracyFactor = when (rotationAccuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 1f
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 0.72f
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 0.42f
            else -> 0.20f
        }
        orientationQuality = (accuracyFactor * horizontal.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    private fun startLocationIfPermitted() {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            val enabled = runCatching { location?.isProviderEnabled(provider) == true }.getOrDefault(false)
            if (!enabled) continue
            runCatching {
                location?.getLastKnownLocation(provider)?.let { considerLocation(it) }
                location?.requestLocationUpdates(provider, 500L, 0.25f, this, Looper.getMainLooper())
            }
        }
    }

    override fun onLocationChanged(location: Location) = considerLocation(location)
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    @Deprecated("Deprecated in Android API")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun considerLocation(candidate: Location) {
        val old = latestLocation
        if (old == null) {
            latestLocation = Location(candidate)
            return
        }

        val ageDeltaMs = candidate.elapsedRealtimeNanos / 1_000_000L - old.elapsedRealtimeNanos / 1_000_000L
        val clearlyNewer = ageDeltaMs > 1500L
        val materiallyMoreAccurate = candidate.hasAccuracy() && (!old.hasAccuracy() || candidate.accuracy + 1f < old.accuracy)
        if (clearlyNewer || materiallyMoreAccurate || ageDeltaMs >= 0L && candidate.accuracy <= old.accuracy * 1.35f) {
            latestLocation = Location(candidate)
        }
    }
}
