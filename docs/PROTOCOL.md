# Runtime protocol and coordinate conventions

The POC uses one WebSocket room with two logical roles, `A` and `B`.

```text
/ws/{room_id}/A
/ws/{room_id}/B
```

## Coordinate systems

### ARCore camera

Physical camera pose returned by ARCore is `T_W_C`, camera coordinates into the local ARCore world.

ARCore/OpenGL camera axes:

```text
+x right
+y up
-z forward
```

### OpenCV camera

OpenCV PnP/epipolar conventions use:

```text
+x right
+y down
+z forward
```

The axis conversion is:

```text
S = diag(1, -1, -1)
```

For homogeneous transforms:

```text
S4 = diag(1, -1, -1, 1)
```

### Shared transform

The desired runtime alignment is:

```text
T_WB_WA
```

which maps a point represented in A's ARCore world into B's ARCore world:

```text
P_WB = T_WB_WA * P_WA
```

## Frame message

Each phone sends:

```json
{
  "type": "frame",
  "session_id": "...",
  "timestamp_ns": 123,
  "pose": {
    "t": [0.0, 0.0, 0.0],
    "q": [0.0, 0.0, 0.0, 1.0]
  },
  "intrinsics": {
    "fx": 1000.0,
    "fy": 1000.0,
    "cx": 640.0,
    "cy": 360.0,
    "width": 1280,
    "height": 720
  },
  "jpeg_b64": "...",
  "metric_points": [
    [512.0, 341.0, 0.4, -0.1, -2.8]
  ]
}
```

`pose` is the ARCore physical-camera pose in that phone's local world.

`metric_points` are A-side support samples:

```text
[u_cpu, v_cpu, X_worldA, Y_worldA, Z_worldA]
```

The JPEG and intrinsics are scaled together. The support pixel coordinates are scaled by the same factor.

## Target message

A sends a target after a hit/depth lookup:

```json
{
  "type": "target",
  "point_wa": [1.0, 0.2, -4.0],
  "selected_pixel": [812.0, 514.0]
}
```

The target is already metric in `World_A`.

## Range message

B may send direct peer RTT:

```json
{
  "type": "range",
  "source": "wifi_aware_rtt",
  "distance_m": 2.31,
  "stddev_m": 0.12,
  "successful_measurements": 7
}
```

The current POC uses this only as the metric scale for the essential-matrix fallback. Production fusion should model it as a probabilistic factor rather than replacing the visual estimate.

## Alignment result

The server reports:

```json
{
  "type": "alignment",
  "ok": true,
  "method": "metric_depth_pnp",
  "inliers": 42,
  "correspondences": 58,
  "median_reprojection_px": 0.91,
  "confidence": 0.72
}
```

## Primary PnP composition

`solvePnP` returns a transform from `World_A` object coordinates into OpenCV B-camera coordinates:

```text
T_CVB_WA
```

Convert its output coordinates to the ARCore B-camera convention:

```text
T_CB_WA = S4 * T_CVB_WA
```

B also supplies:

```text
T_WB_CB
```

Therefore:

```text
T_WB_WA = T_WB_CB * S4 * T_CVB_WA
```

## Essential-matrix fallback composition

`recoverPose` returns relative OpenCV camera motion, with translation only up to scale. After applying a range estimate to `|t|`:

```text
T_CB_CA = S4 * T_CVB_CVA * S4
```

and:

```text
T_WB_WA = T_WB_CB * T_CB_CA * inverse(T_WA_CA)
```

This fallback is intrinsically weaker: the RF distance is a scalar antenna-to-antenna measurement, not an optical-camera-baseline vector, and asynchronous motion plus antenna/camera lever arms become material at decimeter targets.
