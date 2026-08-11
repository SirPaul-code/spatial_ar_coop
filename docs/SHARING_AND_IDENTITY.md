# Server identity and private place sharing

Spatial AR Coop intentionally has **no global discovery service**. Installing the app does not reveal a list of other people's servers or maps.

## The three values

A deployment has three different values, and they are deliberately not interchangeable:

| Value | Secret? | Scope | Who needs it? |
| --- | --- | --- | --- |
| `serverId` | No | One server installation | Everyone; it identifies the expected server |
| `sar_admin_...` | Yes | Entire server | Server owners/admins only |
| `sar_map_...` | Yes | Exactly one place/map | Participants invited to that one place |

The server creates `serverId` and the owner admin token on first boot if they were not configured explicitly. They are persisted in `/data/server.json` together with independent per-map keys.

**Participants do not need the owner token.** Being on the same Tailscale network only makes the server reachable; it does not reveal its maps. A participant learns about a place only when an owner intentionally shares that place's QR/invite.

## Normal sharing flow

The intended day-to-day flow is:

1. The owner runs one self-hosted Spatial AR server.
2. The owner creates and finishes setup for a place.
3. The owner opens **Share & manage** in Android or **Share place QR** in the operator dashboard.
4. Another phone taps **Scan place QR**.
5. The receiving app verifies the server identity and imports only that one map/key.
6. Both phones open **Live AR** for the place.

No one should manually type a long `sar_map_...` key during normal use.

## Why both `serverId` and a key?

A URL alone is not a stable identity. DNS, an IP address, a reverse proxy, or a Tailscale endpoint can be changed or accidentally point to another deployment.

A map invite therefore includes the expected `serverId`. The Android client first calls:

```text
GET /api/v1/info
```

and refuses the invite if the endpoint returns another `serverId`.

`serverId` does **not** grant access. The participant must also possess the map's current `mapKey`.

## Invite / QR payload

The QR encodes the same deep link used for text sharing:

```text
spatialar://join?url=https%3A%2F%2Fserver.example&serverId=srv_...&mapId=map-...&key=sar_map_...
```

The values mean:

- `url` — endpoint for that self-hosted server,
- `serverId` — expected stable server identity,
- `mapId` — one specific place,
- `key` — bearer secret only for that map.

The QR never contains the owner admin token.

A server configured without `SPATIAL_PUBLIC_URL` can generate an invite without a URL. For the least confusing sharing experience, configure `SPATIAL_PUBLIC_URL` to the private HTTPS/Tailscale endpoint participants actually use.

## Owner workflow

1. Start or connect to your self-hosted server.
2. Read its owner identity with `docker compose exec spatial-server npm run identity`.
3. In Android open **Owner server & diagnostics**.
4. Enter the server URL and `sar_admin_...` token, then tap **Save & verify owner server**.
5. Create a place.
6. Follow the on-camera guided map setup. Shared origin, Cloud Anchor placement and floor detection are automatic in the normal path; **More** contains recovery/manual tools.
7. When **Finish setup** becomes available, finish the map.
8. Open **Share & manage** and show the QR to intended participants.

The owner connection is a profile for server administration. Changing it does not rewrite the URLs or keys of maps already stored on the phone.

The web operator dashboard is also owner-only. It asks for the `sar_admin_...` token because it can list every map, inspect server diagnostics and create share QR codes. A `sar_map_...` participant key is intentionally rejected there.

## Participant workflow

1. Make sure the phone can reach the server, for example via the intended Tailscale connection.
2. In Android tap **Scan place QR** and scan the owner's QR. A pasted/deep link remains available as a fallback.
3. The app verifies `/api/v1/info` against the QR's `serverId`.
4. It requests only the specified `mapId` using the QR's `mapKey`.
5. If authorized, it stores that place connection locally and can enter **Live AR**.
6. Live AR resolves a saved Cloud Anchor automatically. Reporting is optional: **Start reporting** enables local object detection; otherwise the phone observes shared tracks only.

The participant never needs and should never receive the owner's `sar_admin_...` token.

## Isolation behavior

The server enforces these properties:

- `GET /api/v1/maps` requires the admin token.
- A `mapKey` for map A cannot read map B.
- A `mapKey` for map A cannot join map B's WebSocket room.
- Map-specific requests without the right key return an opaque `404`, so the API does not confirm whether that private map ID exists.
- An admin token can access every map on its own server because it is the recovery/owner credential.
- There is no endpoint that enumerates other Spatial AR server installations.

The automated server security test covers cross-map REST/WebSocket isolation and QR access.

## Revocation

The owner can rotate one map key without changing the server identity or any other map:

```text
POST /api/v1/maps/<mapId>/rotate-key
Authorization: Bearer <adminToken>
```

The Android owner UI exposes the same operation as **Share & manage → Revoke old invites & create new QR**.

After rotation:

- old invite links no longer authenticate,
- active WebSocket sessions for that map are disconnected immediately,
- other maps are unaffected,
- the owner gets a replacement QR/link,
- existing participant devices need the replacement invite to reconnect.

## Restores and clones

`/data/server.json` is part of the server identity. A normal backup/restore should preserve it together with maps and chunks.

If you intentionally create an independent clone, start it with a fresh data directory so it receives a new `serverId` and new secrets. Do not copy `server.json` into an unrelated public deployment unless you intentionally want it to be the same logical server identity.

## Threat boundary

A `mapKey` is a bearer secret. Anyone who obtains a place QR/link can collaborate on that map until the key is rotated. Protect invites like private collaboration links:

- show/send them only to intended participants,
- use HTTPS on untrusted networks,
- prefer Tailscale Serve or another authenticated/private network path,
- rotate the key when a participant should no longer have access,
- never place an `adminToken` in a participant invite.

For an internet-facing service, add deployment-specific rate limiting, reverse-proxy protections, monitoring and access policy in addition to the application key model.
