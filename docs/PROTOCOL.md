# SPV2 direct peer protocol and coordinate conventions

Spatial Sync V2 does not use HTTP or WebSockets. After Wi‑Fi Aware discovery the two Android devices establish an encrypted Wi‑Fi Aware network data path and open one direct TCP stream.

## Wire framing

Every record is:

```text
uint32 magic       = 0x53505632  // "SPV2"
uint8  version     = 2
uint8  type
uint32 payloadSize
byte[payloadSize] payload
```

Maximum payload size is 8 MiB. Numeric fields use Java `DataOutputStream` / `DataInputStream` representation (big-endian). Camera JPEG bytes are carried as raw bytes on the wire even though `CapturedFrame` internally retains the legacy Base64 field.

Message types:

```text
1 HELLO
2 FRAME
3 POI
4 CLEAR_POI
5 RANGE
6 QUALITY
```

## Discovery / room handshake

The creator publishes the Wi‑Fi Aware service:

```text
spatialnomap.v2
```

Service-specific info:

```text
V2|<ROOM>|<USERNAME>
```

The joining device subscribes to that service and shows matching rooms in the UI. When selected it sends an Aware discovery message:

```text
JOIN|<ROOM>|<USERNAME>
```

The publisher creates a TCP `ServerSocket`, requests an encrypted Wi‑Fi Aware data path, and transfers the selected server port through the Wi‑Fi Aware network specifier. It acknowledges readiness to the subscriber with:

```text
NDP|<ROOM>
```

The subscriber requests its matching data path and obtains the publisher's peer-scoped IPv6 address and port from `WifiAwareNetworkInfo`. It connects through that Aware `Network.socketFactory`; the socket is therefore bound to the direct peer network rather than arbitrary internet routing.

The current prototype derives the NDP PSK from the random room code. This protects the direct link from unrelated peers but is not intended as a long-term authenticated identity system.

## HELLO

Payload:

```text
UTF username
UTF deviceModel
```

Used to replace provisional discovery metadata with the connected peer identity.

## FRAME

Payload:

```text
int64 timestampNs
float32 cameraTranslation[3]
float32 cameraQuaternion[4] // x,y,z,w
float32 fx, fy, cx, cy
int32 imageWidth, imageHeight
int32 jpegLength
byte[jpegLength] grayscaleJpeg
int32 metricPointCount
float32 metricPoints[metricPointCount][5]
```

Metric point layout:

```text
[u_image, v_image, X_world, Y_world, Z_world]
```

Each phone sends frames continuously. The sender uses a latest-frame back-pressure queue: if processing/networking falls behind, stale camera frames are dropped instead of building latency.

## POI

Payload:

```text
int64 poiId
UTF ownerUsername
float32 pointWorld[3]
int64 createdAtUnixMs
```

`pointWorld` is expressed in the sender's current ARCore world. The receiver transforms it through its locked `T_localWorld_remoteWorld` before rendering.

## CLEAR_POI

Empty payload. Clears the current POI on both peers.

## RANGE

Payload:

```text
float32 distanceM
float32 stdDevM
int32 successfulSamples
```

Source is peer Wi‑Fi Aware RTT. V2 does **not** use RTT to manufacture the metric scale of the visual solution. It is an independent consistency measurement: a PnP transform whose implied phone separation is grossly incompatible with RTT is penalized/rejected.

## QUALITY

Payload:

```text
float32 confidence
int32 stableSolveCount
bool ready
```

Each device solves alignment independently. POI placement is enabled only after both peers report a stable local lock.

## Coordinate systems

### ARCore camera

ARCore/OpenGL camera axes:

```text
+x right
+y up
-z forward
```

ARCore camera pose is:

```text
T_W_C
```

mapping camera coordinates into that device's local ARCore world.

### OpenCV camera

PnP uses:

```text
+x right
+y down
+z forward
```

Output-axis conversion is:

```text
S = diag(1, -1, -1)
S4 = diag(1, -1, -1, 1)
```

For a receiver B solving metric points from remote world A:

```text
solvePnP -> T_CVB_WA
T_CB_WA = S4 * T_CVB_WA
T_WB_WA = T_WB_CB * T_CB_WA
```

A remote POI then becomes:

```text
P_WB = T_WB_WA * P_WA
```

The receiver reprojects `P_WB` every render frame with its current ARCore view/projection matrices. If its projection is outside the viewport or behind the camera, the renderer instead derives a camera-space bearing and drives the edge-arrow UI.

## Alignment quality

The current V2 gate combines:

- SIFT ratio-filtered feature matches,
- metric support association,
- PnP RANSAC inlier count,
- median reprojection error,
- image-space inlier coverage,
- consecutive transform consistency,
- peer readiness exchange,
- optional RTT distance consistency,
- ARCore tracking continuity.

This protocol deliberately transports diagnostics separately from POI data so future solvers (LightGlue, LoFTR, UWB/Channel Sounding factors, multi-peer factor graphs) can replace the current SIFT/PnP implementation without changing the user-facing POI model.
