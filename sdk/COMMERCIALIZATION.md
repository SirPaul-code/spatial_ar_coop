# StableAR commercial release checklist

This repository contains an engineering preview, not a legal statement that every binary is cleared for resale.

## Release gates

A commercial binary release must not be marked cleared until all of the following are recorded for the exact release SHA:

- physical-device validation on each advertised platform/device class;
- exact binary dependency inventory and third-party notices;
- OpenCV native provenance/notices reconciled for every shipped ABI/platform;
- ARCore/Google API terms reviewed against the intended business/distribution model;
- Apple/Meta/OpenXR platform terms and required app entitlements reviewed for the advertised features;
- owner EULA/SDK license, evaluation terms, privacy terms, contributor/IP policy and support/SLA terms finalized;
- code signing/reproducible artifact checks completed;
- entitlement issuer private key held only in KMS/HSM or isolated issuer service;
- release artifacts built from CI, hashed and linked to their source commit;
- `commercialReleaseCleared` changed only by an explicit release approval process.

## Recommended licensing model

Use a per-application/per-platform annual SDK license, with an evaluation tier and negotiated enterprise/source-access tier. Tracking stays offline. A short signed lease is refreshed when online and may include a bounded offline grace period.

The `STABLEAR1` entitlement is technical enforcement, not the legal grant. Client binaries can be patched; contractual rights, application binding, code signing and controlled distribution remain necessary.

## Product claims

Until physical ground-truth runs exist, do not advertise millimetre/centimetre accuracy, drift percentages, FPS, thermal endurance, battery cost, motion-to-photon latency, or superiority over ARCore/ARKit/Vuforia/etc. CI and synthetic contracts prove software invariants only.
