---
title: Security model
---

# Security model

## Permissions

| Permission | Why |
| --- | --- |
| `MANAGE_EXTERNAL_STORAGE` | Full shared-storage access; the app routes the user through the system "All files access" panel on first run and degrades to SAF-only if declined |
| `READ/WRITE_EXTERNAL_STORAGE` (maxSdk 29) | Legacy devices only |
| `moe.shizuku.manager.permission.API_V23` | Privilege service over Shizuku |
| `QUERY_ALL_PACKAGES` | The Cleaner enumerates installed packages (App Cleaner caches, Corpse Finder's installed-set diff). Without it, API 30+ package visibility filters the list to nothing |
| `com.termux.permission.RUN_COMMAND` | **Runtime (dangerous)** — requested on first "Open in terminal"; opens a bash session at the browsed folder via Termux's official API |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Media playback service |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Network/cloud backends and embedded servers |
| `ACCESS_SUPERUSER` | Declared for the privilege layer on rooted devices (legacy superuser managers). Note: the Cleaner's direct `su` path needs no app permission — access is granted by Magisk/another su manager at runtime |

No telemetry, no analytics, no tracking SDKs.

## Privilege service

`:privilege` exposes a Shizuku user service (`daemon = false`) with a minimal AIDL surface (`stat`, `listFiles`, `openFile`, `rename`, `delete`, `mkdir`). Every binder call validates the **caller UID** and rejects anything other than Trove's own UID. Privilege scope equals the Shizuku instance: shell-UID (ADB) for most of the system, UID 0 when Shizuku is started with root. The service dies with Shizuku; no root daemon is left behind.

## Encrypted vault

`:core:crypto` implements a chunked **AES-256-GCM** streaming AEAD format (`TrveChunkedCipher/InputStream/OutputStream` + per-chunk headers), with **Argon2id** key derivation and hardware-backed **biometric** unlock. `VaultFileItem` presents decrypted content through the ordinary `FileItem` interface, so the whole UI (browse, copy, viewers) works on vault files without special cases.

## Cleaner & root shell

The Cleaner (`:feature:cleaner`) can reach private app data through a direct `su` shell. Its hardening:

- **Two paths, clearly split.** Public storage is handled with ordinary file APIs under the all-files grant. Private areas (`/data/data`, `/data/app-lib`) and FUSE-blocked backing stores (`/data/media/0`) are only touched through `RootShell`, which exists only when the user grants Trove superuser via Magisk (or another su manager).
- **Global namespace.** `su` is spawned with Magisk's `-M` flag so commands run in the device's global mount namespace; a plain-`su` fallback covers su implementations without the flag. Without `-M`, su silently inherits the app's isolated namespace and scans would see only Trove's own data dir.
- **Framed protocol.** One persistent `su` process; each command is wrapped in echo sentinels (`ShellProtocol`) so output and exit codes are matched per command and cross-talk between commands is impossible. A deadline tears the session down — this is also what re-arms a pending Magisk grant prompt instead of hanging.
- **Name validation before the shell.** Everything interpolated into a root command comes from directory listings or PackageManager and must pass `PackageNameValidator` (strict package-name grammar) and shell quoting. Fixed system paths (`/data/system/dropbox`, `/data/tombstones`, `/data/anr`) are compile-time constants, and their contents must match a conservative filename grammar before deletion is offered.
- **Preview-first.** Nothing is deleted from a scan directly: results are listed, the user selects, and an explicit confirmation dialog with a path preview is required. Corpse deletion **does not** go through the recycle bin — `/data` has no trash semantics, so the dialog states it is permanent. A re-check of the installed-package set immediately before deletion prevents racing an app that was installed after the scan. The Deduplicator refuses to delete every copy in a group.
- **Accessibility automation scope.** The automation service (`TroveAutomationService`) exists for root-less cache clearing. It only reacts to windows of `com.android.settings`, only performs clicks the planner identified (the "Storage & cache" row and the "Clear cache" button), and only while a clean is actively running; it never reads or acts on any other app's UI and stops when the queue is done.

## Recycle bin

Deletes default to a recycle bin (`.TroveTrash` + a restore index `.trove_trash_index.txt`) instead of unlinking. Permanent deletion always requires an explicit second confirmation ("This cannot be undone"). Inside the bin, delete means delete permanently and ⇄ restores to the original path.

## Embedded servers

FTP (2121), WebDAV (8080) and SFTP (2222) servers run **only** when explicitly toggled from the ⋮ menu, show state as checkable menu entries, and are stopped the same way. They bind for the duration of the session and are never started on boot.

## SAF & Android/data

SAF (`ScopedFileItem`) covers other apps' folders, `Android/data`, removable media and permissionless persisted grants — needed even with all-files access. The system tree picker is launched at the current folder where possible; note the storage root itself is excluded by Android 11+ policy (the picker's disabled button there is system behavior, not an app bug).

## CI security audits

`.github/workflows/ci.yml` runs on every push/PR:

- **Dependency Review** (PRs): fails on newly added dependencies with severity ≥ high.
- **Trivy filesystem scan**: CRITICAL/HIGH CVEs, `--ignore-unfixed`.
- Unit + security + fuzz test suites, and the dual-pass reproducibility audit.

## Clean-room

No decompiled or proprietary code anywhere in the repository. Functionality is reimplemented from observed behavior and public documentation (file management behavior after MiXplorer; cleaning behavior after the public docs of established maintenance tools such as SD Maid SE); internal analyses of other apps are kept outside the repo and never versioned. See [UI parity & clean-room policy](ui-parity.md).

## Reporting

Security issues: open a restricted GitHub Security Advisory (Security tab → Report a vulnerability) rather than a public issue.
