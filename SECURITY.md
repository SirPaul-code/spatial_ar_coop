# Security policy

Do not commit API keys, bearer tokens, signing keys, private map data, video, or exported diagnostics. Configure Android values through `android/local.properties` or CI secrets, and server tokens through environment variables.

The demo server supports bearer-token authentication but is intentionally small. For any internet-facing deployment, place it behind TLS, use independent random `DEMO_API_TOKEN` and `ADMIN_TOKEN` values, restrict ingress, back up `/data`, and monitor logs. Scan chunks contain spatial coordinates and should be treated as sensitive site data.

Report vulnerabilities privately through GitHub Security Advisories rather than a public issue.
