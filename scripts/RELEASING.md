# Release checklist

1. Update `VERSION` using a numeric `major.minor.patch` version.
2. Run frontend build, `./gradlew test bootJar`, and `python3 scripts/test-bootstrap.py` after packaging.
3. Build with `python3 scripts/package-macos.py` and sign with `python3 scripts/prepare-release.py`.
4. Publish the matching PKG, JAR, `update.properties`, `update.sig`, and `SHA256SUMS` together under tag `v<version>`.
5. Never replace published version assets; issue a higher version for fixes.

`.release-keys/private.der` is a release-only Ed25519 private key. Never commit it, package it, or send it to issue reports. The public key in `src/main/resources/update-public-key.der` pins the update publisher. Back up the private key privately. Key rotation requires a separately distributed installer unless a future trusted key-rotation protocol is added.

The GitHub Actions release workflow needs the `UPDATE_SIGNING_KEY` encrypted repository secret (base64 PKCS#8 DER). Setting this secret requires the owner's explicit approval. Without it, publish locally signed releases; installed apps can still download and apply them automatically.

Automated application updates intentionally preserve the launcher and bundled Java. Increase the update protocol when a new bootstrap/runtime is required, and provide a new installer.
