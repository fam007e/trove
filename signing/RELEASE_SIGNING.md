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
| Keystore | PKCS12, `~/.keystores/trove/trove-release.p12` (private, NOT in this repo; migrated from JKS 2026-11-18, same certificate) |
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
### 4. Release evidence

| Artifact | SHA-256 |
|---|---|
| `app-release.apk` (v1.0.0, versionCode 100, 2026-11-18, local build) | `74b67e1a3c0484a3f150f24568797d01f0898f587c7dee873ac90b27843c20c8` |
| `Trove-v2026.11.21.apk` (v2026.11.21, versionCode 20261121, 2026-11-21, first CI tag release) | `d48411662e2167ac3f945a9d9985806933b353654d1cec4d4ac30888f040a273` |
| `trove-release.crt` | `cfbbcd6b7a74a7b1047c2fdab51c5c948b366be66504b9b716c7337c2fc87587` |

Both the APK and this certificate carry GPG detached signatures in this
directory (`*.gpgsig`), made by the GPG key above. (v1.0.0; from
v2026.11.21 on, each release's APK + SHA256SUMS GPG signatures are
attached to its GitHub release instead.)

- Container migrated JKS -> PKCS12 on 2026-11-18 (keytool deprecates JKS algorithms);
  certificate and fingerprint are byte-identical, so update compatibility is unaffected.
  The original JKS is kept as a backup in the private keystore dir.

## Public GitHub release flow (keystore never leaves this machine)

1. Push a tag `vYYYY.MM.DD` (e.g. `v2026.11.18`) -> `.github/workflows/release.yml` builds a reproducible
   UNSIGNED APK and uploads it as a workflow artifact (CI refuses signed APKs).
2. Download the artifact locally, then run in YOUR terminal:
   `scripts/sign-release.sh app-release.apk vYYYY.MM.DD`
   The script signs (v1+v2+v3), verifies the cert fingerprint against the value
   recorded in this file, produces SHA256SUMS + GPG detached signatures, and
   publishes the GitHub release via `gh` when a tag is passed. GPG steps run in
   your terminal so pinentry can prompt for the GPG passphrase — never paste the
   passphrase into CI, chat, or shell history.
3. Users verify: `sha256sum -c SHA256SUMS` and `gpg --verify <apk>.gpgsig <apk>`.

Version identity: CI stamps versionName=<tag> (verbatim, e.g. `v2026.11.18`) and
versionCode=<YYYYMMDD> (e.g. 20261118) from the tag — tag, in-app version, and
store metadata are always the same identifier. versionCode is monotonic for
F-Droid/Play; never cut two releases on the same calendar day (code collision).

Threat model notes:
- CI never sees key material; signing is artifact-only from a trusted local machine.
- sign-release.sh fails loudly if the keystore is wrong/replaced (fingerprint check).
- Loss of the keystore = permanent update breakage: back it up offline (encrypted)
  and keep the passphrase in a password manager, never in the repo or shell history.
