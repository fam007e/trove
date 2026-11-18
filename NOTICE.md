# Trove Open Source Licensing & Third-Party Notices

Trove is licensed under the **Apache License, Version 2.0**.

---

## Third-Party Components & License Register

| Library | Version | License | Upstream Repository / Source |
|---|---|---|---|
| **AndroidX Suite** | Pinned (1.13.1 / 1.7.0 / 2.9.0) | Apache-2.0 | [android/platform/frameworks/support](https://github.com/androidx/androidx) |
| **AndroidX Media3 (ExoPlayer)** | 1.5.1 | Apache-2.0 | [androidx/media](https://github.com/androidx/media) |
| **Apache Commons Compress** | 1.26.2 | Apache-2.0 | [apache/commons-compress](https://github.com/apache/commons-compress) |
| **Apache MINA SSHD** | 2.12.1 | Apache-2.0 | [apache/mina-sshd](https://github.com/apache/mina-sshd) |
| **PDFBox-Android** | 2.0.27.0 | Apache-2.0 | [TomRoush/PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) |
| **Metadata Extractor** | 2.19.0 | Apache-2.0 | [drewnoakes/metadata-extractor](https://github.com/drewnoakes/metadata-extractor) |
| **Zip4j** | 2.11.5 | Apache-2.0 | [srikanth-lingala/zip4j](https://github.com/srikanth-lingala/zip4j) |
| **Ktor Framework** | 2.3.12 | Apache-2.0 | [ktorio/ktor](https://github.com/ktorio/ktor) |
| **Bouncy Castle** | 1.78.1 | Bouncy Castle Licence (MIT-style) | [bcgit/bc-java](https://github.com/bcgit/bc-java) |
| **Conscrypt** | 2.5.2 | Apache-2.0 | [google/conscrypt](https://github.com/google/conscrypt) |
| **SSHJ** | 0.38.0 | Apache-2.0 | [hierynomus/sshj](https://github.com/hierynomus/sshj) |
| **Shizuku API** | 13.1.5 | Apache-2.0 | [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API) |
| **Junrar** | 7.5.5 | UnRAR License / Apache-2.0 | [junrar/junrar](https://github.com/junrar/junrar) |
| **XZ for Java** | 1.9 | Public Domain | [tukaani/xz-java](https://tukaani.org/xz/java.html) |
| **JCIFS-NG** | 2.1.10 | **LGPL-2.1** | [Agno3/jcifs-ng](https://github.com/Agno3/jcifs-ng) |
| **JAudioTagger** | 3.0.1 | **LGPL-2.1** | [jthink/jaudiotagger](https://github.com/jthink/jaudiotagger) |

---

## GNU Lesser General Public License (LGPL 2.1) Compliance Statement

Trove uses **JCIFS-NG** (SMB protocol client) and **JAudioTagger** (audio metadata editor), both licensed under the GNU Lesser General Public License (LGPL), Version 2.1.

### Fulfilling LGPL 2.1 Section 6 Requirements:

1. **Unmodified and Source-Linked**: The source code for the exact versions of JCIFS-NG and JAudioTagger used in this distribution is publicly available at their upstream repositories linked in the table above.
2. **Dynamic Linking & Replacement**:
   - Trove compiles these libraries as distinct module dependencies.
   - ProGuard / R8 rules (`proguard-rules.pro`) preserve all public method signatures, packages, and class boundaries of `eu.agno3.jcifs.*` and `org.jaudiotagger.*`.
   - Any developer or end-user building Trove from source or modifying APK bytecode can substitute a modified version of `jcifs-ng` or `jaudiotagger` without modifying the Trove host codebase.
3. **No DRM or Cryptographic Gating**: Trove does not impose hardware or cryptographic locks that prevent the execution of user-modified versions of these LGPL libraries on user-controlled hardware.
