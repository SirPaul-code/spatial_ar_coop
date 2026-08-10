# Security policy

## Secrets that must not be committed

Do not commit:

- Google/ARCore API keys,
- Spatial AR `sar_admin_...` owner tokens,
- Spatial AR `sar_map_...` map keys or private invite links,
- Android signing keystores or signing passwords,
- private scan/map exports,
- diagnostics/log bundles that contain sensitive deployment details.

Configure Android build values through `android/local.properties`, environment variables, or CI secrets. Keep release signing material outside the repository. The reference server persists generated credentials in `/data/server.json`; protect and back up that file with the rest of `/data`.

## Spatial AR authorization model

Each self-hosted server has:

- a stable non-secret `serverId`,
- one secret owner `adminToken`,
- an independent secret `mapKey` for each map.

The admin token can list/create/delete maps, read server diagnostics and rotate map keys. A participant should never need the admin token.

A map key authorizes only its map's REST and WebSocket room. It cannot list the server's other maps. Map-specific requests with an invalid key return an opaque `404` rather than confirming whether the requested private map exists.

Map invites are bearer credentials. Anyone who obtains an invite can use that map until the owner rotates its key. See `docs/SHARING_AND_IDENTITY.md`.

## Network exposure

The default Docker Compose configuration binds the service to `127.0.0.1`. For private remote access, the recommended deployment is Tailscale Serve HTTPS; see `docs/TAILSCALE.md`.

If you intentionally expose the server to the public internet:

- terminate TLS with a maintained reverse proxy or equivalent service,
- keep the admin token out of browser history, URLs and logs,
- retain per-map keys rather than replacing them with a shared global password,
- restrict ingress where possible,
- add deployment-appropriate rate limiting and abuse controls,
- monitor authentication failures and resource use,
- keep Node/container dependencies updated,
- maintain tested backups of `/data`.

The built-in key model is an application authorization boundary; it is not a substitute for operating-system, container-host, reverse-proxy or tailnet security.

## Sensitive spatial data

Scan chunks, anchors, map metadata, poses and object tracks can disclose the layout or activity of a physical site. Treat `/data`, Android local databases, exported diagnostics and invite links as sensitive data. Camera inference is performed on device; the relay does not require a camera-video stream.

## Dependency/security updates

Server dependencies are intentionally small and pinned. CI runs the server test/syntax suite, container build and package audit. Android dependencies and GitHub Actions are tracked separately through the repository update workflow/Dependabot configuration.

## Reporting a vulnerability

Report vulnerabilities privately through GitHub Security Advisories rather than a public issue. Include the affected version/commit, reproduction steps, impact and any relevant logs with secrets redacted.
