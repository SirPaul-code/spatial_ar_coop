# ShowMe Internet service

A single Cloudflare Worker hosts the browser and creates private call invitations. SQLite Durable Objects coordinate owner approval and WebRTC signaling. **Video, audio, frozen images and drawing commands do not stream through the Worker.** WebRTC attempts a direct connection and uses Cloudflare Realtime TURN when necessary.

## Deploy from Windows, macOS or Linux

Requirements: a Cloudflare account, Node.js 22 or newer, and the ShowMe `0.3.0-internet` Android APK. You do not need a domain, virtual server, database subscription, Vercel project or public IP on your home router.

1. Create/sign in to https://dash.cloudflare.com/ . Keep **Workers Free**; this repository uses SQLite Durable Objects supported on that plan.
2. Open **Realtime > TURN**, create a TURN key, and keep its **Key ID** and **API token** private. Use TURN, not RealtimeKit or an SFU application. Reference: https://developers.cloudflare.com/realtime/turn/generate-credentials/ . Dashboard labels can change; the linked instructions lead to the current TURN dashboard.
3. Get this branch or extract `ShowMe-server.zip` from the release. Keep its `showme/server` and `showme/web` folders together.
4. Open a terminal in `showme/server` and run:

   ```sh
   npm install
   npx wrangler login
   npm run deploy
   ```

   The login command opens your browser. Allow access to **your own account**. The deploy prints a real HTTPS address such as `https://showme-calls.YOUR-SUBDOMAIN.workers.dev`. That is a placeholder example here, not an already provisioned service.

5. Configure the three private server values:

   ```sh
   npm run configure
   ```

   Paste the deployed HTTPS origin, TURN Key ID and TURN API token when asked. The script stores them using `wrangler secret put`; it also creates a random `ROOM_CREATE_KEY` and checks `/api/health`. None of those values are added to Git, the APK or browser JavaScript. Use a private terminal: do not record credential entry for your presentation.

6. The script prints your **private activation link**. Open it on the Android camera phone, tap **Open ShowMe**, and confirm the service address. Alternatively paste the whole link into ShowMe > Settings > Activate Internet calls. This is a one-time setup for the camera owner, not something helpers must do.
7. Tap **Start a call** in ShowMe. Share its new invitation via Android's share sheet. The helper opens that invitation on a PC or phone. Tap **Allow** on the camera phone to approve them. Two-way microphone access uses the normal browser permission prompt on trusted HTTPS.
8. Test with the camera phone on mobile data and the PC on Wi-Fi, then swap networks. That tests real Internet NAT traversal rather than the easier same-LAN case. The browser shows Direct or Relay and measured FPS. Keep ShowMe in the foreground.

## Deployment and cost controls

No service has been deployed into your account by the agent. These commands create it when you run them. The deployment does not select a paid Workers plan, purchase a domain or create a paid VM.

Official pricing checked **2026-09-10**:

- Workers Free has 100,000 requests/day. SQLite Durable Objects are supported on Workers Free, with 100,000 requests/day and 13,000 GB-seconds/day included. Exceeding a Free-plan allowance fails the affected operation rather than silently enabling a Paid plan.
- Cloudflare Realtime has **1,000 GB/month included**, shared between SFU and TURN, then **USD 0.05/GB** egress. This implementation uses TURN only, not the SFU. Direct WebRTC sessions do not relay their media through TURN.
- Pricing and quotas are provider terms, not promises made by this repository. Check the account billing screen and current linked documentation before enabling any paid plan. TURN bandwidth above its free allowance may incur charges; Workers Free does **not** impose a hard cap on TURN usage.

References:
https://developers.cloudflare.com/workers/platform/pricing/
https://developers.cloudflare.com/durable-objects/platform/pricing/
https://developers.cloudflare.com/realtime/
https://developers.cloudflare.com/realtime/turn/faq/

Rough planning example: a one-way 4 Mbit/s camera payload is 1.8 GB/hour, before audio and protocol overhead. It is not a metered billing prediction: relay topology, overhead and adaptation affect actual usage. Monitor **Realtime TURN usage**, not just Worker requests.

Default application limits in `wrangler.jsonc` are **20 created rooms/day** and **30 minutes/room**. Room creation requires the private activation key; helpers need an unguessable guest invitation plus owner approval. Temporary TURN credentials expire, and a closed room cannot issue more. These controls reduce accidental abuse but are not a provider-level monetary spending cap. Treat the activation key as a secret. For a public product, replace it with authenticated accounts and entitlements, add provider usage monitoring/alerts and explicit billing controls.

## Everyday behavior

- Main UI: Start a call -> Invite -> Allow helper. No certificate imports, IP selection or administration during normal Internet calls.
- Each call gets separate random owner/guest capabilities. A helper cannot end the server room or authorize another helper.
- The owner is reached through an outbound WSS connection; no router port forwarding is required.
- ICE supports UDP, TCP and TLS/443 TURN fallback. Unknown/missing TURN configuration is an explicit error, never an unannounced claim that Internet connectivity is guaranteed.
- Freeze and drawings use a bounded, ordered WebRTC data channel. Images are split into small chunks so signaling is not a video relay and SCTP messages stay bounded.
- Call end revokes the room/invitation; issued TURN credentials have their own short expiration and may remain usable until then. Never hand long-lived TURN keys to clients.
- Camera/annotation frames stay in bounded memory on the phone. This service does not save call video or instructional sessions.

## Updates and troubleshooting

`npm run deploy` updates code and static assets. Existing secrets remain. Active signaling sockets can reconnect during deployment, but schedule upgrades outside presentations.

`npm run configure` **rotates the owner activation key**. Reactivate camera phones using the new link; do not use it for a routine code update.

- **Activation required:** copy the complete setup link, including `#key=...`.
- **Relay not configured/unavailable:** verify the TURN Key ID/API token belong together, not a generic account ID/token. Re-run configure only when intentionally replacing those secrets.
- **Waiting for owner:** keep ShowMe open, check the approval dialog and network.
- **No reliable surface:** scan from a slightly different position, avoid glare/blank surfaces, then draw a smaller shape. Missing depth is not replaced with an invented distance.
- **Stuttering:** inspect native More > Connection details for measured frame/depth/luma-copy p95 times. Compare with browser FPS/jitter/RTT. A 30-fps target is not a hardware measurement. Low light, thermal/battery limits and network loss remain possible causes.
- **Local mode:** available under Settings > Use local Wi-Fi. Browser microphone capture is restricted on ordinary HTTP; use Internet mode for normal HTTPS audio.

## Tests

```sh
npm test
npm run build
npm run test:integration
npm run check
```

The integration suite runs the actual Worker/SQLite Durable Object in Miniflare, including approval, role isolation, SDP forwarding, quota and revocation. It does not use real TURN credentials or prove a successful call across arbitrary carrier NATs. Android EGL, actual radio performance, real microphone routing and physical surface accuracy still require device testing.
