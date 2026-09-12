# StableAR SDK development contract

For multiplatform SDK work, operate only on `stablear/multiplatform-sdk`. Treat
`research_sdk` at `38978da448b6610606e9145367b9b31328bb5cc0` as the frozen Android
reference. Do not merge/write to main, ShowMe branches, fresh/*, outdoor/* or other
product branches. Other agents may operate there concurrently.

Read `STATUS.md` before changing code; it is the durable handoff/source of current
implementation and validation status. Update it whenever a platform moves from
scaffold -> compile-tested -> device-tested.

The product is a local stable-material-attachment SDK on top of host VIO/SLAM.
Two-phone alignment, video calls, cloud anchors and networking are host integrations,
not core dependencies. Platform wrappers must not duplicate the C++ solver.

Do not publish release tags, change repository settings, replace app signing
identities, bundle private entitlement issuer keys, or silently add model weights /
noncommercial / unknown dependencies. Keep native vision separately auditable.

Preserve immutable original point identity, exact frame/timestamps, anchor-local
geometry, thread confinement, bounded queues/history, and correction epoch/generation
checks. No claim of physical accuracy, mobile/XR FPS, calibrated covariance or
commercial licence clearance follows from synthetic tests or CI.
