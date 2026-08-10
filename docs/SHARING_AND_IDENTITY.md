# Server identity and private map sharing

Spatial AR Coop intentionally has **no global discovery service**. Installing the app does not reveal a list of other people's servers or maps.

## Identity model

A deployment has three different values:

| Value | Secret? | Scope | Purpose |
| --- | --- | --- | --- |
| `serverId` | No | One server installation | Stable identity used to detect wrong endpoints/restores |
| `adminToken` | Yes | Entire server | Owner operations: list/create/delete maps, logs, metrics, map-key rotation |
| `mapKey` | Yes | Exactly one map | Collaborative REST/WebSocket access to one map |

The server creates `serverId` and `adminToken` on first boot if they were not configured explicitly. They are persisted in `/data/server.json` together with the independent map keys.

## Why both `serverId` and a key?

A URL alone is not a stable identity. DNS, an IP address, reverse proxy, or Tailscale endpoint can be changed or accidentally point to another deployment.

A map invite therefore includes the expected `serverId`. The Android client first calls:

```text
GET /api/v1/info
```

and refuses the invite if the endpoint returns another `serverId`.

`serverId` does **not** grant access. The participant must also possess the map's current `mapKey`.

## Invite format

The Android app registers this deep-link shape:

```text
spatialar://join?url=https%3A%2F%2Fserver.example&serverId=srv_...&mapId=map-...&key=sar_map_...
```

The values mean:

- `url` — endpoint for that self-hosted server,
- `serverId` — expected stable server identity,
- `mapId` — one specific place,
- `key` — secret only for that map.

A server configured without `SPATIAL_PUBLIC_URL` can generate an invite without a URL. In that case the receiving app may use its already configured server URL, but it still requires the `serverId` to match.

## Owner workflow

1. Start or connect to your self-hosted server.
2. Read its owner identity with `docker compose exec spatial-server npm run identity`.
3. In the Android app, enter the server URL and `sar_admin_...` token under **Server owner & diagnostics**.
4. Create a place. The server returns a newly generated map key.
5. Complete mapping/anchors.
6. Open **Manage & share → Share invite**.

The owner connection is a profile for server administration. Changing it does not rewrite the URLs or keys of maps already stored on the phone.

## Participant workflow

1. Receive a `spatialar://join?...` link from the owner.
2. Open it on the Android phone, or paste it into **Join a shared place**.
3. The app verifies `/api/v1/info` against the invite's `serverId`.
4. The app requests only the specified `mapId` using its `mapKey`.
5. If authorized, it stores that map connection locally and can participate in its AR session.

The participant never needs the owner's `adminToken`.

## Isolation behavior

The server enforces these properties:

- `GET /api/v1/maps` requires the admin token.
- A `mapKey` for map A cannot read map B.
- A `mapKey` for map A cannot join map B's WebSocket room.
- Map-specific requests without the right key return an opaque `404`, so the API does not confirm whether that private map ID exists.
- An admin token can access every map on its own server because it is the recovery/owner credential.
- There is no endpoint that enumerates other Spatial AR server installations.

The automated server security test covers cross-map REST and WebSocket isolation.

## Revocation

The owner can rotate one map key without changing the server identity or any other map:

```text
POST /api/v1/maps/<mapId>/rotate-key
Authorization: Bearer <adminToken>
```

The Android owner UI exposes the same operation as **Manage & share → Rotate invite key**.

After rotation:

- old invite links no longer authenticate,
- other maps are unaffected,
- the owner gets a new invite containing the replacement key,
- existing participant devices need the replacement invite to reconnect.

## Restores and clones

`/data/server.json` is part of the server identity. A normal backup/restore should preserve it together with maps and chunks.

If you intentionally create an independent clone, start it with a fresh data directory so it receives a new `serverId` and new secrets. Do not copy `server.json` into an unrelated public deployment unless you intentionally want it to be the same logical server identity.

## Threat boundary

A `mapKey` is a bearer secret. Anyone who obtains it can collaborate on that map until the key is rotated. Protect invite links like private collaboration links:

- send them only to intended participants,
- use HTTPS on untrusted networks,
- prefer Tailscale Serve or another authenticated/private network path,
- rotate the key when a participant should no longer have access,
- never place an `adminToken` in a participant invite.

For an internet-facing service, add deployment-specific rate limiting, reverse-proxy protections, monitoring, and access policy in addition to the application key model.
