# ShowMe runtime acceptance

Production startup contract for the Android camera owner:

- Opening ShowMe does not start the camera.
- Tapping **Start a call** starts ARCore and immediately reveals the live local camera preview.
- Cloud room creation happens while that preview remains visible; signaling latency must never blank, minimize, or terminate the activity.
- No activation link, service URL, Cloudflare key, or manual pairing is exposed to the app user.
- The shared-EGL/WebRTC video encoder is not created until an approved helper is actually present.
- If room creation fails, the process stays alive and returns to the start UI with an actionable error.
- Invite QR rendering is optional: a QR failure must not prevent Share/Copy or terminate the call.
- Invite and approval dialogs are shown only while the Activity is in the foreground; otherwise they are deferred until resume.
- Existing Spatial Sync runtime remains untouched.

Physical acceptance after every release: fresh install, grant camera/microphone, Start a call, verify immediate preview, obtain HTTPS invite, open it from a different network, approve helper, verify WebRTC video/audio and one anchored annotation.
