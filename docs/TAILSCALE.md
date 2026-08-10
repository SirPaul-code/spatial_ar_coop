# Private remote access with Tailscale

The recommended public-repository deployment keeps Spatial AR Coop private at the network layer **and** at the application layer:

1. Docker binds the Spatial AR server to `127.0.0.1:8080` on the host.
2. Tailscale connects the host to a private tailnet.
3. `tailscale serve` publishes the local server to the tailnet over HTTPS.
4. Spatial AR's own `serverId`, owner `adminToken`, and per-map `mapKey` still control application access.

This is deliberately different from a central Spatial AR directory. The application never scans Tailscale or the internet for servers.

## 1. Start Spatial AR on localhost

From the repository root:

```bash
cp .env.example .env
docker compose up -d --build
curl http://127.0.0.1:8080/healthz
```

The default Compose binding is localhost-only. Do not change `SPATIAL_BIND_IP` to `0.0.0.0` for the Tailscale Serve setup.

Read the generated Spatial AR server identity and owner token:

```bash
docker compose exec spatial-server npm run identity
```

Keep the `sar_admin_...` token private.

## 2. Install Tailscale on the server host

On a supported Linux server, Tailscale's documented install path is:

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

Complete the authentication URL printed by `tailscale up`.

Check that the host is connected:

```bash
tailscale status
tailscale ip -4
```

For an unattended server, configure the host according to Tailscale's server/unattended guidance for your operating system and tailnet policy.

## 3. Publish the localhost service to your tailnet

Run:

```bash
sudo tailscale serve --bg 8080
```

Then inspect the active Serve configuration:

```bash
tailscale serve status
```

Tailscale will report the private HTTPS endpoint for this machine, normally a fully-qualified `*.ts.net` name. `tailscale serve` is tailnet-only. Do **not** substitute `tailscale funnel` unless you intentionally want public-internet exposure.

Test the HTTPS endpoint from another device in the same tailnet:

```bash
curl https://your-machine.your-tailnet.ts.net/healthz
curl https://your-machine.your-tailnet.ts.net/api/v1/info
```

## 4. Put the HTTPS endpoint into Spatial AR

Edit `.env` on the server host:

```dotenv
SPATIAL_PUBLIC_URL=https://your-machine.your-tailnet.ts.net
```

Restart the container so newly generated map invites contain the correct endpoint:

```bash
docker compose up -d
```

On an owner Android device, use the same HTTPS URL in **Server owner & diagnostics** plus the `sar_admin_...` token from `npm run identity`.

Map participants do not need the admin token. They receive a `spatialar://join?...` invite containing only:

- the private Tailscale HTTPS endpoint,
- the stable Spatial AR `serverId`,
- one `mapId`,
- that map's independent `sar_map_...` key.

The Android app verifies the endpoint's `serverId` before accepting the map.

## 5. Android phones

Install Tailscale on each Android phone and sign into a tailnet that can reach the server machine. The Spatial AR app itself does not depend on the Tailscale SDK; it simply uses the HTTPS endpoint that the Android network stack can reach while Tailscale is connected.

Use this quick connectivity check in the phone browser before debugging the AR app:

```text
https://your-machine.your-tailnet.ts.net/healthz
```

It should return a small JSON object with `"ok": true`.

## 6. Sharing the server machine with somebody outside your tailnet

Tailscale supports sharing a specific machine with another Tailscale user without exposing the whole tailnet. A recipient of a machine share gets connectivity to that shared machine, subject to tailnet policy; they do not automatically receive access to your other tailnet machines.

A clean external-collaboration flow is therefore:

1. Share only the Spatial AR server machine through the Tailscale admin console.
2. The recipient accepts the Tailscale machine share and connects their Android phone to Tailscale.
3. Separately send the Spatial AR `spatialar://join?...` invite for the one map they should use.
4. Do not send the `sar_admin_...` token.

There are two independent controls: Tailscale controls whether the network endpoint is reachable; Spatial AR's `mapKey` controls which map room/API can be used.

## Revoking access

### Revoke only one Spatial AR map

Rotate its map key from the Android owner UI (**Manage & share → Rotate invite key**) or with the admin API. Old Spatial AR invite links stop authenticating.

### Revoke Tailscale machine connectivity

Revoke the machine share/user access in the Tailscale admin console. This removes network reachability to the server from that shared user.

For a sensitive deployment, do both.

## Tailscale ACL/grant policy

For a multi-user tailnet, restrict inbound access to the Spatial AR server rather than relying on the tailnet's broad default policy. Tailscale policy is outside this repository because identities/tags differ per deployment. The server needs HTTPS reachability from the Android users who should participate; it does not need to initiate connections back to their phones.

## Troubleshooting

### `https://...ts.net` does not open

Check:

```bash
tailscale status
tailscale serve status
curl http://127.0.0.1:8080/healthz
```

If localhost works but the `ts.net` endpoint does not, the problem is in Tailscale Serve/DNS/policy rather than Spatial AR.

### Spatial AR says server identity mismatch

The invite's `serverId` does not match `/api/v1/info` on the endpoint. Do not bypass this check. It usually means the URL points to another server, a restored deployment did not restore `/data/server.json`, or an old invite contains the wrong endpoint.

### The server works but a map invite is rejected

The map key may have been rotated. Ask the owner for a newly generated invite. A valid key for another map intentionally returns an opaque `404` and cannot join the requested map room.

## Public exposure

If you deliberately expose the service outside Tailscale, put it behind a properly configured HTTPS reverse proxy, keep the admin token secret, retain per-map keys, and add the rate limiting/monitoring appropriate for an internet-facing service. The reference Compose/Tailscale configuration is intentionally private-first.
