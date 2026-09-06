package com.sirpaul.spatialnomap

data class IntrinsicsPacket(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
)

data class PosePacket(
    val t: FloatArray,
    val q: FloatArray,
)

/**
 * Absolute/ambient measurements captured close to an AR camera frame.
 * NaN/+INF mean that a source is unavailable or currently untrusted.
 *
 * headingDeg is the physical rear-camera forward azimuth in the Android Earth
 * frame: 0=north, +90=east. Pitch/roll are advisory UI/fusion signals only.
 */
data class SensorSnapshot(
    val elapsedRealtimeNs: Long = 0L,
    val headingDeg: Float = Float.NaN,
    val pitchDeg: Float = Float.NaN,
    val rollDeg: Float = Float.NaN,
    val orientationQuality: Float = 0f,
    val latitudeDeg: Double = Double.NaN,
    val longitudeDeg: Double = Double.NaN,
    val altitudeM: Double = Double.NaN,
    val horizontalAccuracyM: Float = Float.POSITIVE_INFINITY,
    val verticalAccuracyM: Float = Float.POSITIVE_INFINITY,
    val pressureHpa: Float = Float.NaN,
    val gyroRadS: FloatArray = floatArrayOf(Float.NaN, Float.NaN, Float.NaN),
) {
    val hasHeading: Boolean
        get() = headingDeg.isFinite() && orientationQuality > 0.05f

    val hasLocation: Boolean
        get() = latitudeDeg.isFinite() && longitudeDeg.isFinite() && horizontalAccuracyM.isFinite()
}

data class CapturedFrame(
    val timestampNs: Long,
    val pose: PosePacket,
    val intrinsics: IntrinsicsPacket,
    val jpegBase64: String,
    val metricPoints: List<FloatArray>, // [u, v, worldX, worldY, worldZ]
    val sensors: SensorSnapshot = SensorSnapshot(),
)
