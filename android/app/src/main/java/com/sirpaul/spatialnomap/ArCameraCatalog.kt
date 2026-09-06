package com.sirpaul.spatialnomap

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Session
import java.util.Locale
import kotlin.math.max

/**
 * Exposes only rear cameras that ARCore itself reports as compatible.
 * We intentionally do not force an arbitrary Camera2 physical camera into ARCore.
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
    )

    val choices: List<Choice>

    init {
        val filter = CameraConfigFilter(session)
            .setFacingDirection(CameraConfig.FacingDirection.BACK)

        val supported = session.getSupportedCameraConfigs(filter)

        // ARCore can return several CPU/FPS variants for the same physical/logical
        // camera. For lens switching we want one high-quality config per camera ID.
        val bestPerCamera = supported
            .groupBy { it.cameraId }
            .mapNotNull { (_, configs) ->
                configs.maxWithOrNull(
                    compareBy<CameraConfig> {
                        it.imageSize.width.toLong() * it.imageSize.height.toLong()
                    }.thenBy { it.fpsRange.upper }
                )
            }
            .sortedBy { it.cameraId }

        val cameraManager = context.getSystemService(CameraManager::class.java)
        val opticalPower = bestPerCamera.associate { config ->
            config.cameraId to effectiveOpticalPower(cameraManager, config.cameraId)
        }
        val minPower = opticalPower.values.filterNotNull().minOrNull()

        choices = bestPerCamera.mapIndexed { index, config ->
            val power = opticalPower[config.cameraId]
            val zoom = if (power != null && minPower != null && minPower > 0f) {
                max(1f, power / minPower)
            } else null
            Choice(
                config = config,
                cameraId = config.cameraId,
                label = zoom?.let { String.format(Locale.US, "%.1f×", it) } ?: "CAM ${index + 1}",
                approximateZoom = zoom,
            )
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
}
