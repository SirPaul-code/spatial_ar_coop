# StableAR SDK development contract

Write only on research_sdk. No merges or writes to main, showme/*, fresh/*,
outdoor/* or any other branch. Other agents may be operating there concurrently.
Do not publish shared release tags, change repository settings or replace existing
app signing identities. The standalone sdk build must not build android/app.

The product is a local stable-surface SDK on top of native VIO. Two-phone alignment,
video calls and cloud anchors are optional host projects, not dependencies.
Do not silently bundle model weights or noncommercial/GPL/unknown dependencies.
Verify exact artefact licences and keep runtime/build/test scopes separate.
Preserve immutable original point identity, anchor-local geometry, timestamps,
thread confinement, bounded queues, and correction-generation checks.
No claim of physical accuracy, mobile FPS, calibrated covariance or commercial
licence clearance follows from synthetic tests or CI. Keep README limits current.
