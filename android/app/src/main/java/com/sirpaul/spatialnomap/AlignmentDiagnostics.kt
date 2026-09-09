package com.sirpaul.spatialnomap

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Exact gate-level telemetry sampled from AlignmentCoordinator.Quality.
 *
 * This intentionally lives outside MainActivity/AlignmentCoordinator so diagnostics
 * cannot perturb the registration state machine. The poller uses package-local JVM
 * reflection only to reach the Activity's private coordinator instance, then records
 * immutable quality snapshots at 4 Hz. Physical-test failures therefore leave a
 * durable answer for which gate was blocking instead of only an ALIGNING label.
 */
object AlignmentDiagnostics {
    data class Snapshot(
        val quality: AlignmentCoordinator.Quality,
        val blocker: String,
        val timestampMs: Long,
    )

    @Volatile private var latest: Snapshot? = null

    fun latest(): Snapshot? = latest

    fun observe(quality: AlignmentCoordinator.Quality, nowMs: Long = System.currentTimeMillis()) {
        val snapshot = Snapshot(quality, blocker(quality), nowMs)
        latest = snapshot
        append(snapshot)
    }

    private fun blocker(q: AlignmentCoordinator.Quality): String = when {
        q.bothReady -> if (q.lockValidationFailures > 0) "LOCKED_REVALIDATING" else "LOCKED"
        q.inliers < 8 -> "VISUAL_INLIERS"
        q.correspondences < 8 -> "CORRESPONDENCES"
        !q.medianReprojectionPx.isFinite() -> "NO_REPROJECTION"
        q.medianReprojectionPx > 4.0 -> "REPROJECTION"
        q.imageCoverage < 0.04 -> "IMAGE_COVERAGE"
        q.confidence < 0.09f -> "CONFIDENCE"
        q.gravityTiltDeg.isFinite() && q.gravityTiltDeg > 10.0 -> "GRAVITY"
        q.metricPairs < TransformSafetyPolicy.MIN_METRIC_PAIRS -> "METRIC_SUPPORT"
        q.metricInliers < TransformSafetyPolicy.MIN_METRIC_INLIERS -> "METRIC_INLIERS"
        !q.medianMetricResidualM.isFinite() -> "NO_METRIC_RESIDUAL"
        q.medianMetricResidualM > TransformSafetyPolicy.MAX_MEDIAN_METRIC_RESIDUAL_M -> "METRIC_RESIDUAL"
        q.localReady && !q.peerReady -> "WAITING_PEER_READY"
        q.localReady && !q.peerTransformVerified -> "PEER_TRANSFORM_VERIFY"
        q.localReady -> "CONFIRMING"
        else -> "CONSENSUS"
    }

    private fun append(snapshot: Snapshot) {
        val path = AlignmentSessionRecorder.latestSessionPath() ?: return
        val q = snapshot.quality
        val line = String.format(
            Locale.US,
            "{\"t\":%d,\"blocker\":\"%s\",\"confidence\":%.5f,\"inliers\":%d,\"corr\":%d,\"reproj\":%.5f,\"coverage\":%.6f,\"stable\":%d,\"localReady\":%s,\"peerReady\":%s,\"verified\":%s,\"range\":%s,\"rangeDelta\":%s,\"gravity\":%.4f,\"metricPairs\":%d,\"metricInliers\":%d,\"metricResidual\":%.5f,\"lockValidationFailures\":%d,\"agreeMedian\":%.5f,\"agreeP90\":%.5f,\"agreeRot\":%.4f,\"source\":\"%s\"}\n",
            snapshot.timestampMs,
            snapshot.blocker,
            q.confidence,
            q.inliers,
            q.correspondences,
            q.medianReprojectionPx,
            q.imageCoverage,
            q.stableCount,
            q.localReady,
            q.peerReady,
            q.peerTransformVerified,
            q.rangeM?.toString() ?: "null",
            q.rangeDeltaM?.toString() ?: "null",
            q.gravityTiltDeg,
            q.metricPairs,
            q.metricInliers,
            q.medianMetricResidualM,
            q.lockValidationFailures,
            q.peerAgreementMedianM,
            q.peerAgreementP90M,
            q.peerAgreementRotationDeg,
            q.fusionSource.replace("\"", "'"),
        )
        runCatching { File(path, "quality.ndjson").appendText(line) }
    }
}

object AlignmentQualityPoller {
    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private var activeActivity: WeakReference<MainActivity>? = null
    private var activeCoordinator: WeakReference<AlignmentCoordinator>? = null

    private val poll = object : Runnable {
        override fun run() {
            val activity = activeActivity?.get()
            val coordinator = activeCoordinator?.get()
            if (activity == null || coordinator == null || activity.isFinishing) return
            runCatching { AlignmentDiagnostics.observe(coordinator.quality()) }
            runCatching { SpatialMapAccumulator.ingest(WorldVizBus.snapshot(maxPointsPerSide = 1800)) }
            main.postDelayed(this, 250L)
        }
    }

    @Synchronized fun register(application: Application) {
        if (registered) return
        registered = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivity) return
                val coordinator = runCatching {
                    activity.javaClass.getDeclaredField("coordinator").apply { isAccessible = true }.get(activity) as AlignmentCoordinator
                }.getOrNull() ?: return
                activeActivity = WeakReference(activity)
                activeCoordinator = WeakReference(coordinator)
                main.removeCallbacks(poll)
                main.post(poll)
            }

            override fun onActivityPaused(activity: Activity) {
                if (activeActivity?.get() === activity) {
                    main.removeCallbacks(poll)
                    activeActivity = null
                    activeCoordinator = null
                }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
