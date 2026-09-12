# StableAR integration in the direct P2P Spatial Sync runtime

## Correct product baseline

This branch intentionally starts from `fresh/no-map-runtime-poc@a5970e9be7fef9434dbf2e681274dc56c9462fe4` (`feat: converge vehicle tracks and add precision surface snap`). That is the direct phone-to-phone Spatial Sync / Anduril-style runtime whose signed Android build was published as `latest-dev`.

The integration branch is `integration/p2p-stablear`. The original `fresh/no-map-runtime-poc` branch and its `latest-dev` release are not moved by this work.

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

## Remote POI flow

A remote phone initially receives the existing shared world point and sender surface reference. The pre-existing fail-closed visual/metric resolver remains as a bootstrap path because the receiving device does not yet own local StableAR root evidence for that material point.

Once that bootstrap produces a trusted local observation, the receiving phone seeds its own StableAR attachment from the current local ARCore exposure. From then on StableAR owns local material refinement and the legacy resolver stops competing with it.

Receiver-local StableAR corrections are not echoed back as a feedback loop. The creating phone remains responsible for canonical POI updates; each receiver may locally stabilize the same shared point against its own camera evidence.

## Vision worker

`StableArP2pBridge` keeps ARCoreAdapter calls on the GL owner thread and moves copied grayscale evidence to one bounded worker. The worker uses the same SDK policy as the StableAR Lab reference integration:

1. XFeat/LiteRT with GPU preferred and CPU fallback;
2. SDK LK/ORB classical fallback;
3. XFeat staged template candidate;
4. StableAR geometric/held-out decision on the owner thread;
5. commit the exact-frame staged template only when that decision is accepted, otherwise discard it.

No unchecked visual match is allowed to train the template bank.

## Dynamic objects

Cars remain dynamic P2P world tracks. Do not create StableAR attachments or permanent ARCore anchors for cars. Their identity, witness lifetime and presentation policy are a separate product layer.

## Packaging

The app consumes the current SDK snapshot imported under `/sdk` as normal Gradle projects (`core`, `arcore`, `vision`, `native-vision-android`). The integration release is published separately as `p2p-stablear-latest`; CI explicitly checks that the APK contains both the pinned XFeat asset and `libstablear_vision_jni.so`.

## Accuracy claims

CI/build success is not a physical-accuracy benchmark. The current StableAR correction model is still bounded by the SDK's present geometry (including its current original-ray correction ceiling). Do not claim universal superiority over stock ARCore until the independent physical benchmark protocol has been run on real devices.
