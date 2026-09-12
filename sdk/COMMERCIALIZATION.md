# StableAR commercial release checklist and SDK boundary

StableAR is intended to be distributable as an SDK, not as a copy of the Spatial AR product. This repository is an engineering preview; it is not a legal statement that every binary is already cleared for resale.

## SDK / product boundary

The SDK owns local **static-material attachment stability above host AR/XR VIO/SLAM**. It must remain independent of:

- any particular app UI;
- WebRTC/WebSocket/server protocols;
- Cloud Anchor map sharing;
- customer accounts or billing;
- the Spatial AR product's SITE coordinate model;
- application-specific object/person detectors.

A host supplies its ARCore/ARKit/OpenXR session, camera/depth observations and an initial material point. StableAR returns bounded refined attachment state and quality. The host decides how that attachment is stored, shared or rendered.

`integration/spatial-stablear` is a **consumer/reference integration**, not part of the SDK API surface.

## Deliverables

Current repository packaging directions are:

- Android: Kotlin/Android AAR modules plus native vision AAR and the pinned XFeat model payload;
- native: static/shared C/C++ package with C ABI;
- Apple: core and XFeat-native XCFrameworks; neural LiteRT runtime still pending;
- Unity: managed/native ABI bindings;
- OpenXR: host adapter/coordinate contract.

Production distribution should come from `sdk/tools/export_multiplatform_sdk.py` or an equivalent controlled release export, not by giving customers the whole product repository.

## Technical licensing model

`sdk/licensing` implements the intended entitlement pattern:

1. the vendor keeps the **ECDSA P-256 private issuer key** outside customer artifacts;
2. customer/runtime artifacts contain only public verification material;
3. `STABLEAR1` tokens sign canonical claims with ECDSA-P256/SHA-256 and bind product/customer/application/platform/features/time window;
4. key rotation is explicit and versioned;
5. runtime validation can be offline without a network call on every frame.

The `STABLEAR1` entitlement is technical enforcement, not the legal grant. Client binaries can be patched; contractual rights, application binding, code signing and controlled distribution remain necessary.

A reasonable commercial structure is per-application/per-platform annual licensing, an evaluation tier, and negotiated enterprise/source-access terms. Tracking itself should stay offline; any online lease/account layer should tolerate a bounded offline period rather than becoming a per-frame dependency.

Never ship private issuer keys, signing scripts configured with secrets, or product admin credentials in customer packages.

## Model and third-party provenance

The XFeat mobile model is pinned by exact revision, byte size and SHA-256 in `sdk/models/xfeat/manifest.json`. CI must continue checking the exact packaged payload.

A commercial binary release must not be marked cleared until all of the following are recorded for the exact release SHA:

- physical-device validation on each advertised platform/device class;
- exact binary dependency inventory and third-party notices;
- OpenCV native provenance/notices reconciled for every shipped ABI/platform;
- ARCore/Google API terms reviewed against the intended business/distribution model;
- Apple/Meta/OpenXR platform terms and required app entitlements reviewed for the advertised features;
- owner EULA/SDK license, evaluation terms, privacy terms, contributor/IP policy and support/SLA terms finalized;
- code signing/reproducible artifact checks completed;
- entitlement issuer private key held only in KMS/HSM or an isolated issuer service;
- release artifacts built from CI, hashed and linked to their source commit;
- any `commercialReleaseCleared`-style release flag changed only by an explicit owner release approval process.

## Comparative performance evidence

The commercial value proposition should be stated in terms that StableAR controls: **material attachment stability and reacquisition under camera motion**, not replacement global SLAM.

Do not advertise numerical superiority from:

- synthetic tests;
- CI;
- upstream XFeat papers/model cards;
- one hand-picked video;
- the mere presence of a learned matcher.

`sdk/benchmark` implements a paired physical A/B comparison against an unrefined stock ARCore attachment. Both candidates start from the **same root exposure, exact pixel and metric seed**:

- Stock ARCore = the original unrefined point on the same native anchor;
- StableAR = that same initial point followed by the normal StableAR correspondence + held-out bounded correction path.

Ground truth is independent from StableAR. The preferred field mode records the exact tapped root exposure/pixel, detects the four corners of a printed ArUco marker offline, maps the tap into marker-plane coordinates by homography, then reprojects that same physical material point from independently detected marker corners in every later frame. XFeat/LK/ORB output is never used as truth.

`score_aruco.py` scores one physical run. `aggregate_runs.py` is the commercial evidence gate: it treats an **independently captured run as the statistical unit** and cluster-bootstraps run-level paired improvement. It deliberately does not treat adjacent video frames as independent evidence.

A comparative claim such as "lower p95 attachment error than stock ARCore" is publishable only for the tested scenario/device class when retained physical data shows:

- independent `aruco_root_homography` ground truth or stronger external calibrated tracking;
- enough paired visible frames **and enough independent runs** for the published scope;
- StableAR pooled p95 screen-space material-point error lower than stock ARCore;
- 95% run-cluster bootstrap CI for mean paired improvement entirely above zero;
- a high fraction of independent runs with positive paired improvement;
- false-lock rate within the configured product threshold and not materially worse than Stock ARCore;
- availability reported rather than hiding lost predictions;
- latency/thermal conditions reported alongside accuracy;
- raw sessions retained for reproduction.

The default `aggregate_runs.py` engineering gate requires five eligible independent runs, 500 total paired frames, 30 paired frames per eligible run, at least 90% StableAR availability, at most a two-percentage-point availability drop versus Stock ARCore, at most 2% false-lock rate, at least 80% positive runs, lower StableAR pooled p95, and a positive lower 95% cluster-CI bound. These defaults are protocol parameters, not universal scientific constants; any published benchmark must record the exact gate configuration.

A first claim should be deliberately narrow, for example:

> On the listed supported Android devices using the published orbit/scale/occlusion protocol, StableAR reduced p95 screen-space static-material attachment error versus the same unrefined ARCore anchor.

Insert actual devices, run count and measured values only after physical sessions exist.

## Reference integration

The two-phone Spatial AR consumer demonstrates the intended host contract without contaminating the SDK with product networking:

- Cloud Anchors/manual alignment continue to own the global shared SITE frame;
- the host uses practical ARCore hit/depth/plane evidence to seed a metric point;
- `ArCoreAdapter.placeWithDepthPrior(...)` binds the exact material pixel to that host seed;
- XFeat/LK/ORB plus StableAR geometry refine the local static attachment;
- a remote SITE point is only a prediction for another phone; that phone may create a local visual attachment after conservative depth agreement;
- viewer-local corrections never feed back into the creator's canonical SITE point.

## Current limitations that must remain visible

- current material correction is scalar depth along the immutable original click ray, not full tangential XYZ optimization;
- Android physical A/B evidence is still pending; there is no physical superiority claim yet;
- Apple XFeat matcher compiles, but iOS LiteRT model execution is not yet a shipping runtime;
- no moving/deforming-object stability guarantee;
- no universal FPS/latency/thermal claim;
- StableAR does not replace host VIO/SLAM or cross-session mapping.
