---
title: CI workflows
---

# CI workflows

All pipelines live in `.github/workflows/`. They are designed around one rule: **the keystore never touches CI** — CI artifacts are unsigned by construction and verified unsigned by assertion.

## `ci.yml` — Trove CI & Reproducibility Pipeline

Trigger: push/PR to `main` (concurrency-canceled per ref).

| Job | What it does | Gate |
| --- | --- | --- |
| **Unit, CVE & Fuzz Tests** | `./gradlew test --continue` across all modules; JUnit reports uploaded as artifacts | test failures fail the run |
| **F-Droid Reproducible Build Audit** | Validates `metadata/com.trove.fdroid.yml` structure; runs the dual-pass audit (`scripts/ci_reproducibility_test.sh`): two independent `clean assembleRelease --no-build-cache` builds must hash-match (META-INF stripped) | any byte difference fails the run |
| **Dependency & CVE Audit** | `dependency-review-action` on PRs (fail ≥ high) + Trivy filesystem scan (CRITICAL/HIGH, ignore-unfixed) | vulnerabilities fail the run |
| **Assemble Release APK** | Snapshot build stamped `versionName=0.0.0-<short-sha>`, `versionCode=<run-number>`; unsigned artifact `trove-release-apk` | runs after tests + repro audit pass |

Note: builds without tag properties use the deterministic dev fallback (`0.0.0-dev` / versionCode 1) — see [Building → Version stamping](building.md#version-stamping).

## `release.yml` — Release build

Trigger: push of a `v*` tag.

1. Enforces the **`vYYYY.MM.DD`** tag format (regex; hard-fail otherwise).
2. Stamps `versionName=<tag>`, `versionCode=<YYYYMMDD>` and builds `assembleRelease --no-daemon`.
3. **Asserts the APK is unsigned** with `apksigner verify` — a signed artifact fails the build (the signing config auto-activates only when `signing/keystore.properties` exists, which never happens on CI).
4. Uploads `trove-<tag>-unsigned` + SHA-256.

The maintainer then signs locally and publishes — the full chain is in [Releases](releases.md).

## `pages.yml` — Documentation site (GitHub Pages)

Trigger: push to `main` touching `docs/**`, `README.md`, or the workflow itself; manual `workflow_dispatch`.

- Builds `docs/` as a Jekyll site (`actions/jekyll-build-pages`, cayman theme).
- Deploys via the official Pages actions (`configure-pages` → `jekyll-build-pages` → `upload-pages-artifact` → `deploy-pages`) with the `pages: write` / `id-token: write` permissions and a `github-pages` concurrency group.

**One-time repo setting:** Settings → Pages → Build and deployment → **Source: GitHub Actions**. After the first deployment the site is available at `https://fam007e.github.io/trove/` (owner/repo: fam007e/trove).

## `discord-webhook.yml` — Discord notifications

Trigger: pushes, PR events, issues, releases, check runs, deployments. Formats a short embed and posts to the webhook URL from the **`DISCORD_WEBHOOK_URL`** secret (repo Settings → Secrets → Actions). Mentions are disabled (`allowed_mentions.parse = []`); if the secret is unset the step skips with a warning instead of failing — safe on forks.

## Maintenance notes

- Runner images: `ubuntu-latest`, JDK 21 (Temurin) via `actions/setup-java`, Gradle caching via `gradle/actions/setup-gradle`.
- The repro audit builds the release APK twice — expect the pipeline to take roughly 3× a plain test run.
- If a new Jetpack library appears in dependencies, check `proguard-rules.pro` keep rules (startup/WorkManager/Room needed them under R8 full mode).
