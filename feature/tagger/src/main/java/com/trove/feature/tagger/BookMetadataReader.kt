package com.trove.feature.tagger

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.trove.core.fs.FileItem
import com.trove.core.fs.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Book & document metadata reader for PDF, EPUB, and MOBI formats.
 *
 * ## PDF
 * Uses PDFBox-Android to read the DocumentInfo dictionary (Title, Author,
 * Subject, Keywords, Creator, Producer, dates) and page count.
 *
 * ## EPUB
 * Parses the OPF content file inside the EPUB ZIP container to extract
 * Dublin Core metadata (dc:title, dc:creator, dc:subject, dc:date, etc.).
 * This is a lightweight custom parser — the MiX tagger addon bundles a
 * full EPUB library (obfuscated as `ab.a(inputStream)`), but we only need
 * the OPF metadata for tag display.
 *
 * ## MOBI
 * Read-only: extracts the PalmDOC header + EXTH records for title, author,
 * subject, publisher, ISBN, and cover thumbnail offset. MiX's obfuscated
 * `y9.B(inputStream)` does the same via a bundled MOBI parser; we do a
 * minimal header-only read.
 *
 * All formats are **read-only** in this initial implementation. PDF write
 * support can be added via PDFBox's `setDocumentInformation()` path, and
 * EPUB via re-writing the OPF inside the ZIP, but both are deferred to
 * avoid complexity in the MVP.
 */
object BookMetadataReader : TroveMetadataEditor {

    override fun canEdit(item: FileItem): Boolean = false // read-only for now

    override suspend fun readMetadata(item: FileItem): Result<MetadataBundle> =
        withContext(Dispatchers.IO) {
            try {
                val ext = item.name.substringAfterLast('.', "").lowercase()
                when (ext) {
                    "pdf" -> readPdf(item)
                    "epub" -> readEpub(item)
                    "mobi", "azw", "azw3", "prc" -> readMobi(item)
                    else -> Result.Failure(
                        UnsupportedOperationException("Unsupported book format: $ext")
                    )
                }
            } catch (e: Exception) {
                Result.Failure(e)
            }
        }

    override suspend fun writeMetadata(
        item: FileItem,
        bundle: MetadataBundle
    ): Result<Unit> = Result.Failure(
        UnsupportedOperationException(
            "Book metadata writing is not yet implemented. " +
            "PDF/EPUB/MOBI tags are currently read-only."
        )
    )

    // ---- PDF ----

    private suspend fun readPdf(item: FileItem): Result<MetadataBundle> {
        val stream = when (val r = item.openInputStream()) {
            is Result.Success -> r.value
            is Result.Failure -> return Result.Failure(r.exception)
        }

        val doc = stream.use { PDDocument.load(it) }
        try {
            val info = doc.documentInformation
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

            val bundle = MetadataBundle(
                title    = info?.title?.takeIf { it.isNotBlank() },
                author   = info?.author?.takeIf { it.isNotBlank() },
                subject  = info?.subject?.takeIf { it.isNotBlank() },
                keywords = info?.keywords?.split(",")
                    ?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: emptyList(),
                creator  = info?.creator?.takeIf { it.isNotBlank() },
                producer = info?.producer?.takeIf { it.isNotBlank() },
                creationDate     = info?.creationDate?.time?.let { dateFormat.format(it) },
                modificationDate = info?.modificationDate?.time?.let { dateFormat.format(it) },
                pageCount = doc.numberOfPages,
                extra = buildMap {
                    info?.trapped?.let { put("PDF/Trapped", it) }
                    put("PDF/Version", doc.version.toString())
                    if (doc.isEncrypted) put("PDF/Encrypted", "true")
                }
            )
            bundle.source = MetadataBundle.FormatFamily.PDF
            return Result.Success(bundle)
        } finally {
            doc.close()
        }
    }

    // ---- EPUB (OPF Dublin Core) ----

    /**
     * Minimal EPUB metadata reader. EPUBs are ZIP files containing an OPF
     * (Open Packaging Format) XML file with Dublin Core metadata.
     *
     * We scan the ZIP for `content.opf` (or any `.opf` file), then parse
     * the `<metadata>` element with simple string matching — no full XML
     * parser needed for tag extraction.
     */
    private suspend fun readEpub(item: FileItem): Result<MetadataBundle> {
        val stream = when (val r = item.openInputStream()) {
            is Result.Success -> r.value
            is Result.Failure -> return Result.Failure(r.exception)
        }

        var opfContent: String? = null
        stream.use { raw ->
            val zip = ZipInputStream(raw)
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".opf", ignoreCase = true)) {
                    opfContent = zip.bufferedReader().readText()
                    break
                }
                entry = zip.nextEntry
            }
        }

        if (opfContent == null) {
            return Result.Failure(
                IllegalStateException("No OPF file found in EPUB")
            )
        }

        val xml = opfContent!!
        val extra = linkedMapOf<String, String>()

        val title    = extractDcElement(xml, "title")
        val creators = extractAllDcElements(xml, "creator")
        val subjects = extractAllDcElements(xml, "subject")
        val date     = extractDcElement(xml, "date")
        val language = extractDcElement(xml, "language")
        val publisher = extractDcElement(xml, "publisher")
        val description = extractDcElement(xml, "description")
        val identifier = extractDcElement(xml, "identifier")
        val rights   = extractDcElement(xml, "rights")

        language?.let { extra["EPUB/Language"] = it }
        identifier?.let { extra["EPUB/Identifier"] = it }
        rights?.let { extra["EPUB/Rights"] = it }

        val bundle = MetadataBundle(
            title    = title,
            artists  = creators,
            subject  = description,
            keywords = subjects,
            creator  = publisher,
            creationDate = date,
            extra    = extra
        )
        bundle.source = MetadataBundle.FormatFamily.EPUB
        return Result.Success(bundle)
    }

    /** Extract the first occurrence of a Dublin Core element. */
    private fun extractDcElement(xml: String, element: String): String? {
        // Handles both <dc:title> and <dc:title opf:role="...">
        val regex = Regex(
            """<dc:$element[^>]*>(.*?)</dc:$element>""",
            RegexOption.DOT_MATCHES_ALL
        )
        return regex.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Extract all occurrences (e.g., multiple dc:creator for co-authors). */
    private fun extractAllDcElements(xml: String, element: String): List<String> {
        val regex = Regex(
            """<dc:$element[^>]*>(.*?)</dc:$element>""",
            RegexOption.DOT_MATCHES_ALL
        )
        return regex.findAll(xml)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    // ---- MOBI (PalmDOC + EXTH header) ----

    /**
     * Minimal MOBI header reader. Extracts title from the PalmDOC name field
     * and metadata from EXTH records (author, subject, publisher, ISBN, etc.).
     *
     * This mirrors MiX's `y9.B(inputStream)` → `jrVar.g()` → `krVar.i()`
     * chain, but as a clean-room minimal parser.
     *
     * MOBI file structure:
     * - Bytes 0–31: PalmDOC database name (null-terminated, title)
     * - Byte 60–63: Number of records
     * - After PalmDOC header: MOBI header with EXTH flag at offset 128
     * - If EXTH flag set: EXTH header follows with record type/data pairs
     */
    private suspend fun readMobi(item: FileItem): Result<MetadataBundle> {
        val stream = when (val r = item.openInputStream()) {
            is Result.Success -> r.value
            is Result.Failure -> return Result.Failure(r.exception)
        }

        return stream.use { raw ->
            try {
                val bytes = raw.readBytes()
                if (bytes.size < 78) {
                    return@use Result.Failure<MetadataBundle>(
                        IllegalStateException("File too small for MOBI format")
                    )
                }

                // PalmDOC name: first 32 bytes, null-terminated
                val nameEnd = bytes.indexOf(0.toByte()).coerceIn(0, 31)
                val palmName = String(bytes, 0, nameEnd, Charsets.ISO_8859_1).trim()

                val extra = linkedMapOf<String, String>()
                var author: String? = null
                var subject: String? = null
                var publisher: String? = null
                var isbn: String? = null
                var description: String? = null

                // Try to find and parse EXTH header
                val exthOffset = findExthOffset(bytes)
                if (exthOffset > 0 && exthOffset + 12 < bytes.size) {
                    val exthId = String(bytes, exthOffset, 4, Charsets.US_ASCII)
                    if (exthId == "EXTH") {
                        val recordCount = readInt(bytes, exthOffset + 8)
                        var pos = exthOffset + 12
                        for (i in 0 until recordCount.coerceAtMost(100)) {
                            if (pos + 8 > bytes.size) break
                            val recType = readInt(bytes, pos)
                            val recLen  = readInt(bytes, pos + 4)
                            if (recLen < 8 || pos + recLen > bytes.size) break
                            val recData = String(
                                bytes, pos + 8, recLen - 8, Charsets.UTF_8
                            ).trim()

                            when (recType) {
                                100 -> author = recData
                                101 -> publisher = recData
                                103 -> description = recData
                                104 -> isbn = recData
                                105 -> subject = recData
                                else -> if (recData.isNotEmpty()) {
                                    extra["MOBI/EXTH-$recType"] = recData
                                }
                            }
                            pos += recLen
                        }
                    }
                }

                isbn?.let { extra["MOBI/ISBN"] = it }

                val bundle = MetadataBundle(
                    title   = palmName.takeIf { it.isNotEmpty() },
                    author  = author,
                    artists = author?.let { listOf(it) } ?: emptyList(),
                    subject = description ?: subject,
                    creator = publisher,
                    keywords = subject?.let { listOf(it) } ?: emptyList(),
                    extra   = extra
                )
                bundle.source = MetadataBundle.FormatFamily.MOBI
                Result.Success(bundle)
            } catch (e: Exception) {
                Result.Failure(e)
            }
        }
    }

    /**
     * Scan for the EXTH header signature. It follows the MOBI header,
     * which starts at PalmDOC record 0's data offset. We search for
     * the "EXTH" magic bytes.
     */
    private fun findExthOffset(bytes: ByteArray): Int {
        val magic = "EXTH".toByteArray(Charsets.US_ASCII)
        // Start searching after the PalmDOC header (at least 78 bytes)
        for (i in 78 until (bytes.size - 4).coerceAtMost(4096)) {
            if (bytes[i] == magic[0] &&
                bytes[i + 1] == magic[1] &&
                bytes[i + 2] == magic[2] &&
                bytes[i + 3] == magic[3]
            ) {
                return i
            }
        }
        return -1
    }

    /** Read a big-endian 32-bit int. */
    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
}
