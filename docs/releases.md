---
title: Releases
---

# Release pipeline

The keystore and passphrase **never** enter the repo, CI, secrets, or any artifact. CI builds are unsigned by construction and verified unsigned by assertion; signing happens in exactly one local script.

## Flow

```
tag push vYYYY.MM.DD
        │
        ▼
release.yml (CI) ─── validates tag format ─── stamps versionName/versionCode
        │            builds release APK (--no-daemon)
        │            asserts APK is UNSIGNED (hard-fail otherwise)
        │            uploads artifact trove-<tag>-unsigned + sha256
        ▼
maintainer machine
        │  gh run download <run-id> -n trove-<tag>-unsigned
        ▼
scripts/sign-release.sh app-release.apk <tag>
        │  1. apksigner v1+v2+v3 with the release key
        │  2. verifies signature + cert fingerprint against
        │     signing/RELEASE_SIGNING.md (guards against a wrong/tampered keystore)
        │  3. emits SHA256SUMS + APK .gpgsig + checksums .gpgsig
        │  4. gh release create/publish with all artifacts attached
        ▼
GitHub Release published
```

## Tag contract

- Format **`vYYYY.MM.DD`** — enforced by regex in `release.yml`; anything else fails the run.
- `versionCode = YYYYMMDD` (monotonic, int32-safe until year 9999). **Never two tags on the same calendar day.**
- `UpdateCheckMode: Tags` in the F-Droid metadata picks releases up automatically.

## Reproducibility cross-check

Rebuild the same tag locally (`scripts/ci_reproducibility_test.sh`) and compare against the CI artifact with `scripts/verify_repro.sh` — strip `META-INF/` zip entries before diffing (signatures differ by design; everything else must match bit-for-bit).

## First-release checklist

1. `gh repo create trove --public --source=. --push` (repository has no remote until this).
2. Verify Actions run on `main` is green (see [workflows](workflows.md)).
3. Tag the first release: `git tag vYYYY.MM.DD && git push origin vYYYY.MM.DD`.
4. Download the unsigned artifact, run `scripts/sign-release.sh`, confirm the published release carries APK + `SHA256SUMS` + `.gpgsig` files.
5. Add the first `Builds:` entry to `metadata/com.trove.fdroid.yml` matching the tag exactly (template is inline in that file).
6. Repo Settings → Pages → **Build and deployment source: GitHub Actions** — the [pages workflow](workflows.md) then publishes the docs on the next `main` push (or run it once manually via *Run workflow*).

## Identity chain

The signing directory documents how the APK certificate is bound to the maintainer's GPG identity (`signing/RELEASE_SIGNING.md`, tracked `.gpgsig` countersignatures for the cert, keystore properties template and the script itself). The certificate fingerprint recorded there is the anchor `sign-release.sh` checks against.
