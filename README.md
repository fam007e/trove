# Trove

Trove is an open-source, reproducible Android file manager designed for security, privacy, and modularity.

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

## Building from Source

Prerequisites:
- JDK 21 LTS
- Android SDK (API Level 35, Build Tools 35.0.0)

To build a release APK:

```bash
./gradlew assembleRelease
```

To run unit tests across all modules:

```bash
./gradlew test
```

## F-Droid Reproducible Builds

Trove adheres to F-Droid reproducible build standards:
- Immutable dependency catalog (`gradle/libs.versions.toml`).
- Stable resource IDs enabled via `stable_ids.txt`.
- Parameter assertion stripping in release builds.
- Reproducibility verification script located at `scripts/verify_repro.sh`.

## License

Trove is licensed under the Apache License, Version 2.0. Third-party open-source components and licensing statements are detailed in `NOTICE.md`.
