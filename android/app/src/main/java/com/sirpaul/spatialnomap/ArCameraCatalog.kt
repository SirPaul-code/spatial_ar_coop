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
 *
 * Tracking quality has priority over CPU-image resolution. ARCore itself ranks
 * stereo usage first, then 60 fps, then hardware depth; keep the same ordering
 * when collapsing duplicate CPU-stream variants for one physical camera ID.
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
        val stereo: Boolean,
        val hardwareDepth: Boolean,
    ) {
        val detail: String
            get() = buildString {
                append("${imageWidth}×${imageHeight} • ${maxFps} fps")
                if (stereo) append(" • stereo VIO")
                if (hardwareDepth) append(" • HW depth")
                append(" • AR camera $cameraId")
            }
    }

    val choices: List<Choice>
    val defaultCameraId: String = session.cameraConfig.cameraId

    init {
        val filter = CameraConfigFilter(session)
            .setFacingDirection(CameraConfig.FacingDirection.BACK)
        val supported = session.getSupportedCameraConfigs(filter)

        val bestPerCamera = supported
            .groupBy { it.cameraId }
            .mapNotNull { (_, configs) -> configs.maxWithOrNull(TRACKING_CONFIG_COMPARATOR) }

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
                stereo = config.stereoCameraUsage == CameraConfig.StereoCameraUsage.REQUIRE_AND_USE,
                hardwareDepth = config.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE,
            )
        }.sortedWith(
            compareByDescending<Choice> { it.stereo }
                .thenByDescending { it.maxFps }
                .thenByDescending { it.hardwareDepth }
                .thenBy { it.approximateZoom ?: Float.MAX_VALUE }
                .thenBy { it.cameraId },
        )
    }

    fun indexForCameraId(cameraId: String?): Int =
        if (cameraId == null) -1 else choices.indexOfFirst { it.cameraId == cameraId }

    private fun formatZoom(zoom: Float): String {
        return when {
            kotlin.math.abs(zoom - 0.5f) < 0.12f -> "0.5×"
            kotlin.math.abs(zoom - 0.6f) < 0.12f -> "0.6×"
            kotlin.math.abs(zoom - 1f) < 0.15f -> "1×"
            kotlin.math.abs(zoom - 2f) < 0.22f -> "2×"
            kotlin.math.abs(zoom - 3f) < 0.30f -> "3×"
            kotlin.math.abs(zoom - 5f) < 0.45f -> "5×"
            kotlin.math.abs(zoom - 10f) < 0.8f -> "10×"
            else -> String.format(Locale.US, "%.1f×", zoom)
        }
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

    companion object {
        private val TRACKING_CONFIG_COMPARATOR =
            compareBy<CameraConfig> {
                if (it.stereoCameraUsage == CameraConfig.StereoCameraUsage.REQUIRE_AND_USE) 1 else 0
            }.thenBy {
                if (it.fpsRange.upper >= 60) 1 else 0
            }.thenBy {
                if (it.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE) 1 else 0
            }.thenBy {
                it.imageSize.width.toLong() * it.imageSize.height.toLong()
            }
    }
}
