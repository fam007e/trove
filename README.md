<p align="center">
  <img src="docs/assets/logo.png" width="128" alt="Trove logo">
</p>

# Trove

[![CI](https://github.com/fam007e/trove/actions/workflows/ci.yml/badge.svg)](https://github.com/fam007e/trove/actions/workflows/ci.yml)
[![Release build](https://github.com/fam007e/trove/actions/workflows/release.yml/badge.svg)](https://github.com/fam007e/trove/actions/workflows/release.yml)
[![Docs](https://github.com/fam007e/trove/actions/workflows/pages.yml/badge.svg)](https://fam007e.github.io/trove/)

Trove is an open-source, reproducible Android file manager designed for security, privacy, and modularity. A clean-room Apache-2.0 project — no proprietary or decompiled code anywhere in the repository ([policy](docs/ui-parity.md)).

## Highlights

- One unified virtual filesystem (`FileItem`): **local · SAF · FTP/SFTP/SMB/WebDAV · cloud (Drive, Dropbox, OneDrive, MediaFire, TeraBox) · inside-archive · encrypted vault · MediaStore**
- Dual-pane browsing with per-pane tabs, thumbnails, folder sizes, recursive search, selection ops, recycle bin with restore
- Built-in engines: archive (ZIP/7Z/TAR/GZ/BZ2/XZ/RAR), image, PDF, Media3 player, universal metadata tagger
- **Storage cleaning**: App Cleaner (caches), Corpse Finder (uninstalled-app leftovers), Deduplicator (content + name matching), System Cleaner (filters) — hardened direct-`su` path for private data, accessibility automation for root-less cache clearing
- Encrypted vault: chunked **AES-256-GCM**, **Argon2id**, biometric unlock
- Embedded **FTP/WebDAV/SFTP** servers; Termux integration; Shizuku **privilege layer** (root-capable)
- **Reproducible builds** to the F-Droid RBU standard; CI builds unsigned APKs, signing never leaves the maintainer's machine

Full feature list and status: [docs/ui-parity.md](docs/ui-parity.md) · Roadmap: [docs/roadmap.md](docs/roadmap.md)

## Architecture

Trove is organized into decoupled Gradle modules operating over a unified virtual filesystem abstraction (`FileItem`):

- **`:app`**: Application entry point, lifecycle management, and manifest configuration.
- **`:ui:widgets`**: Programmatic UI controls (`TroveTabBar`, `TroveBreadcrumbBar`, `FileRowView`).
- **`:ui:browse`**: File browsing activity, directory fragments, and ViewPager2 tab synchronization.
- **`:core:fs`**: Unified virtual filesystem interface (`FileItem`, `LocalFileItem`, `ScopedFileItem`).
- **`:core:net`**: Remote protocol clients (`FTP`, `SFTP`, `SMBv2/v3`, `WebDAV`).
- **`:core:crypto`**: AES-256-GCM chunked streaming AEAD vault, Argon2id key derivation, and hardware-backed biometric authentication.
- **`:server:ftp`**, **`:server:webdav`**, **`:server:sftp`**: Embedded background file servers.
- **`:privilege`**: Root and Shizuku IPC service with caller UID validation.
- **`:feature:archive`**: Container browsing and extraction for ZIP, 7Z, TAR, GZ, BZ2, XZ, and RAR.
- **`:feature:image`**: Gallery viewer with bounds-first decoding, bitmap pooling, and EXIF orientation handling.
- **`:feature:pdf`**: Document viewer with day/night modes, text extraction, and PDFBox integration.
- **`:feature:tagger`**: Universal metadata reader/editor for audio, image, video, and ebook files.
- **`:feature:media`**: Media3 ExoPlayer engine with custom `FileItemDataSource` streaming bridge.
- **`:feature:cleaner`**: Storage cleaning engines (App Cleaner, Corpse Finder, Deduplicator, System Cleaner) with a persistent sentinel-framed `su` shell and an accessibility automation service.

Deep dive: [docs/architecture.md](docs/architecture.md).

## Building from Source

Prerequisites:
- JDK 21 LTS
- Android SDK (API Level 35, Build Tools 35.0.0)

```bash
./gradlew test              # unit tests (163 green)
./gradlew assembleDebug     # dev APK
```

Release/versioned builds are stamped from the tag scheme (`vYYYY.MM.DD` → `versionCode YYYYMMDD`); builds without the properties fall back to deterministic dev defaults:

```bash
./gradlew assembleRelease \
  -Ptrove.versionCode=20261121 \
  -Ptrove.versionName=0.0.0-dev
```

Details, gotchas and the reproducibility contract: [docs/building.md](docs/building.md).

## F-Droid Reproducible Builds

Trove adheres to F-Droid reproducible build standards:
- Immutable dependency catalog (`gradle/libs.versions.toml`).
- Stable resource IDs enabled via `stable_ids.txt`.
- Parameter assertion stripping in release builds.
- Reproducibility verification script located at `scripts/verify_repro.sh`.
- Dual-pass audit in CI: two independent release builds must hash-match (`scripts/ci_reproducibility_test.sh`).

## Releases

CI builds an **unsigned** APK on every `vYYYY.MM.DD` tag and hard-fails if the artifact is signed; signing happens only on the maintainer's machine via `scripts/sign-release.sh` (fingerprint-guarded, GPG-countersigned). Pipeline: [docs/releases.md](docs/releases.md).

## Documentation

| | |
| --- | --- |
| [Architecture](docs/architecture.md) | Modules, `FileItem` abstraction, privilege layer |
| [Building](docs/building.md) | Prerequisites, version stamping, reproducibility |
| [Releases](docs/releases.md) | Tag pipeline, local signing, first-release checklist |
| [Security model](docs/security.md) | Permissions, vault crypto, servers, audits |
| [UI parity & clean-room](docs/ui-parity.md) | What is replicated, what is never copied |
| [CI workflows](docs/workflows.md) | All GitHub Actions pipelines |
| [Roadmap](docs/roadmap.md) | What is not done yet |

The same pages are published as a website by the Pages workflow (`docs/` → GitHub Pages).

## License

Trove is licensed under the Apache License, Version 2.0. Third-party open-source components and licensing statements are detailed in `NOTICE.md`.
