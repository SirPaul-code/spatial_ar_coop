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

data class CapturedFrame(
    val timestampNs: Long,
    val pose: PosePacket,
    val intrinsics: IntrinsicsPacket,
    val jpegBase64: String,
    val metricPoints: List<FloatArray>, // [u, v, worldX, worldY, worldZ]
)
