# StableAR licensing / entitlement engineering

The legal SDK licence and runtime entitlement are separate concerns. `STABLEAR1` is an offline-first technical entitlement format, not the contractual grant of rights.

Token form:

`STABLEAR1.<base64url canonical claims>.<ECDSA-P256/SHA-256 DER signature>`

Required canonical claims are `product_id`, `customer_id`, `app_id`, `platform`, `features`, `nbf`, `exp`, `grace`. The issuer emits one claim per line in that order. Runtime parsing rejects unknown/duplicate/missing claims and binds the token to the expected product/platform/application/feature.

The private issuer key must never be committed, embedded in an AAR/XCFramework/Unity package or delivered to customers. Development scripts generate ephemeral local PEM files; production should sign in KMS/HSM or an isolated entitlement service. Tracking does not phone home; lease refresh is an application-level operation outside the frame/render path.

Android should bind `app_id` to package + signing-certificate SHA-256. iOS should at minimum bind to the bundle/product identity used by the commercial agreement. Because all client-side checks can ultimately be patched, contract terms and controlled SDK distribution remain necessary.
