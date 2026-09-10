# StableAR Apple preview

`build_xcframework.sh` builds the C++20 core as `StableARNative.xcframework` for iOS device + simulator. `Sources/StableARApple` is a Swift façade and host-owned ARKit adapter.

ARKit world tracking remains the VIO/SLAM provider. StableAR converts only the camera local axes from ARKit (+Y up/-Z forward) to StableAR (+Y down/+Z forward). `sceneDepth` is optional and sampled as external frame-aligned metric depth; `smoothedSceneDepth` is a fallback, not independent evidence.

This does not claim unrestricted visionOS camera support. Vision Pro camera access has separate entitlement/product constraints and must be reviewed for the actual distribution model.
