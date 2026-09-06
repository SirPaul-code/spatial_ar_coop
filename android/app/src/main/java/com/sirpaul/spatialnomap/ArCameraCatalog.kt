package com.sirpaul.spatialnomap

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Session
import java.util.Locale

/**
 * Enumerates only rear camera configs ARCore says are valid for tracking.
 * The normal Android camera app may expose extra physical lenses that ARCore
 * intentionally does not expose as tracking cameras; those are not forced here.
 */
class ArCameraCatalog(
    context: Context,
    session: Session,
) {
    data class Choice(
        val config: CameraConfig,
        val cameraId: String,
        val label: String,
        val approximateZoom: Float?,
        val imageWidth: Int,
        val imageHeight: Int,
        val maxFps: Int,
    ) {
        val detail: String
            get() = "${imageWidth}×${imageHeight} • ${maxFps} fps • AR camera $cameraId"
    }

    val choices: List<Choice>
    val defaultCameraId: String = session.cameraConfig.cameraId

    init {
        val filter = CameraConfigFilter(session)
            .setFacingDirection(CameraConfig.FacingDirection.BACK)
        val supported = session.getSupportedCameraConfigs(filter)

        // ARCore often exposes several CPU-image/FPS variants for one tracking
        // camera. Keep one feature-rich config per camera ID. Alignment frames are
        // downscaled later, so the larger CPU image is useful without increasing
        // network payload.
        val bestPerCamera = supported
            .groupBy { it.cameraId }
            .mapNotNull { (_, configs) ->
                configs.maxWithOrNull(
                    compareBy<CameraConfig> {
                        it.imageSize.width.toLong() * it.imageSize.height.toLong()
                    }.thenBy { it.fpsRange.upper }
                )
            }

        val cameraManager = context.getSystemService(CameraManager::class.java)
        val opticalPower = bestPerCamera.associate { config ->
            config.cameraId to effectiveOpticalPower(cameraManager, config.cameraId)
        }
        val defaultPower = effectiveOpticalPower(cameraManager, defaultCameraId)
            ?: opticalPower.values.filterNotNull().sorted().let { list ->
                if (list.isEmpty()) null else list[list.size / 2]
            }

        choices = bestPerCamera.mapIndexed { index, config ->
            val power = opticalPower[config.cameraId]
            val zoom = if (power != null && defaultPower != null && defaultPower > 0f) {
                (power / defaultPower).coerceIn(0.1f, 20f)
            } else null
            Choice(
                config = config,
                cameraId = config.cameraId,
                label = zoom?.let { formatZoom(it) } ?: "CAM ${index + 1}",
                approximateZoom = zoom,
                imageWidth = config.imageSize.width,
                imageHeight = config.imageSize.height,
                maxFps = config.fpsRange.upper,
            )
        }.sortedWith(compareBy<Choice> { it.approximateZoom ?: Float.MAX_VALUE }.thenBy { it.cameraId })
    }

    fun indexForCameraId(cameraId: String?): Int =
        if (cameraId == null) -1 else choices.indexOfFirst { it.cameraId == cameraId }

    private fun formatZoom(zoom: Float): String {
        val rounded = when {
            kotlin.math.abs(zoom - 0.5f) < 0.12f -> "0.5×"
            kotlin.math.abs(zoom - 0.6f) < 0.12f -> "0.6×"
            kotlin.math.abs(zoom - 1f) < 0.15f -> "1×"
            kotlin.math.abs(zoom - 2f) < 0.22f -> "2×"
            kotlin.math.abs(zoom - 3f) < 0.30f -> "3×"
            kotlin.math.abs(zoom - 5f) < 0.45f -> "5×"
            kotlin.math.abs(zoom - 10f) < 0.8f -> "10×"
            else -> String.format(Locale.US, "%.1f×", zoom)
        }
        return rounded
    }

    private fun effectiveOpticalPower(cameraManager: CameraManager?, cameraId: String): Float? {
        if (cameraManager == null) return null
        return try {
            val c = cameraManager.getCameraCharacteristics(cameraId)
            val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: return null
            val focal = focals.minOrNull() ?: return null
            if (sensor.width <= 0f) null else focal / sensor.width
        } catch (_: Throwable) {
            null
        }
    }
}
