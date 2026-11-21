---
title: Roadmap
---

# Roadmap

An honest list of what is **not** done yet — kept public on purpose.

## Functionality

- **Undo** for destructive operations (currently: recycle bin + permanent-delete confirmations, no undo stack).
- **App lock** (biometric/PIN gate for the whole app; biometrics exist only for vault keys today).
- **APK uninstall** of installed apps (install-side works via the system installer; uninstall is missing).
- **Live filter** (as-you-type filtering; current filter applies on submit) and **send log** (diagnostics export).
- **MediaStoreFileItem depth**: breadcrumb navigation and back behavior inside MediaStore categories are limited (flat category lists, no path jail).
- **Settings depth**: theme/skins, per-storage view settings, server configuration pages.
- **Dual-pane polish**: pane swap, per-pane sort/view overrides.
- **Cloud OAuth client IDs**: before public release, the bundled Drive/Dropbox/OneDrive client IDs in `OAuthConfig.kt` must be replaced by the maintainer's own registered apps (they are placeholders pointing at development credentials).
- **Root tools**: chmod/chown/ownership UI, su-terminal wrapper, root bookmark of `/` (the Cleaner already runs a hardened direct-`su` path; the browse side remains Shizuku-based).
- **Cross-backend move performance**: falls back to copy+delete for backends without native rename.
- **Cleaner follow-ups**: the Deduplicator compares content + file name only — deep metadata diffs (EXIF/ID3 between byte-identical copies) are future work; no Scheduler for recurring cleans; recycle-bin routing does not apply to Cleaner deletions outside user storage. (The accessibility automation is implemented and device-verified.)

## Distribution

- First tagged release + GitHub Release with signed APK (see [Releases](releases.md) checklist).
- F-Droid: first `Builds:` entry once the first tag exists; metadata file is ready.
- GitHub Pages docs site: works after enabling Pages (Source: GitHub Actions).

## Engineering

- Instrumented (on-device) test suite for SAF/privilege flows — currently covered by unit tests + manual device verification.
- Baseline profiles for cold-start.
- Per-module kdoc pass; public APIs of `:core:fs` and `:feature:*` deserve doc comments.
