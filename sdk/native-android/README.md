# StableAR native Android / ARCore

This AAR builds the shared C++20 core through the Android NDK and exposes a typed Kotlin API. `ArCoreNativeAdapter` consumes a host-owned ARCore `Session`/`Frame`; it never creates a second camera or ARCore session.

ARCore camera pose (+Y up, -Z forward) is converted once to StableAR camera convention (+Y down, +Z forward) by composing a 180-degree X rotation at the camera boundary. World coordinates remain in the host ARCore world basis.

Depth samples preserve raw/smoothed/point-cloud origin and source timestamps. The adapter is owner-thread confined because native anchor callbacks synchronously touch ARCore anchors.
