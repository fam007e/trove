---
title: Building
---

# Building Trove

## Prerequisites

- **JDK 21 LTS** (Temurin recommended)
- **Android SDK**, compileSdk/targetSdk **35**, minSdk **26**
- `local.properties` pointing at the SDK (`sdk.dir=…`) — gitignored; without it AGP fails on license checks
- Gradle wrapper included (Gradle 9.9.0, AGP 9.5.1, Kotlin 2.4.21 — pinned in `gradle/libs.versions.toml`)

## Common commands

```bash
./gradlew test                 # all unit tests across modules
./gradlew testDebugUnitTest    # debug-variant unit tests only
./gradlew assembleDebug        # dev APK
./gradlew assembleRelease      # unsigned release APK (signed only if signing/keystore.properties exists)
```

## Version stamping

Releases are stamped from the git tag by two Gradle properties:

| Property | Source | Example |
| --- | --- | --- |
| `trove.versionName` | tag `vYYYY.MM.DD` verbatim | `2026.11.21` |
| `trove.versionCode` | tag digits `YYYYMMDD` | `20261121` |

Rules:

- **Monotonic** `versionCode` for F-Droid/Play; the `YYYYMMDD` encoding fits int32 until year 9999.
- **Never cut two releases on the same calendar day** — same-day tags collide on `versionCode`.
- Builds **without** the properties fall back to deterministic dev defaults: `versionName=0.0.0-dev`, `versionCode=1`. This keeps tests, CI audits and reproducibility scripts buildable without stamps. Dev builds therefore never shadow a real release install — pass the properties explicitly when installing a dev build over a release.

```bash
# Device-ready dev build (installable over current installs):
./gradlew assembleRelease \
  -Ptrove.versionCode=20261121 \
  -Ptrove.versionName=0.0.0-dev
```

Release tooling (CI, `scripts/ci_reproducibility_test.sh`) inherits the same contract; release tags are stamped by `.github/workflows/release.yml`.

## Reproducible builds

Trove targets the F-Droid RBU (Reproducible Build) standard — two independent builds must produce byte-identical APKs:

- Immutable dependency catalog (`gradle/libs.versions.toml`) — no dynamic versions.
- **`stable_ids.txt`** fed to AAPT2 `--stable-ids` so resource IDs survive clean rebuilds.
- Build scripts use `--no-build-cache` for audit passes.
- `SOURCE_DATE_EPOCH` is derived from the latest git commit in repro scripts.
- Release metadata (`metadata/com.trove.fdroid.yml`) documents the F-Droid build recipe, including the minification-off prebuild used for their toolchain.

Verification scripts in `scripts/`:

| Script | Purpose |
| --- | --- |
| `verify_repro.sh A B` | Compares two APKs: unzips both, strips `META-INF/` (signatures differ by design), hash-lists every remaining file, diffs |
| `ci_reproducibility_test.sh` | Dual-pass runner: `clean assembleRelease --no-build-cache --no-daemon` twice, then `verify_repro.sh` |

```bash
bash scripts/ci_reproducibility_test.sh   # full local audit (~2× release build)
```

## Signing layout

`signing/keystore.properties` (gitignored) configures the release signing config consumed by `app/build.gradle.kts`. The keystore itself lives outside the repo — see [Releases](releases.md) for the identity chain and the one script allowed to touch it (`scripts/sign-release.sh`).

## Known toolchain gotchas

- Running Gradle **without** the version properties used to fail (`versionCode=0` config error) — fixed by the dev fallback above; if you see it, you are on a tree older than this fix.
- R8 full mode can strip interface casts across feature modules — concrete-class casts are used for cross-fragment calls (`openTabFromHome`); prefer that pattern when adding hosts.
- AndroidX startup/WorkManager/Room classes need explicit keep rules (already in `proguard-rules.pro`) — new Jetpack libraries may need the same.
