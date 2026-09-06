package com.sirpaul.spatialnomap

import android.media.Image
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Metric geometry sampler for cross-device registration and POI placement.
 *
 * Production policy:
 *  1. Prefer high-confidence ARCore Raw Depth as the metric backbone.
 *  2. Never throw away dense full depth just because a small raw set exists:
 *     raw depth is intentionally sparse, so full depth fills uncovered image cells.
 *  3. Reject raw pixels below the ARCore confidence midpoint (128/255).
 *  4. Fall back to ARCore's tracked point cloud when depth is unavailable.
 *
 * A single depth pixel is never trusted. All paths use a local robust median
 * with an edge/noise gate before converting the measurement to ARCore world.
 */
object MetricSupportSampler {
    private const val RAW_CONFIDENCE_MIN = 128
    private const val MIN_DEPTH_MM = 150
    private const val MAX_DEPTH_MM = 65_000
    private const val MERGE_CELL_PX = 10f

    fun sample(frame: Frame, camera: Camera, maxPoints: Int = 2200): List<FloatArray> {
        // Raw depth is more accurate but can contain only a few dozen confident
        // samples. Returning it exclusively was starving SIFT->metric association
        // on otherwise good frames. Keep raw as the first-class support and fill
        // the holes with dense ARCore depth from the same frame.
        val raw = tryRawDepth(frame, camera, maxPoints)
        val full = tryFullDepth(frame, camera, maxPoints)
        val merged = mergeSupports(raw, full, maxPoints)
        if (merged.size >= 24) return merged

        val cloud = samplePointCloud(frame, camera, maxPoints)
        return mergeSupports(merged, cloud, maxPoints)
    }

    fun pointAtCpuPixel(frame: Frame, camera: Camera, u: Float, v: Float): FloatArray? {
        pointAtCpuPixelRaw(frame, camera, u, v)?.let { return it }
        return pointAtCpuPixelFull(frame, camera, u, v)
    }

    private fun pointAtCpuPixelRaw(frame: Frame, camera: Camera, u: Float, v: Float): FloatArray? {
        return try {
            frame.acquireRawDepthImage16Bits().use { depthImage ->
                frame.acquireRawDepthConfidenceImage().use { confidenceImage ->
                    if (depthImage.width != confidenceImage.width || depthImage.height != confidenceImage.height) {
                        return null
                    }
                    val xy = cpuPixelToDepth(frame, u, v, depthImage.width, depthImage.height) ?: return null
                    val depth = DepthAccessor(depthImage)
                    val confidence = ConfidenceAccessor(confidenceImage)
                    val medianMm = robustRawDepthMm(
                        depth = depth,
                        confidence = confidence,
                        cx = xy.first,
                        cy = xy.second,
                        radius = 3,
                        minSamples = 3,
                    ) ?: return null
                    unprojectToWorld(camera, u, v, medianMm / 1000f)
                }
            }
        } catch (_: NotYetAvailableException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun pointAtCpuPixelFull(frame: Frame, camera: Camera, u: Float, v: Float): FloatArray? {
        return try {
            frame.acquireDepthImage16Bits().use { image ->
                val xy = cpuPixelToDepth(frame, u, v, image.width, image.height) ?: return null
                val depth = DepthAccessor(image)
                val medianMm = robustDepthMm(depth, xy.first, xy.second, radius = 2, minSamples = 4) ?: return null
                unprojectToWorld(camera, u, v, medianMm / 1000f)
            }
        } catch (_: NotYetAvailableException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Raw depth is sparse but higher-fidelity. We keep only confident samples
     * and use a confidence-weighted local robust median at every support cell.
     */
    private fun tryRawDepth(frame: Frame, camera: Camera, maxPoints: Int): List<FloatArray> {
        return try {
            frame.acquireRawDepthImage16Bits().use { depthImage ->
                frame.acquireRawDepthConfidenceImage().use { confidenceImage ->
                    if (depthImage.width != confidenceImage.width || depthImage.height != confidenceImage.height) {
                        return emptyList()
                    }
                    val depth = DepthAccessor(depthImage)
                    val confidence = ConfidenceAccessor(confidenceImage)
                    val total = depthImage.width * depthImage.height
                    val step = max(1, ceil(sqrt(total.toDouble() / maxPoints.toDouble())).toInt())
                    val texList = ArrayList<Float>(maxPoints * 2)
                    val depths = ArrayList<Int>(maxPoints)

                    var y = step / 2
                    while (y < depthImage.height) {
                        var x = step / 2
                        while (x < depthImage.width) {
                            val mm = robustRawDepthMm(
                                depth = depth,
                                confidence = confidence,
                                cx = x,
                                cy = y,
                                radius = 1,
                                minSamples = 2,
                            )
                            if (mm != null) {
                                texList.add((x + 0.5f) / depthImage.width)
                                texList.add((y + 0.5f) / depthImage.height)
                                depths.add(mm)
                            }
                            x += step
                        }
                        y += step
                    }
                    supportsFromDepth(frame, camera, texList, depths, maxPoints)
                }
            }
        } catch (_: NotYetAvailableException) {
            emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Full ARCore depth is denser and temporally smoothed; it fills raw-depth holes. */
    private fun tryFullDepth(frame: Frame, camera: Camera, maxPoints: Int): List<FloatArray> {
        return try {
            frame.acquireDepthImage16Bits().use { image ->
                val accessor = DepthAccessor(image)
                val total = image.width * image.height
                val step = max(1, ceil(sqrt(total.toDouble() / maxPoints.toDouble())).toInt())
                val texList = ArrayList<Float>(maxPoints * 2)
                val depths = ArrayList<Int>(maxPoints)
                var y = step / 2
                while (y < image.height) {
                    var x = step / 2
                    while (x < image.width) {
                        val mm = robustDepthMm(accessor, x, y, radius = 1, minSamples = 3)
                        if (mm != null) {
                            texList.add((x + 0.5f) / image.width)
                            texList.add((y + 0.5f) / image.height)
                            depths.add(mm)
                        }
                        x += step
                    }
                    y += step
                }
                supportsFromDepth(frame, camera, texList, depths, maxPoints)
            }
        } catch (_: NotYetAvailableException) {
            emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Keep primary supports first and fill only image cells which are not already
     * represented. This avoids thousands of duplicate raw/full samples while
     * preserving dense metric coverage for visual feature association.
     */
    private fun mergeSupports(
        primary: List<FloatArray>,
        secondary: List<FloatArray>,
        maxPoints: Int,
    ): List<FloatArray> {
        if (maxPoints <= 0) return emptyList()
        if (primary.isEmpty()) return secondary.take(maxPoints)
        if (secondary.isEmpty() || primary.size >= maxPoints) return primary.take(maxPoints)

        val out = ArrayList<FloatArray>(minOf(maxPoints, primary.size + secondary.size))
        val occupied = HashSet<Long>(maxPoints * 2)

        fun cellKey(point: FloatArray): Long {
            val x = kotlin.math.floor(point.getOrElse(0) { 0f } / MERGE_CELL_PX).toLong()
            val y = kotlin.math.floor(point.getOrElse(1) { 0f } / MERGE_CELL_PX).toLong()
            return (x shl 32) xor (y and 0xffffffffL)
        }

        for (p in primary) {
            if (p.size < 5 || !p.take(5).all { it.isFinite() }) continue
            out += p
            occupied += cellKey(p)
            if (out.size >= maxPoints) return out
        }
        for (p in secondary) {
            if (p.size < 5 || !p.take(5).all { it.isFinite() }) continue
            if (!occupied.add(cellKey(p))) continue
            out += p
            if (out.size >= maxPoints) break
        }
        return out
    }

    private fun supportsFromDepth(
        frame: Frame,
        camera: Camera,
        texList: List<Float>,
        depths: List<Int>,
        maxPoints: Int,
    ): List<FloatArray> {
        if (depths.isEmpty()) return emptyList()
        val tex = FloatArray(texList.size) { texList[it] }
        val cpu = FloatArray(tex.size)
        frame.transformCoordinates2d(
            Coordinates2d.TEXTURE_NORMALIZED,
            tex,
            Coordinates2d.IMAGE_PIXELS,
            cpu,
        )
        val out = ArrayList<FloatArray>(depths.size)
        for (i in depths.indices) {
            val pu = cpu[i * 2]
            val pv = cpu[i * 2 + 1]
            if (!pu.isFinite() || !pv.isFinite() || pu < 0 || pv < 0) continue
            val world = unprojectToWorld(camera, pu, pv, depths[i] / 1000f)
            out.add(floatArrayOf(pu, pv, world[0], world[1], world[2]))
            if (out.size >= maxPoints) break
        }
        return out
    }

    private fun cpuPixelToDepth(
        frame: Frame,
        u: Float,
        v: Float,
        depthWidth: Int,
        depthHeight: Int,
    ): Pair<Int, Int>? {
        val tex = FloatArray(2)
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS,
            floatArrayOf(u, v),
            Coordinates2d.TEXTURE_NORMALIZED,
            tex,
        )
        if (tex[0] !in 0f..1f || tex[1] !in 0f..1f) return null
        return Pair(
            (tex[0] * depthWidth).toInt().coerceIn(0, depthWidth - 1),
            (tex[1] * depthHeight).toInt().coerceIn(0, depthHeight - 1),
        )
    }

    private fun samplePointCloud(frame: Frame, camera: Camera, maxPoints: Int): List<FloatArray> {
        return try {
            frame.acquirePointCloud().use { cloud ->
                val buffer = cloud.points
                val count = buffer.remaining() / 4
                if (count <= 0) return emptyList()
                val stride = max(1, count / maxPoints)
                val intr = camera.imageIntrinsics
                val f = intr.focalLength
                val pp = intr.principalPoint
                val dims = intr.imageDimensions
                val camFromWorld = camera.pose.inverse()
                val out = ArrayList<FloatArray>(minOf(maxPoints, count))
                for (i in 0 until count step stride) {
                    val base = i * 4
                    val confidence = buffer.get(base + 3)
                    if (confidence < 0.2f) continue
                    val world = floatArrayOf(buffer.get(base), buffer.get(base + 1), buffer.get(base + 2))
                    val ca = camFromWorld.transformPoint(world)
                    val z = -ca[2]
                    if (z <= 0.05f) continue
                    val pu = f[0] * ca[0] / z + pp[0]
                    val pv = f[1] * (-ca[1]) / z + pp[1]
                    if (pu !in 0f..dims[0].toFloat() || pv !in 0f..dims[1].toFloat()) continue
                    out.add(floatArrayOf(pu, pv, world[0], world[1], world[2]))
                    if (out.size >= maxPoints) break
                }
                out
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private class DepthAccessor(image: Image) {
        private val plane = image.planes[0]
        private val buffer: ByteBuffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        private val rowStride = plane.rowStride
        private val pixelStride = plane.pixelStride
        val width: Int = image.width
        val height: Int = image.height

        fun mm(x: Int, y: Int): Int {
            val index = x * pixelStride + y * rowStride
            return java.lang.Short.toUnsignedInt(buffer.getShort(index))
        }
    }

    private class ConfidenceAccessor(image: Image) {
        private val plane = image.planes[0]
        private val buffer: ByteBuffer = plane.buffer.duplicate()
        private val rowStride = plane.rowStride
        private val pixelStride = plane.pixelStride
        val width: Int = image.width
        val height: Int = image.height

        fun value(x: Int, y: Int): Int {
            val index = x * pixelStride + y * rowStride
            return java.lang.Byte.toUnsignedInt(buffer.get(index))
        }
    }

    private fun robustRawDepthMm(
        depth: DepthAccessor,
        confidence: ConfidenceAccessor,
        cx: Int,
        cy: Int,
        radius: Int,
        minSamples: Int,
    ): Int? {
        val samples = ArrayList<Pair<Int, Int>>((radius * 2 + 1) * (radius * 2 + 1))
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val x = (cx + dx).coerceIn(0, depth.width - 1)
                val y = (cy + dy).coerceIn(0, depth.height - 1)
                val conf = confidence.value(x, y)
                if (conf < RAW_CONFIDENCE_MIN) continue
                val mm = depth.mm(x, y)
                if (mm in MIN_DEPTH_MM..MAX_DEPTH_MM) samples += mm to conf
            }
        }
        if (samples.size < minSamples) return null

        val weighted = ArrayList<Int>(samples.size * 3)
        for ((mm, conf) in samples) {
            val weight = 1 + ((conf - RAW_CONFIDENCE_MIN) * 2 / (255 - RAW_CONFIDENCE_MIN)).coerceIn(0, 2)
            repeat(weight) { weighted += mm }
        }
        weighted.sort()
        val median = weighted[weighted.size / 2]
        val deviations = weighted.map { kotlin.math.abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
        val allowedMad = max(65, (median * 0.065f).toInt())
        return median.takeIf { mad <= allowedMad }
    }

    private fun robustDepthMm(
        depth: DepthAccessor,
        cx: Int,
        cy: Int,
        radius: Int,
        minSamples: Int,
    ): Int? {
        val samples = ArrayList<Int>((radius * 2 + 1) * (radius * 2 + 1))
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val x = (cx + dx).coerceIn(0, depth.width - 1)
                val y = (cy + dy).coerceIn(0, depth.height - 1)
                val mm = depth.mm(x, y)
                if (mm in MIN_DEPTH_MM..MAX_DEPTH_MM) samples += mm
            }
        }
        if (samples.size < minSamples) return null
        samples.sort()
        val median = samples[samples.size / 2]
        val deviations = samples.map { kotlin.math.abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
        val allowedMad = max(95, (median * 0.10f).toInt())
        return median.takeIf { mad <= allowedMad }
    }

    private fun unprojectToWorld(camera: Camera, u: Float, v: Float, depthM: Float): FloatArray {
        val intr = camera.imageIntrinsics
        val f = intr.focalLength
        val pp = intr.principalPoint
        val xCv = (u - pp[0]) / f[0] * depthM
        val yCv = (v - pp[1]) / f[1] * depthM
        return camera.pose.transformPoint(floatArrayOf(xCv, -yCv, -depthM))
    }
}
