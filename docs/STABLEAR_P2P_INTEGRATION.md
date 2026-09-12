# StableAR integration in the direct P2P Spatial Sync runtime

## Correct product baseline

This branch intentionally starts from `fresh/no-map-runtime-poc@a5970e9be7fef9434dbf2e681274dc56c9462fe4` (`feat: converge vehicle tracks and add precision surface snap`). That is the direct phone-to-phone Spatial Sync / Anduril-style runtime whose signed Android build was published as `latest-dev`.

The integration branch is `integration/p2p-stablear`. The original `fresh/no-map-runtime-poc` branch and its `latest-dev` release are not moved by this work.

## StableAR source checkpoint

The complete `/sdk` tree on this integration branch is byte-for-byte the same Git tree as the current `stablear/multiplatform-sdk@59ec2cb952008c3c7b92843ee5088a466a967cc7` checkpoint as of 2026-09-12:

```text
sdk tree SHA: 4076558f24f8454a88791dc121ba3937424b0547
```

That means this product branch is not carrying an older hand-copied XFeat/core subset: it has the same current SDK source tree, including the pinned XFeat/LiteRT runtime, shared matcher, staged-template admission, Android ARCore adapter, LK/ORB fallback, packaging/compliance material and the rest of the multiplatform source tree.

Latest integration runtime code checkpoint before this documentation refresh:

```text
49d195be2bbe36fd0f930ad3132a50f6dcefb952
fix(p2p): guard async StableAR root admission
```

Preceded by:

```text
1e25835f86fb8ed72ebfee5ebc8b7e453b3a837b
fix(p2p): keep dormant StableAR seeds fail-closed
```

## Non-negotiable boundary

StableAR is a static-material attachment SDK. It is not the shared-world solver and it is not the peer transport.

The following product paths remain unchanged in ownership:

- Wi-Fi Aware discovery and NDP/TCP transport;
- encrypted direct peer connection;
- V6 wire framing;
- acquisition bursts;
- host-canonical P2P alignment;
- shared coordinate conversion;
- RTT/BLE/gravity/alignment gates;
- dynamic vehicle tracking and room-level vehicle association.

StableAR is injected only after the product has an already trusted local static material point.

## Local POI flow

```text
screen tap
  -> existing ARCore Plane / Point / DepthPoint + metric support policy
  -> existing trusted local world point
  -> StableAR placeWithDepthPrior(exact CPU-image pixel, metric z-depth)
  -> exact root grayscale exposure
  -> XFeat/LiteRT preferred; SDK LK/ORB fallback
  -> StableAR held-out geometric acceptance / anti-drift template admission
  -> refined local material point
  -> shared through the existing P2P POI message
```

The existing placement policy is deliberately retained because it was physically tuned and is more permissive at close range than StableAR's conservative standalone `SurfaceFitter`. StableAR does not reinterpret ARCore confidence as metric covariance; the bridge supplies a bounded integration prior only.

A depth-prior-only StableAR seed is **not** allowed to become product authority before immutable visual root evidence is actually captured. Until XFeat or LK/ORB successfully arms that exact root exposure, rendering and product behavior continue to use the existing ARCore anchor. This prevents an asynchronous root-capture failure from leaving a stale StableAR point in control.

If the legacy/product surface bootstrap refines a point before StableAR visual root evidence becomes active, the dormant StableAR seed is replaced from that newer trusted point instead of silently returning success with obsolete geometry.

Asynchronous root admission is attachment-generation safe at the bridge level: the owner thread maintains an `activeAttachments` set. If a dormant attachment is replaced, removed, cleared or invalidated while its worker-side root capture is still queued, that old worker result cannot later resurrect the stale attachment into `visualRoots`. This also prevents a stale root id from keeping camera/LiteRT work alive after the corresponding product target is gone.

## Remote POI flow

A remote phone initially receives the existing shared world point and sender surface reference. The pre-existing fail-closed visual/metric resolver remains as a bootstrap path because the receiving device does not yet own local StableAR root evidence for that material point.

Once that bootstrap produces a trusted local observation, the receiving phone seeds its own StableAR attachment from the current local ARCore exposure. From then on StableAR owns local material refinement and the legacy resolver stops competing with it.

Receiver-local StableAR corrections are not echoed back as a feedback loop. The creating phone remains responsible for canonical POI updates; each receiver may locally stabilize the same shared point against its own camera evidence.

## Vision worker

`StableArP2pBridge` keeps `ArCoreAdapter` calls on the GL owner thread and moves copied grayscale evidence to one bounded worker. The worker uses the same SDK policy as the StableAR Lab reference integration:

1. XFeat/LiteRT with GPU preferred and CPU fallback;
2. SDK LK/ORB classical fallback;
3. XFeat staged template candidate;
4. StableAR geometric/held-out decision on the owner thread;
5. commit the exact-frame staged template only when that decision is accepted, otherwise discard it.

No unchecked visual match is allowed to train the template bank.

The bridge does not run camera/LiteRT tracking for depth-prior-only attachments that never captured immutable visual root evidence. This keeps failed/dormant seeds fail-closed instead of continuously consuming inference work.

## Dynamic objects

Cars remain dynamic P2P world tracks. Do not create StableAR attachments or permanent ARCore anchors for cars. Their identity, witness lifetime and presentation policy are a separate product layer.

## Packaging and CI

The app consumes `/sdk` as normal Gradle projects (`core`, `arcore`, `vision`, `native-vision-android`). The integration release is published separately as `p2p-stablear-latest`; CI explicitly checks that the APK contains both the pinned XFeat asset and `libstablear_vision_jni.so`.

The integration workflow runs:

- Android unit tests;
- debug + release lint;
- debug + release APK assembly;
- integrated APK payload verification for `assets/models/xfeat.tflite`;
- integrated APK payload verification for `lib/arm64-v8a/libstablear_vision_jni.so`;
- stable signing-certificate verification for both APKs;
- GitHub prerelease publication on a successful `integration/p2p-stablear` push.

A pre-existing Android lint false-positive/blocker in `BirdEyeWorldView.kt` around copied `Paint.alpha` assignments was removed without changing the P2P/StableAR architecture. Do not disable the `Range` lint globally; keep lint as a real gate.

Do not call the integration release-ready until the workflow for the final documentation/code HEAD is green and `p2p-stablear-latest` targets that exact HEAD.

## Physical validation still required

CI proves source/build/package/signature contracts, not physical AR accuracy. On two real phones, validate at minimum:

1. CREATE/JOIN and `LOCKED` behavior remains equivalent to the P2P baseline;
2. local static POI placement still succeeds at the previously useful close ranges;
3. once visual root evidence arms, XFeat/LK/ORB refinement does not jump to nearby texture;
4. orbit 20/45/70+ degrees, distance change, blur, illumination change and temporary occlusion/reacquisition;
5. remote POI bootstrap transitions to receiver-local StableAR without feedback oscillation;
6. vehicles remain dynamic P2P tracks and are unaffected by StableAR;
7. pause/resume, tracking loss, target removal, clear and session replacement leave no stale visual authority.

## Accuracy claims

CI/build success is not a physical-accuracy benchmark. The current StableAR correction model is still bounded by the SDK's present geometry, including the current original-ray correction ceiling. Do not claim universal superiority over stock ARCore until the independent physical benchmark protocol has been run on real devices.
