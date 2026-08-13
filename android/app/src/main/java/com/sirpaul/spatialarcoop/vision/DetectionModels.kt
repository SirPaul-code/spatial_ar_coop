package com.sirpaul.spatialarcoop.vision

import android.graphics.RectF

data class CaptureGeometry(
    val siteFromCamera: FloatArray?,
    val focalLength: FloatArray,
    val principalPoint: FloatArray,
    val depthSnapshot: DepthSnapshot? = null
)

data class PoseLandmark2D(
    val index: Int,
    val x: Float,
    val y: Float,
    val confidence: Float
)

data class Detection2D(
    val label: String,
    val confidence: Float,
    val rawBoundingBox: RectF,
    val rawBottomCenter: FloatArray,
    val capturedAtMs: Long,
    val rawImageWidth: Int,
    val rawImageHeight: Int,
    val temporalId: String? = null,
    val temporallyConfirmed: Boolean = true,
    val captureGeometry: CaptureGeometry? = null,
    val poseLandmarks: List<PoseLandmark2D> = emptyList()
)
