# Self-hosting Spatial AR Coop

Spatial AR Coop has no central server registry. Every deployment is independent and has its own stable `serverId`, admin credential, maps, scan chunks and live WebSocket rooms.

## Requirements

- Docker Engine with Docker Compose v2, or Node.js 22+
- Persistent storage for `/data`
- For remote/private access: Tailscale is recommended (see `docs/TAILSCALE.md`)
- For Cloud Anchors in the Android app: an ARCore API key configured at build time

## Docker quick start

```bash
git clone https://github.com/SirPaul-code/spatial_ar_coop.git
cd spatial_ar_coop
cp .env.example .env
docker compose up -d --build
```

The default Compose configuration binds the server to `127.0.0.1:8080`, not to every LAN interface. Verify it:

```bash
curl http://127.0.0.1:8080/healthz
curl http://127.0.0.1:8080/api/v1/info
```

On the first start, if `ADMIN_TOKEN` and `SPATIAL_SERVER_ID` are empty, the server creates cryptographically random values in the persistent `/data/server.json` identity file. Read the identity as the server owner:

```bash
docker compose exec spatial-server npm run identity
```

Example shape:

```json
{
  "serverId": "srv_...",
  "serverName": "Spatial AR Server",
  "protocolVersion": 1,
  "auth": "admin-plus-map-key",
  "adminToken": "sar_admin_...",
  "dataDir": "/data"
}
```

Treat `adminToken` as a password. It can list/create/delete every map on that server.

## Access model

There are three identifiers/credentials with deliberately different roles:

- `serverId` — stable public identity of one self-hosted server. It is safe to display and is returned by `/api/v1/info`.
- `adminToken` — server-owner secret. It can list/create/delete maps, read logs/metrics and rotate map keys.
- `mapKey` — random secret for exactly one map. It can read/update that map, upload scan chunks, resolve its live state and connect to its WebSocket room. A map key cannot open another map.

There is no endpoint that discovers other Spatial AR servers. An unauthenticated client cannot list maps. A user who receives one map invite does not receive the admin token and cannot list the other maps on the same server.

## Create a map through the API

The Android app normally does this for you, but the API can also be used directly:

```bash
ADMIN_TOKEN='sar_admin_...'
curl -sS \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Back garden","createdBy":"owner-phone"}' \
  http://127.0.0.1:8080/api/v1/maps
```

The response contains a random map ID, an `accessKey`, and an invite object. The `accessKey` is the map's `mapKey`.

## List maps as server owner

```bash
curl -sS \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://127.0.0.1:8080/api/v1/maps
```

Only an admin token can use this endpoint. The admin response intentionally includes map access keys so an owner can recover/share existing maps from a new device.

## Use one map as a participant

```bash
MAP_ID='map-...'
MAP_KEY='sar_map_...'
curl -sS \
  -H "Authorization: Bearer $MAP_KEY" \
  "http://127.0.0.1:8080/api/v1/maps/$MAP_ID"
```

The same map key authorizes the map's scan, point-cloud, live-state and WebSocket endpoints. It does not authorize `/api/v1/maps` or another map ID.

## Revoke a shared map invite

Rotate the map key as the server owner:

```bash
curl -sS -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://127.0.0.1:8080/api/v1/maps/$MAP_ID/rotate-key"
```

Every old invite/map key becomes invalid immediately for new HTTP/WebSocket authentication. Send the returned new invite only to the intended participants.

## Rotate the server admin token

If the token is persisted in `/data/server.json` (that is, `ADMIN_TOKEN` is not forcing a value from the environment):

```bash
docker compose exec spatial-server node src/identity-cli.mjs rotate-admin
```

If `ADMIN_TOKEN` is explicitly set in `.env`, rotate it there instead and restart the container; the configured value takes precedence and is persisted.

## Backups

All durable server state is under `/data`:

- `server.json` — stable server ID, admin token and per-map keys
- `maps/` — map metadata/anchors
- `chunks/` — compressed sparse scan chunks and metadata
- server logs (depending on logger configuration)

The Compose project stores `/data` in the named `spatial-data` volume. Back up the volume while writes are stopped or use a storage-level snapshot.

A simple portable backup:

```bash
docker compose stop spatial-server
docker run --rm \
  -v spatial_ar_coop_spatial-data:/data:ro \
  -v "$PWD":/backup \
  alpine sh -c 'cd /data && tar czf /backup/spatial-data-backup.tgz .'
docker compose start spatial-server
```

Restore into an empty volume before starting the server. Restoring `server.json` preserves the same `serverId`, admin token and map keys.

## Upgrade

```bash
git pull
docker compose build --pull
docker compose up -d
```

Then check:

```bash
docker compose ps
curl http://127.0.0.1:8080/healthz
docker compose logs --tail=100 spatial-server
```

## Direct LAN binding

Tailscale HTTPS is the recommended remote path. For an isolated/trusted LAN you can bind Docker directly by changing `.env`:

```dotenv
SPATIAL_BIND_IP=0.0.0.0
SPATIAL_PORT=8080
SPATIAL_PUBLIC_URL=http://192.168.1.50:8080
```

Then restart:

```bash
docker compose up -d
```

The Android app supports HTTP for local/self-hosted deployments, but map keys are bearer credentials. Do not send them over an untrusted network in cleartext. Use Tailscale Serve HTTPS or another TLS reverse proxy for remote/shared deployments.
