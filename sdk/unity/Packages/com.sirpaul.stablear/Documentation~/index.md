# StableAR Unity preview

The Unity package is a thin binding over the native C ABI. Your AR Foundation/OpenXR host owns camera/session/depth. Implement `IAnchorStore` using the host's anchor primitive and feed exact frame pose/intrinsics/depth to `StableAR.Session`.

Unity world coordinates are left-handed. Use `UnityCoordinates.WorldPose` for anchors/world poses and `UnityCoordinates.CameraPose` for camera frames; do not substitute a simple 180-degree rotation.
