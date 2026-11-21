---
title: Architecture
---

# Architecture

Trove is 16 decoupled Gradle modules over one virtual filesystem abstraction. No module imports another module's concrete classes — everything crosses boundaries through `FileItem` interfaces and small data types (`Result<T>`, `MetadataBundle`, …).

## Module map

| Module | Responsibility |
| --- | --- |
| `:app` | Application entry, manifest assembly, FileProvider, versioning |
| `:ui:browse` | `BrowseActivity` (top bar, tabs, dual-pane host, drawer), `BrowseFragment`, selection ops, dialogs, Home dashboard |
| `:ui:widgets` | Custom-drawn controls: `TroveTabBar`, `TroveBreadcrumbBar`, `FileRowView` |
| `:core:fs` | `FileItem` interface + `LocalFileItem`, `ScopedFileItem`, path canonicalization, `Result` |
| `:core:net` | FTP, SFTP (MINA SSHD), SMB, WebDAV clients; cloud backends (Drive, Dropbox, OneDrive, MediaFire, TeraBox); OAuth PKCE engine |
| `:core:crypto` | `TrveChunked*` AES-256-GCM streaming vault, Argon2id key derivation, biometric auth, EncFS config parsing, `VaultFileItem` |
| `:server:ftp` / `:server:webdav` / `:server:sftp` | Embedded background servers (ports 2121 / 8080 / 2222), started explicitly from the ⋮ menu |
| `:privilege` | `TrovePrivilegedService` — Shizuku user service with AIDL surface (`stat`, `listFiles`, `openFile`, `rename`, `delete`, `mkdir`) and caller-UID validation |
| `:feature:archive` | 7-Zip-JBinding fork engine; browse-inside, create, extract (ZIP, 7Z, TAR, GZ, BZ2, XZ, RAR) |
| `:feature:image` | Bounds-first decoding, bitmap pooling, EXIF orientation |
| `:feature:pdf` | PDFBox-based viewer, day/night modes, text extraction |
| `:feature:tagger` | JAudioTagger + metadata-extractor engines; read/write tags for audio, image, video, ebook files |
| `:feature:media` | Media3/ExoPlayer with a custom `FileItemDataSource` that streams from **any** backend |
| `:feature:cleaner` | Storage cleaning: App Cleaner, Corpse Finder, Deduplicator, System Cleaner; persistent `su` shell (`RootShell`) with a framed sentinel protocol; pure, unit-tested decision logic (`ShellProtocol`, `AutomationPlanner`, filter rules, keep rules); accessibility automation service for root-less cache clearing |

## The `FileItem` abstraction

Every storage backend implements the same small interface, so all UI operations (copy, move, delete, rename, create, search, info) are written once:

```
suspend fun listFiles(): Result<List<FileItem>>
suspend fun openInputStream() / openOutputStream(append)
suspend fun createFile(name) / createDirectory(name) / delete() / rename()
suspend fun copyTo(dest) / moveTo(destDir, name) / findChild(name) / getParent()
```

Implementations: `LocalFileItem` (java.io), `ScopedFileItem` (SAF `DocumentFile`), `ArchiveFileItem` (archive roots/nested entries), cloud and network items in `:core:net`, `VaultFileItem` (decrypted view of vault files), `PrivilegedFileItem` (binder to the privilege service), `MediaStoreFileItem` (MediaStore collections for the Home categories).

Cross-backend operations degrade gracefully: `copyTo` streams when the source and destination live in different worlds; `moveTo` falls back to copy+delete across backends.

**Design rule learned from the engines this project replicates:** every engine must work against stream/listener *file abstractions*, never `java.io.File` directly — that is what lets the archive extractor write into SAF, the vault, or SFTP without engine changes.

## Threading and errors

All I/O is coroutine-based (`Dispatchers.IO`), every fallible call returns `Result<T>` (`com.trove.core.fs.Result`) — no exceptions cross module boundaries except at the Android API edge.

## Privilege layer

Trove has two privilege paths:

1. **Shizuku user service** (`:privilege`, `daemon = false`, dies with Shizuku). The AIDL surface is deliberately tiny; `PrivilegedFileItem` implements the full `FileItem` interface on top of it, so privileged tabs behave identically to local ones. The service validates the **caller UID** on every binder call — only Trove's own UID is accepted. When Shizuku runs as root on a rooted device, the service executes as UID 0 and privileged tabs can reach other apps' private data and protected system paths.
2. **Direct root shell** (`:feature:cleaner`'s `RootShell`) for the Cleaner. One persistent `su` process is kept alive; every command is wrapped in echo sentinels so output and exit codes are matched per command, and timeouts tear the session down (which re-arms a pending Magisk grant prompt). Magisk's `-M` flag forces the **global mount namespace** — without it `su` inherits the app's isolated namespace, where `/data/data` shows only Trove's own directory. Names that reach the shell pass through `PackageNameValidator` first; nothing else is ever interpolated. See [Security model → Cleaner & root shell](security.md#cleaner--root-shell).

## UI structure

`BrowseActivity` owns top bar, per-pane tab bars (`TroveTabBar`), `ViewPager2` fragment adapters, the drawer and dual-pane state (`DualPaneState`, fragment tags `f{pane*10000+index}`). Panes are `BrowseFragment`s; the activity implements `DualPaneHost`/`TabHost` interfaces so fragments never reach into concrete activity internals except where R8 full mode requires concrete casts (documented R8 lesson: interface casts on `HomeHost`-style interfaces broke under full-mode shrinking — concrete-class casts are used there instead).

## Versioning & reproducibility

Version stamping and the reproducible-build contract live in [Building](building.md) and [Releases](releases.md).
