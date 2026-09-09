# ShowMe local protocol v1

This protocol is independent of Spatial Sync V6 and does not modify it.

Invite URL: `http://<LAN-IP>:<port>/#token=<192-bit-base64url-token>`. Fragment keeps the token out of HTTP asset requests and referrers. The browser authenticates its same-origin WebSocket with token/client/name query parameters. The server does not log URLs, SDP or video. LAN HTTP is not encryption.

`GET /ws?token=...&client=...&name=...`: masked, unfragmented RFC6455 frames; 32 KiB maximum client payload. Server sends `waiting`, then `ready` after owner approval. No camera frames or annotation execution before approval.

## Frame flow

Client: `{"type":"pull","since":"17"}`.

Server: `idle`, `paused` with reason, or binary:

```
uint32 big endian: JSON byte length
UTF-8 JSON: {type:"frame",frameId:"18",width:720,height:1280,
             tracking:true,depthPoints:5000,count:1,
             annotations:[{id,tool,color,label,verified,points:[[u,v],...]}]}
JPEG bytes: upright raw camera image
```

JSON and JPEG always belong to the same captured frame. Numeric-looking frame IDs are strings to avoid JS 53-bit monotonic timestamp mistakes. Off-camera vertices are null. Browser pulls again only after decode/render, unless frozen or drawing.

## Annotation flow

`hold(frameId)` before starting the gesture; `release` when resuming. A held frame still expires after 45 seconds from camera capture.

```
{"type":"draw","requestId":"unique-8-to-80-char-id","frameId":"18",
 "tool":"pin|pointer|arrow|pen|circle","color":"mint|amber|coral|blue",
 "label":"optional, max 80 characters","points":[[0.5,0.3]]}
```

Coordinates are normalized in the UPRIGHT camera image, not the CSS canvas box. The browser expands arrows/circles into a bounded polyline. Max 64 points. Pin/pointer require exactly one vertex; other shapes at least two.

ACK contains requestId, annotationId, count and depth source only after actual AR anchor creation. Rejection contains requestId and a human-readable reason. Repeating an ID does not create a second mark. `pointer` lives for ~3 seconds; other tools last until remove/clear/end.

Other messages: `undo`, `remove(id)`, `clear`, `chat(text <=300)`, `ping`/`pong`. Server responses: `changed`, `cleared`, `chat`, `error`.

Audio signaling: `voiceOffer(sdp)`, `voiceAnswer(sdp)`, `voiceIce(candidate,sdpMid,sdpMLineIndex)`, `voiceStop`, `voiceAvailable`, `voiceClosed`, `voiceError`. Native mic permission and toggle are mandatory. Browser getUserMedia is secure-context-only; ordinary LAN HTTP falls back to recvonly.
