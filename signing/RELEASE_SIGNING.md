# Trove — Release Signing Identity Chain

Created: 2026-11-18

This document binds Trove's APK signing certificate to its author's GPG identity.
Anyone can verify the chain without trusting this file alone (see Verification).

## Identity

| Artifact | Value |
|---|---|
| GPG key (author) | `Faisal Ahmed Moshiur <faisalmoshiur+gpg@gmail.com>` |
| GPG fingerprint (RSA4096, SC) | `308DFB7C 06580B2D 3762E298 CE252206 F6BFBB77` |
| GPG fingerprint (compact) | `308DFB7C06580B2D3762E298CE252206F6BFBB77` |
| APK signing cert | `trove-release.crt` (this directory, GPG-signed) |
| Cert subject | `CN=Faisal Ahmed Moshiur, OU=GPG:308DFB7C06580B2D3762E298CE252206F6BFBB77, O=Trove Project, C=BD` |
| Cert SHA-256 | `CF:BB:CD:6B:7A:74:A7:B1:04:7C:2F:DA:B5:1C:5C:94:8B:36:6B:E6:65:04:B9:B7:16:C7:33:7C:2F:C8:75:87` |
| Cert validity | 2026-11-18 → 2056-12-30 (30 years, F-Droid compatible) |
| Key algorithm | RSA 4096 |
| Keystore | JKS, `~/.keystores/trove/trove-release.jks` (private, NOT in this repo) |
| Keystore alias | `trove-release` |

The OU field of the certificate deliberately embeds the author's GPG fingerprint,
so the binding survives even without this document: any APK signed by this key
carries the GPG fingerprint in its certificate subject.

## Verification

### 1. Verify the certificate against the APK

```bash
apksigner verify --print-certs path/to/trove-release.apk
# Signer #1 certificate SHA-256 digest must be:
#   cfbbcd6b7a74a7b1047c2fdab51c5c948b366be66504b9b716c7337c2fc87587
```

### 2. Verify the certificate was endorsed by the GPG key

```bash
gpg --import signing/trove-release.crt          # imports as a "normal" cert
gpg --verify signing/trove-release.crt.gpgsig signing/trove-release.crt
# Must show: Good signature from "Faisal Ahmed Moshiur ... <19180457+fam007e@users.noreply.github.com>"
# and the fingerprint 308DFB7C06580B2D3762E298CE252206F6BFBB77.
```

### 3. Full chain

APK signature matches cert `cfbbcd...7587` → cert is GPG-signed by
`308DFB7C...FBB77` → GPG key is the author's published identity.

## Operational notes

- Keystore + passphrase live at `~/.keystores/trove/` (0700 dir, 0600 passphrase file).
  **Move the passphrase into a password manager and back up the keystore separately —
  losing this keystore means Trove updates can never be installed over existing installs
  (Android enforces same-signature updates).**
- `signing/keystore.properties` (gitignored) points the Gradle build at the keystore.
- Every release APK and this directory's artifacts are GPG-detach-signed by the key above.
### 4. Release evidence (v1.0.0, build 100, 2026-11-18)

| Artifact | SHA-256 |
|---|---|
| `app-release.apk` (v1.0.0, versionCode 100) | `74b67e1a3c0484a3f150f24568797d01f0898f587c7dee873ac90b27843c20c8` |
| `trove-release.crt` | `cfbbcd6b7a74a7b1047c2fdab51c5c948b366be66504b9b716c7337c2fc87587` |

Both the APK and this certificate carry GPG detached signatures in this
directory (`*.gpgsig`), made by the GPG key above.

- JKS was requested explicitly; `keytool` warns JKS is deprecated. Migration to PKCS12
  (identical keys, new container) is one command and can be done any time without
  changing the certificate:
  `keytool -importkeystore -srckeystore trove-release.jks -destkeystore trove-release.jks -deststoretype pkcs12`
