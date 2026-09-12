# StableAR vs stock ARCore benchmark

This directory is deliberately **outside the shipping runtime**. It produces reproducible engineering/commercial evidence without making the tracker depend on its evaluator.

## What is compared

The primary metric is **material-attachment screen-space error**: the pixel distance between the rendered attachment and the same physical material point while the phone moves.

Every run is paired from the **same root exposure, exact pixel and metric seed**:

- **Stock ARCore**: the original unrefined material point on the same native ARCore anchor.
- **StableAR**: that same initial point followed by the normal StableAR visual-correspondence and bounded geometry path.

Both candidates are projected into the same CPU-camera image raster. The comparison therefore does not reward StableAR for receiving a better tap, a different anchor or a different starting depth.

## Independent ground truth

The recommended field target is a printed OpenCV ArUco marker (`DICT_4X4_50`, id `23`). The preferred mode records the exact tapped root exposure and pixel in `root.json`.

Offline scoring:

1. detects the four ArUco corners in the root exposure;
2. maps the exact clicked material point into the marker's planar coordinate system with a homography;
3. detects the marker corners independently in each later camera frame;
4. reprojects the same physical material point into that frame;
5. measures both Stock ARCore and StableAR against that independently reconstructed pixel.

XFeat/LK/ORB output is **never** used as ground truth. Human tap offset therefore does not become the truth. Explicit external `gt_x,gt_y` labels may be supplied for a stronger calibrated dataset.

This supports a scoped statement such as *lower p95 image attachment error than an unrefined ARCore anchor under the published tested conditions*. It does not prove absolute mm/cm world accuracy or universal ARCore superiority.

## Capture contract

One independent physical run is one directory containing `frames.csv`, frame images and preferably `root.json` plus the exact root exposure.

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

All coordinates must use the same source/evaluator raster. Invalid predictions remain invalid; they are not converted to zero error. Availability is scored separately so a tracker cannot look accurate merely by dropping hard frames.

## Physical protocol

Use the same printed target and the same root click for both candidates. Never re-place only one candidate after a run begins.

Recommended phases:

1. `front` — frontal, roughly 0.5–1.0 m;
2. `orbit45` — lateral/orbit motion to roughly 45°;
3. `orbit70` — aggressive oblique view;
4. `scale_near` — approach while the target remains inside the useful camera/depth range;
5. `scale_far` — retreat while the target remains resolvable;
6. `blur` — normal hand motion with short motion-blur intervals;
7. `lowlight` — reduced illumination;
8. `occluded` — fully cover the target for 1–2 s;
9. `reacquire` — uncover and return from a different viewpoint;
10. `return` — return near the original view to expose accumulated drift/hysteresis.

For an engineering comparison, capture at least **5 independent runs per claimed condition**. For a commercial device-class claim, repeat on multiple supported devices and report them explicitly. Do not pool incompatible camera modes without identifying them.

## Generate a target

```bash
python -m pip install numpy opencv-contrib-python
python sdk/benchmark/make_target.py --output stablear-aruco-23.png
```

## Score one run

```bash
python sdk/benchmark/score_aruco.py path/to/session --json-out run-summary.json
```

Per-run output reports p50/p95/RMSE/max pixel error, availability, false-lock rate, paired improvement and metrics per phase. The frame-bootstrap value inside a single run is descriptive only because adjacent video frames are temporally correlated.

## Aggregate independent runs and evaluate the claim gate

Commercial inference must use independent captures as the statistical unit:

```bash
python sdk/benchmark/aggregate_runs.py \
  captures/orbit45-run-01 \
  captures/orbit45-run-02 \
  captures/orbit45-run-03 \
  captures/orbit45-run-04 \
  captures/orbit45-run-05 \
  --require-phase orbit45 \
  --json-out orbit45-aggregate.json \
  --claim-exit-code
```

`aggregate_runs.py` performs a **cluster bootstrap over independent runs**, not over individual video frames. Default engineering claim gate requires:

- at least 5 independent eligible runs;
- at least 500 total paired target-visible frames;
- at least 30 paired frames per eligible run;
- all primary ground truth in `aruco_root_homography` mode unless another accepted mode is explicitly configured;
- pooled StableAR p95 lower than pooled Stock ARCore p95;
- lower bound of the 95% run-cluster bootstrap CI for mean paired improvement above zero;
- at least 80% of independent runs with positive mean paired improvement;
- StableAR availability at least 90% and no more than 2 percentage points below Stock ARCore;
- StableAR false-lock rate at most 2% and not materially worse than Stock ARCore;
- every explicitly required phase represented by the configured number of independent runs.

All gates are CLI-configurable so published thresholds can be versioned with the benchmark protocol. `--claim-exit-code` exits with code `2` when the supplied dataset is **not cleared**; this is useful for an evidence pipeline, not as a substitute for physical testing.

## Metrics to publish together

Do not publish only the best error number. A defensible benchmark table should show, for both candidates:

- p50 and p95 screen-space material-point error;
- RMSE;
- prediction availability;
- false-lock rate and its threshold;
- number of independent runs and paired frames;
- StableAR latency and device/thermal conditions;
- per-condition/per-device result;
- run-clustered 95% CI for paired improvement.

A positive paired improvement means StableAR was closer to the independently reconstructed physical material point.

## Commercial claim gate

Do **not** publish “better than ARCore” from CI, synthetic data, an upstream model paper, or one hand-picked video. A comparative release claim requires real-device sessions, retained raw evidence and wording scoped to the tested device/conditions.

A reasonable first wording after the dataset clears the gate is:

> On the listed supported Android devices using the published orbit/scale/occlusion protocol, StableAR reduced p95 screen-space static-material attachment error versus the same unrefined ARCore anchor.

Insert the actual devices, protocol revision, run count and measured numbers only after physical sessions exist.

The strongest future absolute-3D benchmark is a calibrated external tracker/robot/Vicon-style reference. ArUco material-point image error is the practical first benchmark because it directly measures what the user sees: whether the annotation stays on the same physical point.
