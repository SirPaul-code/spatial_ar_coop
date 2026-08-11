# Field use: owner, QR sharing, map setup and Live AR

This page describes the normal phone workflow. The advanced controls exist for recovery and diagnostics; you should not need them for a healthy new map.

## 1. Who needs which credential?

Think of the system as two separate layers:

- **Network reachability** — Tailscale/Wi-Fi/Internet lets a phone reach the self-hosted server.
- **Spatial place access** — a private place QR grants access to exactly one map on that server.

The server owner additionally has one `sar_admin_...` token. That token is powerful: it can list/manage every map and open the operator dashboard. Do not give it to participants.

Participants scan a place QR containing:

- server URL,
- stable `serverId`,
- one `mapId`,
- that map's `sar_map_...` key.

Two participants therefore do **not** need the owner token merely to share Live AR. They need reachability to the same server and an invite for the same place.

## 2. Owner: connect a self-hosted server

On the owner phone:

1. Open **Owner server & diagnostics**.
2. Enter the server URL that the phones can actually reach, for example a private Tailscale Serve HTTPS address.
3. Enter the server's `sar_admin_...` owner token.
4. Tap **Save & verify owner server**.

That action checks `/api/v1/info`, verifies the owner credential and refreshes owned maps. There are no separate **Save**, **Test & Sync**, and **Sync Owner** actions anymore.

The operator web dashboard is owner-only. Opening it from the Android owner screen transfers the already configured admin token in a URL fragment; the dashboard consumes and removes the fragment locally before loading maps. A `sar_map_...` key is intentionally rejected by the admin dashboard.

## 3. Create and map a new place

Tap **New place**. The server creates the map and a private per-map key automatically, then Android opens guided **Map setup**.

For a brand-new map, the normal setup is automatic:

1. Wait until ARCore reports tracking and the header changes from preparing to scanning.
2. The first stable tracked camera pose establishes the gravity-aligned shared site origin automatically.
3. Walk slowly through the area while pointing the camera at textured, well-lit surfaces from several angles.
4. Sparse scan chunks are saved locally continuously and uploads retry automatically.
5. Cloud Anchors are hosted automatically when feature quality and spacing are suitable.
6. The app opportunistically detects a floor/ground reference automatically. Ground is a refinement for object feet/wheels; it is not a prerequisite for capturing the map.
7. Watch the header/detail text. It reports captured points/chunks, anchor hosting, feature quality, floor state and server sync state.
8. **Finish setup** remains disabled as **Keep scanning** until there is useful geometry and, in a Cloud-Anchor-enabled build, at least one hosted Cloud Anchor.
9. When **Finish setup** enables, tap it. The app flushes local data, marks the map ready, queues the final server sync, shows a completion confirmation and returns to the place list.

A server outage does not discard mapping: the header explicitly says the scan is saved locally and upload retry is automatic.

### The `More` menu during map setup

These are recovery/diagnostic tools, not normal mandatory steps:

- **Re-establish shared origin** — only for an unfinished/legacy map with no usable Cloud Anchor. Stand at the original map start position and face the original heading before using it. It changes the local ARCore-to-site transform; using it at an arbitrary position would misalign existing geometry.
- **Host Cloud Anchor here now** — forces an anchor host request at the current pose. Normally automatic anchor placement is preferable.
- **Retry nearest failed anchor** — retries a nearby anchor that previously failed/needs rescan.
- **Set floor from camera center** — manual fallback if automatic ground detection never finds a useful floor. Aim the camera center at a visible floor surface.
- **Place shared test marker** — publishes a temporary marker useful for verifying site transforms and WebSocket relay before object detection.
- **Share diagnostics** — exports local app diagnostics.

## 4. Share the place

Once a map is ready, open **Share & manage**.

The phone shows a scannable QR code directly. The QR grants access only to that one place. You can also use **Share invite** or **Copy link** for remote participants.

To revoke access, use **Revoke old invites & create new QR**. The server rotates only that map's key, rejects the old invite and disconnects currently connected sessions using the revoked key. Other places are unaffected.

## 5. Participant: join with QR

On the second phone:

1. Make sure it can reach the server, for example by connecting Tailscale.
2. Open Spatial AR and tap **Scan place QR**.
3. Scan the owner's QR.
4. The app reads the server URL, `serverId`, `mapId` and map key.
5. It calls the server's public info endpoint and refuses the invite if the endpoint has the wrong `serverId`.
6. It then downloads only the invited map metadata using that map's key.
7. The place appears in **Places on this phone**. No owner token is required.

**Paste invite** remains available for a link sent by chat/email.

## 6. Live AR

Tap **Live AR** on a ready place.

The header tells you which stage you are in:

- **Waiting for location / Localizing…** — ARCore is running but the phone has not yet resolved the saved shared frame. Move slowly and look around an area that was seen while mapping/hosting anchors.
- **Localized** — this phone has a site-to-world transform and can spatially render shared tracks.
- **Server connected · live sharing active** — REST/WS reachability is healthy.
- **Server reconnecting · shared tracks temporarily unavailable** — local AR may keep running, but cooperative data is temporarily unavailable.

Every Live participant observes remote tracks. **Start reporting** additionally enables the local object detector and publishes this phone's compact tracks. **Stop reporting** disables detector inference but keeps observation/localization active.

The server identifies normal Live clients as `participant`; their current status distinguishes `observing` from `reporting`.

### The `More` menu during Live AR

- **Re-localize with saved Cloud Anchors** — clears the current resolved reference and starts Cloud Anchor resolution again.
- **Align fallback at saved origin** — shown only when Cloud Anchors are unavailable in the build; both phones must use the same physical origin and heading.
- **Place shared test marker** — useful for checking shared coordinate alignment without object detection.
- **Share diagnostics** — exports app diagnostics.

## 7. Operator dashboard

Open the server root in a browser. The dashboard first asks for the **owner admin token** because it can list all maps on that server.

It then provides:

- map selection and server map status,
- sparse SAC1 point-cloud preview,
- stored anchor transforms/status/errors,
- live participants, localization/status and recent pose/track ages,
- current object tracks,
- **Share place QR** for the selected map.

A place key cannot unlock this dashboard's server-wide map list by design.

## 8. What the server logs should look like

During mapping you should see scan chunks being stored, Cloud Anchors transition from hosting to hosted, and eventually a map status update to ready.

During Live AR, a client should remain connected rather than repeatedly cycling WebSocket close code `1006`. Object-track TTL and WebSocket connection heartbeat are separate mechanisms: stale objects expire quickly, while a mobile/Tailscale WebSocket gets a much longer heartbeat timeout.

A normal explicit Back/Activity close is logged as WebSocket close code `1000` with the activity-close reason.
