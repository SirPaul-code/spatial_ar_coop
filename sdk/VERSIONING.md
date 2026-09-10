# StableAR versioning and ABI policy

StableAR has two independent version signals:

- product/package version, currently `0.2.0-research` / `0.2.0-preview.*`;
- native C ABI version returned by `stablear_abi_version()` (currently 1).

During `0.x`, source APIs may evolve, but released binary artifacts must keep their published C ABI compatible within an ABI version. Additive functions are preferred over changing existing structs or signatures. If a shipped struct layout/signature becomes incompatible, increment the ABI version and keep a compatibility shim when practical.

Language wrappers must call the C ABI rather than duplicating solver logic. C++ public APIs are convenient source APIs and are not promised to have the same binary compatibility guarantees as the C ABI.

Every distributed binary should be traceable to a Git commit and accompanied by version/ABI metadata and SHA-256 hashes.
