package com.sirpaul.spatialnomap

/**
 * Compatibility factory so the Activity keeps the compact renderer construction
 * call while ArRenderer can obtain an Android Context for the bundled ML model.
 */
@Suppress("FunctionName")
fun ArRenderer(
    coordinator: AlignmentCoordinator,
    overlay: TargetOverlayView,
    usernameProvider: () -> String,
    status: (String) -> Unit,
    rotationProvider: () -> Int,
    sensorSnapshotProvider: () -> SensorSnapshot = { SpatialSyncApplication.sensorSnapshot() },
): ArRenderer = ArRenderer(
    context = overlay.context,
    coordinator = coordinator,
    overlay = overlay,
    usernameProvider = usernameProvider,
    status = status,
    rotationProvider = rotationProvider,
    sensorSnapshotProvider = sensorSnapshotProvider,
)
