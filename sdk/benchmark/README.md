# StableAR vs stock ARCore benchmark

This directory is deliberately **outside the shipping runtime**. It exists to produce evidence for engineering and commercial claims without making the tracker depend on its evaluator.

## What the benchmark measures

The primary claimable metric is **material-attachment screen-space error**: how far the rendered attachment is from the same physical target in the camera image while the phone moves.

For every run, both candidates start from the **same exposure, pixel and metric seed**:

- **Stock ARCore**: an immutable ARCore anchor / initial material point with no StableAR refinement.
- **StableAR**: the same initial point, then the normal StableAR visual correspondence + bounded geometry path.

Both candidates are projected into the same CPU-camera image raster for each frame.

Ground truth is intentionally independent from StableAR. The recommended field target is a printed OpenCV ArUco marker (`DICT_4X4_50`, id 23). The strongest normal field mode records the exact tapped root exposure and pixel in `root.json`. Offline scoring detects the four ArUco corners in that root exposure, maps the exact clicked material point into the marker's planar coordinate system, and reprojects that same physical point from independently detected marker corners in every later frame. Human tap offset therefore does not become the ground truth. XFeat/LK/ORB output is **never** used to define truth.

If `root.json` is absent, the scorer can fall back to the marker center. Explicit externally labelled `gt_x,gt_y` values take precedence over both modes.

This benchmark supports a defensible statement such as "lower p95 image attachment error than an unrefined ARCore anchor under these tested conditions." It does **not** by itself prove absolute centimetre/mm world accuracy, SLAM accuracy, or performance on every device.

## Capture contract

One benchmark session is a directory containing `frames.csv`, frame images, and preferably `root.json` plus the exact root exposure.

Required CSV columns:

```text
timestamp_ns,phase,fx,fy,stock_x,stock_y,stock_valid,stable_x,stable_y,stable_valid
```

Recommended columns:

```text
image_path,stable_method,stable_latency_ms,accepted_corrections,tracking_state
```

Preferred root metadata:

```json
{
  "image_path": "root.pgm",
  "pixel_x": 319.2,
  "pixel_y": 241.8
}
```

Coordinates in the CSV, root metadata and images must all be in the same image raster. `stock_valid`/`stable_valid` use `1/0`, `true/false`, or equivalent boolean text. Invalid predictions are not silently converted into zero error; availability is reported separately.

## Field protocol

Use the same printed marker and same root click for both candidates. Do not re-place one candidate after the run starts.

Recommended phases, each at least 5-10 seconds:

1. `front` - normal frontal view at 0.5-1.0 m.
2. `orbit45` - lateral/orbit motion to roughly 45 degrees.
3. `orbit70` - aggressive oblique view.
4. `scale_near` - approach the marker without going inside the camera/depth minimum useful range.
5. `scale_far` - retreat while the marker remains resolvable.
6. `blur` - normal hand motion with short motion-blur intervals.
7. `lowlight` - reduced illumination without changing the target.
8. `occluded` - cover the marker fully for 1-2 seconds.
9. `reacquire` - uncover it and return from a different view.
10. `return` - return close to the original viewpoint to expose accumulated drift/hysteresis.

Record at least 5 independent runs per condition. For product claims, repeat on multiple supported phones and do not pool incompatible camera modes without reporting them.

## Scoring

Install optional evaluator dependencies:

```bash
python -m pip install numpy opencv-contrib-python
```

Generate the target:

```bash
python sdk/benchmark/make_target.py --output stablear-aruco-23.png
```

Score a captured session:

```bash
python sdk/benchmark/score_aruco.py path/to/session --json-out summary.json
```

Important reported values:

- p50 / p95 / RMSE pixel error for each candidate;
- prediction availability while the independent target is visible;
- false-lock rate above the configured pixel threshold;
- paired StableAR improvement (`stock_error - stable_error`);
- paired win rate;
- bootstrap 95% confidence interval for mean paired improvement;
- the ground-truth mode actually used;
- the same metrics per named phase.

A positive paired improvement means StableAR was closer to the physical marker point on that frame.

## Commercial claim gate

Do not publish "better than ARCore" from CI or synthetic data. A release claim should require, at minimum:

- physical sessions captured on real supported devices;
- an evaluator independent of the StableAR correspondence frontend;
- `aruco_root_homography` or stronger external ground truth for the primary claim;
- enough paired target-visible frames to make p95 meaningful;
- StableAR p95 error lower than stock ARCore in the named scenario;
- bootstrap 95% CI for mean paired improvement entirely above zero;
- false-lock rate no worse than the agreed product threshold;
- availability and latency reported alongside accuracy, not hidden;
- raw sessions retained so the result can be reproduced.

The strongest future absolute-3D benchmark is a calibrated external tracker/robot/Vicon-style reference. ArUco material-point image error is the practical first benchmark because it directly measures what the user sees: whether the annotation stays on the same physical point.
