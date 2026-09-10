# Research SDK: evidence-aware local surface anchoring

Research date: 2026-09-10. Repository: `SirPaul-code/spatial_ar_coop`.
Audited ShowMe baseline: `4763e0fa2845b5b3e443fb9e298b7b2964a3067f`.
Research branch: `research_sdk`. All product source files were left unchanged.

## Executive decision

Build an anchor-local surface attachment SDK above ARCore/ARKit, not a replacement
visual-inertial odometry stack. Its job is to preserve the identity, geometry,
uncertainty and timing of a user-selected material point. A camera pose, a bounding
box, a depth value and a stable-looking overlay are four different measurements.

The strongest immediate gains identified here are correctness and estimation
changes: transport historical geometry through retained anchors; distinguish new
depth evidence from reprojected old evidence; keep original click references
immutable; verify corrections in multiple views; make geometry and camera display
share a frame timeline. Learned matching is an optional front-end improvement,
not a substitute for those contracts.

This is an engineering research hypothesis supported by code inspection and
synthetic experiments. It is not a claim of a novel SLAM theorem, a patent search,
a completed mobile SDK, better-than-Apple performance, or physical accuracy.

## 1. Repository-wide coverage

The generated inventory covers **41 original remote branch heads**, including
Dependabot branches. It records commit IDs, recursive file trees and distinct
source blob versions. `generated/branch_inventory.json` is machine-readable;
`generated/branch_inventory.md` is its human-readable summary. All Git objects
were also exported and inspected locally. No code from arbitrary branches was run.

This is not a claim of a line-by-line semantic review of every historical version.
Semantic inspection concentrated on the following architecture families and their
tracking paths:

| Family | Evidence inspected | Relevance |
|---|---|---|
| `main`, `feat/hifi-tracking-v1.2.0`, associated detection fixes | Main README, TemporalDetectionTracker, CloudAnchorCoordinator structure, server fusion, differences to hifi | Bounding-box identity and server object fusion are not clicked-surface tracking. |
| `fresh/no-map-runtime-poc`, `outdoor/gnss-global` | Same head `a5970e9be7fe`; sensor fusion, alignment/essential/PnP paths, depth sampler, surface resolver/refiner | Reusable geometry, but peer alignment is outside a single-owner ShowMe world. |
| `golden/4ee42ea-tracking` | Differences in resolver, renderer, edge refinement and vehicle policy | Multi-view reference atlas was already added after this checkpoint. |
| `archive/pre-rollback-2026-09-09` | TransformSafetyPolicy, essential-solver and verifier differences | Contains stricter peer-transform evidence policies; do not blindly restore or remove them. |
| `showme/local-assist` | Historical SurfaceGeometry and source layout | Already has inverse-depth fitting and explicit plane handling. |
| `showme/remote-assistance` | Renderer, geometry, depth history, video pipe, overlay, session contracts and shared surface paths | Principal baseline for the proposed SDK. |

The archive differs substantially from fresh, including removed shared-transform
verification and altered metric-scale gates. That is a separate two-phone
registration trade-off, not evidence that the new single-device SDK must carry
all those mechanisms. `outdoor/gnss-global` is not an independently newer GNSS
implementation at the inspected head: it equals fresh exactly.

`SpatialSensorFusion` explicitly preserves ARCore as the metric tracker. It reads
rotation-vector orientation, gyro, pressure and location as registration priors.
It does not implement an independent high-grade inertial navigation solution.
Its snapshot time is also not the measurement time of every contained sensor.
For future peer work, retain individual sensor timestamps, freshness and frame
conventions rather than treating every snapshot as simultaneous new evidence.

## 2. What the current code already gets right

The live ShowMe stream is WebRTC, captured through shared-EGL ARCore camera texture,
not continuous screenshot/JPEG polling. JPEG remains for exact frozen references
and a low-rate verifier. A CRC-coded video footer binds the displayed image to a
native frame ID; full epoch metadata, bounded history and failure on expired
references prevent the classic old-pixel/current-hit-test error. Preserve this.

`ShowMeGeometry.pointAt` already reconstructs the original pixel ray using local
metric supports and an inverse-depth plane. It rejects some mixed-depth and
unsupported cases. Drawings already use an ARCore anchor plus local vertex offsets.
`SurfaceTargetResolver` already maintains a bounded multi-view atlas. Recommending
"add anchors", "use inverse depth" or "keep several images" as if absent would
misrepresent this repository.

Pure baseline geometry was actually compiled unchanged together with `Models.kt`:
7,005 assertions passed. Seven existing Node browser-geometry tests also passed.
These do not validate Android camera access, GPU/WebRTC hardware paths, tracking
quality, sensor synchronization, or physical centimetre accuracy.

## 3. Findings and recommended corrections

### F1 — Historical world coordinates outlive their coordinate frame

**Code evidence:** `VideoDepthHistory.kt` stores numerical `PosePacket` and converts
supports into numerical world coordinates. `ShowMeRenderer.applyCommand` later
creates an anchor in the current session from those historical coordinates.
Epoch checking only detects designated world/session resets.

**Why this matters:** ARCore explicitly states that numerical world poses can change
as its world model is refined, even within a session [S1, S3]. The same epoch does
not make old and current numerical coordinates identical. The existing anchor
protects a drawing after creation, not the historical pixel before that creation.

**Correction:** establish a small bank of retained, nearby submap anchors during
capture. Record the frame's camera pose relative to its selected anchor in the
same update tick. Store camera-local depth and anchor-local supports. At placement,
transport them with that anchor's current pose. Retain the anchor through history
and freeze leases. Do not create an anchor retrospectively at an old numerical
camera pose. Do not create 30 new anchors per second.

The transform below is exact for a common rigid coordinate-frame change. ARCore
may also apply spatially varying map refinement; one distant anchor cannot repair
all such deformation. Keep patches local, reverify visually, and reject/relocalize
when an anchor cannot provide a coherent local frame.

### F2 — Asynchronous repair can arrive in an obsolete world/anchor version

**Code evidence:** repair contains drawing ID, epoch, frame ID and numerical world
point; epoch is checked on application. Work may have used an older reference
frame and an earlier anchor position, while the anchor changes before application.

**Correction:** return anchor-local residual/proposal plus anchor generation,
source frame, root keyframe and evidence IDs. Reproject the proposal using the
current anchor before commit. Cancel jobs when the attachment/reference generation
changes. The accepted correction should not implicitly redefine the immutable
original clicked material point.

### F3 — Reprojected depth is not independent new depth

`DepthRaster` retains depth/confidence but not the source image timestamp.
Raw/full/point-cloud support provenance and numeric confidence are dropped when
converted to five-float support arrays. Two distinct video frames can therefore
be counted as two corroborations without independent metric evidence.

ARCore documents roughly ten new raw-depth estimates per second, with intermediate
maps reprojected to the current camera pose [S2]. This is valid current-view depth,
not automatically a timestamp bug. Compare consecutive RAW DEPTH source timestamps
to identify new information; do not require equality with every RGB timestamp.

Keep video/camera timestamp, depth-source timestamp, raw/full/point-cloud origin,
confidence and local uncertainty separately. Deduplicate statistical evidence,
not blindly the raster bytes: two reprojected rasters can differ despite sharing
one source timestamp. A depth confidence byte is not a calibrated error in metres.

### F4 — Edge refinement can invalidate its own geometric evidence

`SurfaceEdgeSnapRefiner` compares an original 55x55 equalized-Canny patch against
a current search window using normalized correlation, without warping the patch
for the solved homography. Rotation, scale and perspective are therefore not
accounted for by this extra stage. It also receives the original reference even
when the coarse resolver selected a learned atlas view.

After changing the matched pixel, `coarse.copy` retains the previous visual inlier
count and median reprojection error. Confidence cannot decrease because it is
computed with `max(coarse.confidence, ...)`. Those numbers do not certify the new
pixel or its depth. This is a code-level evidence-contract defect, not a measured
rate of wrong physical placements.

Return the actual selected keyframe and homography from the coarse solve. Refine
only under a valid local plane model, using the corresponding warped patch and
subpixel photometric alignment. Verify the refined coordinate anew with held-out
features, forward/backward consistency and metric support. Otherwise retain the
coarse result. A general object boundary must never silently redefine the clicked
point as "nearest edge".

### F5 — Atlas learning happens before the outer acceptance decision

`SurfaceTargetResolver.resolve` calls `learnCurrentView` before ShowMe applies its
stricter checks and two-frame consensus. Internal learn gates are confidence .25,
inliers 9, reprojection 2.4 px and depth supports 3. ShowMe checks .30, 9, 2.0 px,
4 supports and an 8 cm correction limit. Consequently a view rejected by ShowMe
can already become a learned reference. That risks self-reinforcing drift.

Separate propose, validate, commit and learn. Only accepted, independently verified
observations may update the atlas. Preserve the original root forever during the
attachment lifetime; use geometrically diverse references rather than repeatedly
learning adjacent, highly correlated frames.

### F6 — Different geometry quality between placement and repair

Initial `pointAt` fits inverse depth. Both surface resolver/refiner repair paths
instead use a nearby clustered median depth. A median over an asymmetrically sampled
slanted patch need not equal the depth at the selected pixel. A synthetic stress
test below illustrates this mechanism; it is not a port of every production gate.

Use the same ray-consistent surface fit for initial placement and later refinement.
Require support coverage around the selected ray, conditioning, robust residuals,
and boundary handling. Fit separate surfaces at depth discontinuities; never
average foreground and background into a physically nonexistent surface.

### F7 — Native overlay and camera are not explicitly frame-locked

The native path projects geometry in the GL renderer, then updates a separate
Android Canvas view through `postInvalidateOnAnimation`. There is no explicit
frame-ID contract between the camera background presentation and this overlay.
The streamed path has a stronger identity contract than this native presentation.
This is a latency/jitter risk to measure, not proof of a particular device fault.

Render world geometry in the camera GL pass, or instrument and enforce a matched
presentation timeline. Distinguish camera-exposure pose, render pose and display
prediction. Do not smooth world-projected screen pixels as a substitute.

### F8 — Resource and precision budgets are conflated

The verifier captures at roughly 1 Hz and checks one drawing in round-robin order.
With 48 drawings, a drawing may be revisited only on the order of tens of seconds,
not once per second. SIFT/JPEG work and reference descriptors should be cached and
shared. Some YUV copying still occurs in the render path. Profile before adding
more inference; starving ARCore's own VIO can worsen tracking [S3].

The 8 cm per-repair limit and 15 cm cumulative travel limit are safety caps, not
accuracy estimates. A monotonically consumed correction budget can also stop
useful later repairs. Replace "verified" with a measurement-backed quality state,
age and uncertainty; retain bounded corrections as additional safety constraints.

## 4. Mathematical design

Let T_AB map coordinates in B into A. Let C_f denote the exposure camera frame,
A a retained nearby anchor, and W_f/W_t the numerical worlds at capture and now.
For pixel (u,v), intrinsics K and optical-axis depth z, using a CV camera convention:

    p_Cf = z K^-1 [u, v, 1]^T
    p_A  = (T_Wf_A)^-1 T_Wf_Cf p_Cf
    p_Wt = T_Wt_A p_A

ARCore camera coordinates use different y/z signs; the adapter must apply the
conversion once. Do not mix Euclidean ray range with optical-axis depth. Both
transforms in the middle expression must be sampled from the same AR update.

A common world rebase G cancels in the relative camera/anchor transform. This is
why the anchor-local representation is invariant in the rigid-gauge experiment.
It is not evidence that real SLAM drift disappears.

### Geometry observable from several views

For a static root ray, write the candidate point as:

    p_A(z) = c_A0 + d_A0 z

Estimate z from robust reprojection residuals in additional camera views plus a
metric depth prior. For a general patch, extend the state to a local plane or small
surfel neighbourhood and optimize its attachment; do not assume every object is
planar. One pixel ray constrains direction but leaves depth unobservable. Pure
rotation does not add triangulation baseline.

With focal length f, range Z, sideways baseline B and independent image noise
sigma_px in each of two views, the linearized depth uncertainty is:

    sigma_Z approximately Z^2 / (f B) * sqrt(2) sigma_px

At Z=2 m, f=800 px and sigma_px=.5, a 1 cm baseline gives about .354 m linearized
sigma, versus .0177 m at 20 cm. These are ideal calibrated-camera numbers, not
mobile promises. Larger baselines also increase correspondence difficulty.

### Correlated uncertainty is not optional

For repeated observations with common bias b and independent noise e_i:

    Var(mean(b+e_i)) = sigma_b^2 + sigma_e^2 / n

It does not decay to zero. ARCore pose, camera-derived depth and visual tracking
share input information; treat them neither as independent sensors nor as three
votes for the same fact. A robust loss rejects some outliers but does not remove
systematic calibration error.

The supplied prototype also propagates an assumed coherent pose-translation
uncertainty through the optimizer. With image residual Jacobians J_z and J_b:

    dz/db = -(J_z^T J_z)^-1 J_z^T J_b
    Var(z) = Var(z | poses) + (dz/db) Sigma_b (dz/db)^T + floor^2

This covers one deliberately simple correlated error model. It is NOT a full
marginalized VIO estimator; rotation error, map deformation, identity switches,
selection bias and camera calibration uncertainty need additional treatment.

## 5. Experiments actually executed

`experiments.py`: deterministic seed 20260910; Python 3.13.5, NumPy 2.3.5,
SciPy 1.17.0, OpenCV 4.13.0. Results are in `results.json`.

All images are procedurally generated and all geometry/noise is simulated.
No real phone recordings, learned model inference, thermal measurements, long-term
relocalization benchmark or calibrated physical ground truth were used.

| Experiment | Result | Valid interpretation |
|---|---|---|
| Old pixel + 100 ms newer pose; 2 m target, 0.3 m/s translation and 60 deg/s yaw | 239.3 mm world error; 96.2 px image error | Exact image/pose association is essential. Current ShowMe already addresses part of this. |
| 1,000 random rigid world rebases; translation sigma 3 cm/axis, rotation sigma 1 deg/axis | Stale numeric point median 63.2 mm, p95 118.6 mm; anchor-relative transport numerical roundoff | Demonstrates coordinate invariance only. |
| Two-view depth, 20,000 trials per baseline | p95 error 820.8 mm at 1 cm, 138.4 mm at 5 cm, 35.0 mm at 20 cm | Small parallax cannot certify depth. Camera poses here are exact. |
| 30 observations; shared noise sigma 20 mm, white noise sigma 10 mm | Naive reported sigma 4.08 mm vs actual 20.10 mm; nominal 95% interval covers only 30.85% | Repetition can create false certainty. Correlation-aware coverage was 95.105%. |
| Slanted plane, asymmetric support, 500 trials | Median depth p95 error 58.0 mm; robust inverse-depth fit 3.09 mm | Shared repair geometry should be ray-consistent. Not an end-to-end ShowMe accuracy result. |
| Existing edge image gates, 36 patches per angle | Accepted 36/36 at 0 deg; 5/36 at 4 deg; 0/36 at 8,16,24 deg | Unwarped patch has a narrow viewpoint range. Rejection is safer than a false correction. |
| Same edge test at 2 deg | 36/36 accepted; one accepted refinement worse than its 1.22 px input | No unconditional "refined is more accurate" claim. Metric gates were not tested. |
| Pyramidal LK, one small-motion synthetic pair | 256/256 forward/backward accepted; median .077 px, p95 .165 px | Cheap local tracking merits a baseline. Not general occlusion/relocalization performance. |
| Smooth projected screen trajectory, .8 px observation noise | RMS .794 px unfiltered vs 27.10 px with EMA alpha=.2 | Filtering screen-space camera motion can create large lag. |
| Same-ray wrong depth | 150 mm depth error gives zero original-view reprojection error; 5.58 px after 20 cm sideways motion | One-view pixel alignment is not metric verification. |

The warped-edge comparison in the code deliberately uses ground-truth homography.
It is an oracle control, not a deployable improvement or a learned model benchmark.
The plane experiment's median baseline is conceptual and does not reproduce every
production support gate. Failures and controls are preserved in the JSON.

## 6. Runnable surface-lock prototype and the negative result

`surface_lock.py` implements robust depth refinement along the immutable original
ray, anchor-local supplied poses, epoch/generation validation, frame deduplication,
parallax gating, Huber outlier rejection, conditional uncertainty and optional
correlated-translation sensitivity. It consumes correspondences; it does not
produce them and is not an Android tracking SDK.

A paired 300-trial experiment starts 120 mm wrong at 2 m, supplies six views with
0.5 px independent noise and one deliberately false correspondence. Results:

| Assumptions | Supported trials | Median error among supported | p95 error among supported |
|---|---:|---:|---:|
| Exact camera poses; baseline up to 24 cm | 300/300 | 5.38 mm | 15.51 mm |
| Shared 5 mm/axis pose-translation error, ignored by uncertainty | 116/300 | 27.72 mm | 78.02 mm |
| Same noisy data, known correlated error model included | 0/300 | Not applicable | Not applicable |
| Correlated error model included, baseline up to 80 cm | 111/300 | 8.75 mm | 24.55 mm |

The third row is not an accuracy victory through zero predictions. It demonstrates
that this geometry no longer justifies the prototype's precision threshold. A
real SDK must preserve an uncertain prior or guide a better scan, not quietly
present rejected cases as successes. The fourth row trades more motion for better
conditioning, at only 37% supported coverage under these deliberately strict image
gates. Report coverage and error together.

The 10 mm systematic floor and 30 mm support threshold are assumed experimental
parameters, not measured ARCore calibration. Even the correlated model is limited.
This negative result is the main reason not to market a fixed centimetre promise
from a point-tracking neural model or reprojection residual alone.

## 7. Current primary research and relevance

**TAPVid-MV, 2026-09-01 [S4].** The newest relevant primary abstract located in this
search explicitly separates geometry recovery from point correspondence. It
reports that multi-view trackers do not consistently beat monocular trackers on
its benchmark. It concerns multiple synchronized cameras, not this single-phone
product, and was reviewed at abstract level; full text was not accessible here.

**DL-VINS-Factory, 2026-07-02 [S5].** Controlled learned-feature comparisons show that
optical flow can remain preferable in some environments; learned matching is not
universally superior. Its 29-47 monocular FPS figures use Jetson AGX Orin/TensorRT,
not a smartphone. Useful lesson: keep interchangeable front ends and measure them
under the same backend and workload.

**TAPNext++, 2026-04-12 [S6].** Online point tracking, long-sequence training and
re-detection evaluation address exactly the failure of returning to a previously
selected point. Use it as a comparison/teacher candidate for reacquisition. No
on-phone power/FPS or commercially deployable integration was established here.
A model's recurrent state should not be the only store of original identity.

**TAPIP3D, 2025, v3 reviewed [S7].** Camera-stabilized 3D point features are a useful
architectural analogy for anchor-local tracking. Reported inference is 11.3 FPS
on an L40S for the stated workload; the geometry front end can cost more than the
tracker. Not selected as a drop-in mobile model.

**XFeat + LighterGlue [S8].** Practical candidate for occasional keyframe matching.
The official implementation offers a smaller matching option. Its CPU/laptop
claims are not Samsung S25 Ultra measurements. Benchmark extraction, matching,
conversion, memory copies and thermal behaviour together, not an isolated kernel.
Use matcher weights trained for the selected descriptor family.

**LightGlue [S9].** Code and its own weights are Apache-2.0; its README explicitly
warns that SuperPoint has separate restrictive licensing. ALIKED is listed as
BSD-3-Clause. Inspect every actual dependency and checkpoint before commercial
packaging; this research does not grant third-party rights.

**ARKit documentation [S10].** Apple also describes VIO, changing plane estimates
and environment-dependent tracking quality. "Apple-like" is a useful target for
coherent motion/display and recovery UX, not a physical zero-error specification.

## 8. Proposed SDK architecture

    Platform AR backend (ARCore / later ARKit)
          |
    Exact frame + retained-anchor reference + sensor/depth provenance
          |
    Local patch tracker (luma pyramids / KLT / forward-backward gates)
          |
    Keyframe reacquisition (cached SIFT baseline / XFeat + matching candidate)
          |
    Local surface geometry + uncertainty / observability checks
          |
    Versioned commit gate + accepted-only atlas update
          |
    Same-frame renderer + diagnostics / bounded recorder

The high-rate path should track image patches relative to the predicted camera
motion; it must not constantly overwrite global anchors with unverified 2D guesses.
Keep native ARCore pose as the platform motion estimate. Do not independently fuse
its output and the same raw IMU data as though unrelated measurements.

A segmentation model can isolate the intended surface and exclude moving fingers
or a foreground obstruction. It cannot alone supply metric depth or establish
that a visually identical screw is the original screw. Preserve material-point
identity, local geometry and independent observations.

For static annotations, optimize shared local patch/attachment geometry, not every
vertex independently. For nonplanar strokes, use multiple coherent subpatches or
surface samples. One correction to the first vertex/anchor cannot fix an incorrectly
reconstructed curved stroke. For moving objects, use an explicit separate rigid
object frame and motion model; a world anchor is not object-following behaviour.

Suggested data contracts:

    FrameRecord:
      frameId, sessionEpoch, cameraTimestamp, depthSourceTimestamp
      intrinsics, image/depth/display transforms, orientation, exposure metadata
      retainedAnchorHandle, anchorGeneration, T_anchor_camera
      depth + confidence + source kind + validity mask

    SurfaceAttachment:
      immutable root keyframe + clicked ray / patch coordinates
      local geometry, local uncertainty model, state, evidence age
      selected reference identity, accepted observation IDs, revision

    TrackingResult:
      attachmentRevision, sourceFrameId, anchorGeneration
      proposed local correction, residuals, support geometry, uncertainty
      reason when rejected or temporarily uncertain

Keep quantitative status separate from presentation: INITIALIZING, SUPPORTED,
OCCLUDED, UNCERTAIN, RELOCALIZING, LOST. Maintain geometry through temporary
occlusion without pretending a moving occluder is the original target. Cross-process
or next-day persistence requires saved map/reference relocalization; an in-memory
anchor or screenshot is not enough.

### CPU/GPU policy

Start with cached classical features and a small luma ROI; use OpenCV pyramidal LK
as a measured baseline [S11]. Schedule heavier matching only when necessary or when
creating a diverse keyframe. Shared descriptors across drawings and bounded queues
are more useful than unconstrained per-drawing inference. No fixed mobile FPS
claim is justified without a device profile. Keep network encoding and ARCore
motion tracking protected from additional workload [S3].

### Implementation sequence

1. Instrument exact frame/presentation timing, depth source IDs and anchor generations.
   Add gauge-change and stale-worker-result regression tests before changing matching.
2. Replace stale-world historical data with local anchor references; preserve the
   WebRTC identity protocol and session safety boundaries.
3. Split resolver proposal/commit/learning; return actual selected keyframe and
   transform; disable unverified edge refinements until correctly revalidated.
4. Unify placement and repair surface fits; expose uncertainty and evidence age.
5. Add shared cached KLT/keyframe front ends, then compare a learned matcher on the
   same recorded sessions. Change one component at a time.
6. Extract a platform-neutral geometry/tracking core and Android AAR adapter only
   after field tests establish improvement. The Python prototype is an executable
   estimator specification, not this completed extraction.

## 9. Validation required before selling precision

Use fixed measured targets at several distances and depths, including slanted
surfaces, repeated textures, engine-bay-like clutter, reflective/textureless regions,
moving occluders and changing light. Record original luma/depth, intrinsics,
per-source timestamps, anchor poses/generations, tracking status and frame presentation.

For independently visible target verification, a printed marker board or known
geometry can help, but exclude its markers from the tested markerless tracker;
otherwise it becomes an assisted-marker test. Report reference-system uncertainty
and camera calibration uncertainty. For genuinely independent 3D error, use a
surveyed fixture or external reference, not the same ARCore depth as ground truth.

Run native-anchor baseline, current ShowMe, anchor-local corrections, cached local
tracking, and learned matching as separate ablations on the same recordings.
Record placement bias, 3D median/p95, pixel jitter at fixed view, drift after walking
away/back, wrong-target rate, re-detection rate, time uncertain, acceptance coverage,
latency, CPU/GPU time, thermal throttling, memory and battery use. Do not combine
these into a single arbitrary "confidence" score.

Choose product targets by distance/material/device, then calibrate quality states
on held-out sessions. A SDK that rejects every placement cannot win solely by
reporting low accepted-case error. A SDK that always draws plausible pins cannot
win solely by reporting high availability.

## Sources

Primary sources accessed 2026-09-10. Dates refer to papers, not documentation crawl times.

- [S1] ARCore Pose: https://developers.google.com/ar/reference/java/com/google/ar/core/Pose
- [S2] ARCore Frame / raw depth timestamp and reprojection: https://developers.google.com/ar/reference/java/com/google/ar/core/Frame
- [S3] ARCore performance and anchor stability: https://developers.google.com/ar/develop/performance
- [S4] TAPVid-MV, 2026-09-01, abstract: https://arxiv.org/abs/2609.01899
- [S5] DL-VINS-Factory, 2026-07-02: https://arxiv.org/html/2607.01757v1
- [S6] TAPNext++, 2026-04-12: https://arxiv.org/html/2604.10582v1
- [S7] TAPIP3D: https://arxiv.org/html/2504.14717v3
- [S8] Official XFeat: https://github.com/verlab/accelerated_features
- [S9] Official LightGlue: https://github.com/cvg/LightGlue
- [S10] ARKit world tracking: https://developer.apple.com/documentation/arkit/understanding-world-tracking
- [S11] Official OpenCV tracking reference: https://docs.opencv.org/5.0/main_modules/video_track.html

No measured smartphone comparison of these learned models was performed. The
research identifies promising components and falsifiable integration changes,
not a universal best tracker or an absolute-accuracy guarantee.
