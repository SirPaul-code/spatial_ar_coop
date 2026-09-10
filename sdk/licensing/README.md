# StableAR licensing / entitlements

StableAR licensing has two separate layers: the commercial agreement grants rights; the signed entitlement is only technical enforcement. Client-side enforcement can always be patched by a sufficiently motivated customer, so do not treat obfuscation as the legal license.

## STABLEAR1 lease

Format: `STABLEAR1.<base64url canonical claims>.<base64url ECDSA-P256/SHA-256 DER signature>`.

Canonical claim order issued by the reference tool:

- `product_id`
- `customer_id`
- `app_id`
- `platform`
- `features` (comma-separated)
- `nbf` Unix seconds
- `exp` Unix seconds
- `grace` offline seconds, max 31 days

The native parser rejects unknown/duplicate/missing claims, malformed time ranges, wrong product/platform/app/feature and expired leases. The private P-256 signing key never belongs in an SDK, app, CI artifact or customer repo. Production issuance should use a KMS/HSM or isolated license service.

Recommended `app_id` bindings:
- Android: `<package>:<sha256-signing-certificate>`; `StableArEntitlement.androidAppBinding()` computes this.
- iOS: bundle ID at minimum; enterprise customers can additionally bind customer/account identifiers in their contract/license service.
- Unity/OpenXR: application/package identity plus platform entitlement chosen by the wrapper.

Refresh a lease on app startup/periodically when online; tracking itself never phones home and an already-valid lease works offline through its explicit grace. Do not perform network licensing checks in the render loop.

## Commercial packaging suggestion

A practical first offer is per-app/per-platform annual licensing with a free evaluation key and an enterprise tier for source access/custom SLAs. Avoid pricing per tracked point/frame: it makes offline XR integration painful. The entitlement can enable features such as `tracking`, `vision`, `persistence`, or future `dynamic` support.

The scripts require Python `cryptography`. `generate_dev_key.py` is for local development only. `issue_entitlement.py` can issue test leases using a PEM private key. No key is included in this repository.

### Clock rollback / replay

The reference parser checks a caller-supplied Unix time. A commercial host should persist a signed/secure high-water timestamp (Keychain/Keystore-backed where practical) and use `max(systemTime, trustedHighWater)` for entitlement evaluation. Refreshing a lease from the issuer should advance that high-water mark. This reduces accidental clock rollback abuse but is not tamper-proof on a fully controlled client device.

Never use the entitlement result as a substitute for server-side authorization of server resources. It only gates local SDK features.
