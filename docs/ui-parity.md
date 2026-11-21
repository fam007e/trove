---
title: UI parity & clean-room policy
---

# UI parity & clean-room policy

## The policy

Trove replicates the *functionality and interaction model* users know from MiXplorer — an established, power-user-friendly Android file manager — and, for the built-in storage cleaning, from the documented module behavior of established maintenance tools such as SD Maid SE. It does so **clean-room**:

- **No decompiled code, resources or assets from any proprietary or copyleft app are in this repository** — not as source, not as bytecode, not as identifiers.
- Functional parity is derived from *observed behavior* (what the UI does when you tap things) and from public engine documentation.
- Any private analysis of other apps' internals lives **outside** the repository and is never committed or published; docs in this repo are written fresh from Trove's own design.

This is what keeps the Apache-2.0 licensing clean and the F-Droid submission unproblematic.

## Parity status (device-verified on a real handset)

| Area | Status |
| --- | --- |
| Dual-pane split view, cross-pane copy/move | ✅ |
| Per-pane tabs + tab menu (add/reset/close/close-others/jump-to) | ✅ |
| Home dashboard with MediaStore categories | ✅ |
| List/grid, sort (name/size/date/type × both directions), hidden files | ✅ |
| Thumbnails (images, video frames, PDF page 1, APK icons) | ✅ |
| Folder sizes + item counts + content badges | ✅ |
| Breadcrumb with tail-truncation + disk-usage chip | ✅ |
| Selection ops (copy/move/extract/archive/share/rename/info/checksum) | ✅ |
| Recursive bounded search + select-by-pattern | ✅ |
| Archive create/extract (ZIP, 7Z, TAR+gz/bz2/xz, RAR browse) | ✅ |
| Recycle bin with restore + permanent delete | ✅ |
| Text editor, APK install dialog, open-any-file via FileProvider | ✅ |
| Network places (FTP/SFTP/SMB/WebDAV) + remote tabs | ✅ |
| Cloud backends (Drive, Dropbox, OneDrive, MediaFire, TeraBox) | ✅ |
| Metadata tagger (audio/image/video/ebook) + player | ✅ |
| Encrypted vault (AES-256-GCM/Argon2id/biometric) | ✅ |
| Embedded FTP/WebDAV/SFTP servers | ✅ |
| Shizuku privileged tabs (root-capable) | ✅ |
| Termux integration (RUN_COMMAND, runtime permission) | ✅ |
| Side drawer (places, bookmarks, recycle bin) | ✅ |
| Home disk-usage stats, TalkBack content descriptions | ✅ |

## Beyond parity — built-in Cleaner

Storage cleaning goes beyond what MiXplorer offers; it follows the interaction model users know from dedicated maintenance tools (scan → review → select → confirm), implemented from Trove's own design:

| Module | What it does | Access |
| --- | --- | --- |
| App Cleaner | Clears expendable caches of installed apps | Public caches without root; private caches with root (`su`), or via accessibility automation |
| Corpse Finder | Finds and removes data left by uninstalled apps | Root areas (`/data/data`, `/data/app-lib`) via `su`; external areas via all-files access |
| Deduplicator | Finds byte-identical files (size → head-hash → full SHA-256), keep-oldest policy with per-copy "keep this one" override; name-matching default | User storage, no root needed |
| System Cleaner | Toggleable filters: thumbnails, logs, temp/backup files, empty dirs, root crash-report areas | User storage; root areas via `su` |

Device-verified on a rooted handset (Magisk grant: live scans and cleans through the direct-`su` path) and for the no-root route (accessibility automation live-tested driving the system "Clear cache" flow). See [Security model → Cleaner & root shell](security.md#cleaner--root-shell) for the hardening and [Roadmap](roadmap.md) for what is not done yet.

## UI decisions that intentionally diverge

- Top bar is `☰ SAF Storage Shizuku ⋮` with compact text items — MiX hides these behind menus; Trove surfaces the most-used mounts directly.
- Breadcrumb tail-truncates with a leading `…` (tappable → first hidden ancestor) instead of horizontal scrolling; the disk-usage figure is a pill chip in the same row.
- Selection action bar uses **text labels**, not glyphs (discoverability; MiX's glyph-only bar was a known pain point).
- The ＋ button opens *New folder / New file*; the bottom bar hides during selection, matching MiX.
