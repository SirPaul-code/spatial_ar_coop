# Spatial AR Coop server

Node.js 22 REST/WebSocket relay with local JSON persistence, scan-chunk storage, track expiry,
structured JSONL logging, and a diagnostics dashboard.

```bash
npm install
DEMO_API_TOKEN=change-me npm start
```

Open `http://localhost:8080/`. Persistent state is stored under `DATA_DIR` (default `server/data`).
