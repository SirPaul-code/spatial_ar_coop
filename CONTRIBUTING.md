# Contributing

1. Create a focused branch and keep changes small enough to review.
2. Run `make server-test` and `make android-test` before opening a pull request.
3. Never add credentials, private map captures, APK signing material, or user video.
4. Document protocol changes in `docs/protocol.md` and preserve backwards compatibility where practical.
5. Add tests for transform math, persistence, retry behavior, and protocol validation.

The Android app targets native Kotlin/ARCore. Avoid adding Unity or a large rendering engine unless a concrete requirement justifies the cost.
