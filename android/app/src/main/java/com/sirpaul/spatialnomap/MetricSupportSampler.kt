package com.sirpaul.spatialnomap

import android.media.Image
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

object MetricSupportSampler {
    fun sample(frame: Frame, camera: Camera, maxPoints: Int = 1800): List<FloatArray> {
        val depth = tryDepth(frame, camera, maxPoints)
        if (depth.size >= 24) return depth
        return samplePointCloud(frame, camera, maxPoints)
    }

    fun pointAtCpuPixel(frame: Frame, camera: Camera, u: Float, v: Float): FloatArray? {
        return try {
            frame.acquireDepthImage16Bits().use { image ->
                val tex = FloatArray(2)
                frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, floatArrayOf(u, v), Coordinates2d.TEXTURE_NORMALIZED, tex)
                if (tex[0] !in 0f..1f || tex[1] !in 0f..1f) return null
                val cx = (tex[0] * image.width).toInt().coerceIn(0, image.width - 1)
                val cy = (tex[1] * image.height).toInt().coerceIn(0, image.height - 1)
                val samples = ArrayList<Int>(25)
                for (dy in -2..2) for (dx in -2..2) {
                    val x = (cx + dx).coerceIn(0, image.width - 1)
                    val y = (cy + dy).coerceIn(0, image.height - 1)
                    val mm = depthMm(image, x, y)
                    if (mm in 150..65000) samples += mm
                }
                if (samples.size < 4) return null
                samples.sort()
                val medianMm = samples[samples.size / 2]
                val deviations = samples.map { kotlin.math.abs(it - medianMm) }.sorted()
                val mad = deviations[deviations.size / 2]
                if (mad > max(80, (medianMm * 0.08f).toInt())) return null
                unprojectToWorld(camera, u, v, medianMm / 1000f)
            }
        } catch (_: NotYetAvailableException) { null } catch (_: Throwable) { null }
    }

    private fun tryDepth(frame: Frame, camera: Camera, maxPoints: Int): List<FloatArray> {
        return try {
            frame.acquireDepthImage16Bits().use { image ->
                val total = image.width * image.height
                val step = max(1, ceil(sqrt(total.toDouble() / maxPoints.toDouble())).toInt())
                val texList = ArrayList<Float>(maxPoints * 2); val depths = ArrayList<Int>(maxPoints)
                var y = step / 2
                while (y < image.height) {
                    var x = step / 2
                    while (x < image.width) {
                        val mm = depthMm(image, x, y)
                        if (mm in 150..65000) { texList.add((x + 0.5f) / image.width); texList.add((y + 0.5f) / image.height); depths.add(mm) }
                        x += step
                    }
                    y += step
                }
                if (depths.isEmpty()) return emptyList()
                val tex = FloatArray(texList.size) { texList[it] }; val cpu = FloatArray(tex.size)
                frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED, tex, Coordinates2d.IMAGE_PIXELS, cpu)
                val out = ArrayList<FloatArray>(depths.size)
                for (i in depths.indices) {
                    val pu = cpu[i * 2]; val pv = cpu[i * 2 + 1]
                    if (!pu.isFinite() || !pv.isFinite() || pu < 0 || pv < 0) continue
                    val world = unprojectToWorld(camera, pu, pv, depths[i] / 1000f)
                    out.add(floatArrayOf(pu, pv, world[0], world[1], world[2]))
                }
                out
            }
        } catch (_: NotYetAvailableException) { emptyList() } catch (_: Throwable) { emptyList() }
    }

    private fun samplePointCloud(frame: Frame, camera: Camera, maxPoints: Int): List<FloatArray> {
        return try {
            frame.acquirePointCloud().use { cloud ->
                val buffer = cloud.points; val count = buffer.remaining() / 4
                if (count <= 0) return emptyList()
                val stride = max(1, count / maxPoints)
                val intr = camera.imageIntrinsics; val f = intr.focalLength; val pp = intr.principalPoint; val dims = intr.imageDimensions
                val camFromWorld = camera.pose.inverse(); val out = ArrayList<FloatArray>(minOf(maxPoints, count))
                for (i in 0 until count step stride) {
                    val base = i * 4; val confidence = buffer.get(base + 3); if (confidence < 0.2f) continue
                    val world = floatArrayOf(buffer.get(base), buffer.get(base + 1), buffer.get(base + 2)); val ca = camFromWorld.transformPoint(world)
                    val z = -ca[2]; if (z <= 0.05f) continue
                    val pu = f[0] * ca[0] / z + pp[0]; val pv = f[1] * (-ca[1]) / z + pp[1]
                    if (pu !in 0f..dims[0].toFloat() || pv !in 0f..dims[1].toFloat()) continue
                    out.add(floatArrayOf(pu, pv, world[0], world[1], world[2])); if (out.size >= maxPoints) break
                }
                out
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun depthMm(image: Image, x: Int, y: Int): Int {
        val plane = image.planes[0]; val index = x * plane.pixelStride + y * plane.rowStride
        val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        return java.lang.Short.toUnsignedInt(buffer.getShort(index))
    }

    private fun unprojectToWorld(camera: Camera, u: Float, v: Float, depthM: Float): FloatArray {
        val intr = camera.imageIntrinsics; val f = intr.focalLength; val pp = intr.principalPoint
        val xCv = (u - pp[0]) / f[0] * depthM; val yCv = (v - pp[1]) / f[1] * depthM
        return camera.pose.transformPoint(floatArrayOf(xCv, -yCv, -depthM))
    }
}
