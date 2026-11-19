package io.herbert.leaf

import io.herbert.leaf.core.Head
import io.herbert.leaf.core.Magic
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipFile

enum class Fmt(val label: String) {
    PDF("PDF"),
    EPUB("EPUB"),
    CBZ("Comic book"),
    DOCX("Word document"),
    ODT("OpenDocument"),
    PPTX("Slides"),
    XLSX("Spreadsheet"),
    MOBI("Kindle book"),
    RTF("Rich text"),
    MARKDOWN("Markdown"),
    TEXT("Plain text"),
    ARCHIVE("Archive"),
    UNKNOWN("Unknown"),
}

object Sniff {

    /**
     * Extensions Leaf reads, lower case and without the dot. Anything else is refused
     * before it is copied, so a stray 4 GB video never lands in the cache.
     */
    val SUPPORTED = setOf(
        "pdf", "docx", "txt", "xlsx", "pptx", "odt", "rtf", "md", "csv",
        // Markdown by its longer names; the reader treats them the same as `.md`.
        "markdown", "mdx",
    )

    /**
     * The MIME types providers use for those nine formats, offered to the picker.
     * `application/octet-stream` is included because some cloud and NAS providers label
     * a perfectly good `.md` or `.rtf` that way; without it those files cannot be picked
     * at all. The reader still checks the name and the bytes afterwards.
     *
     * `AndroidManifest.xml` lists the same document types (without octet-stream) in its
     * VIEW filter, which is what decides whether Leaf appears in another app's
     * "Open with" sheet.
     */
    val MIME_TYPES = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text",
        "application/rtf",
        "text/rtf",
        "text/plain",
        "text/markdown",
        "text/x-markdown",
        "text/csv",
        "text/comma-separated-values",
        "application/octet-stream",
    )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** True when the file name itself says Leaf should open it. */
    fun supportedName(name: String): Boolean {
        val ext = extension(name)
        // No extension: let the bytes decide, then gate on the detected format.
        return ext.isEmpty() || ext in SUPPORTED
    }

    /** Formats Leaf actually draws. Everything else is reported, not parsed. */
    fun renderable(fmt: Fmt): Boolean = when (fmt) {
        Fmt.PDF, Fmt.DOCX, Fmt.XLSX, Fmt.PPTX, Fmt.ODT, Fmt.RTF,
        Fmt.TEXT, Fmt.MARKDOWN -> true
        Fmt.EPUB, Fmt.CBZ, Fmt.MOBI, Fmt.ARCHIVE, Fmt.UNKNOWN -> false
    }

    fun detect(file: File, name: String): Fmt {
        val head = readHead(file, 2048)
        val ext = extension(name)
        return when (Magic.head(head)) {
            Head.PDF -> Fmt.PDF
            Head.ZIP -> zipKind(file)
            Head.MOBI -> Fmt.MOBI
            Head.RTF -> Fmt.RTF
            Head.TEXT -> {
                val n = name.lowercase()
                if (n.endsWith(".md") || n.endsWith(".markdown") || n.endsWith(".mdx")) Fmt.MARKDOWN else Fmt.TEXT
            }
            // UTF-16 text is full of NUL bytes, so the byte heuristic calls it binary.
            // For a file whose name already says "text", trust the name and let
            // TextCodec's BOM handling do the decoding.
            Head.BINARY -> if (ext == "txt" || ext == "csv" || ext == "md") Fmt.TEXT else Fmt.UNKNOWN
            // Leaf is a document reader: web pages and pictures deliberately have no
            // renderer, so they fall through to the honest "unsupported" screen.
            Head.PNG, Head.JPEG, Head.GIF, Head.WEBP, Head.BMP, Head.HTML,
            Head.TIFF -> Fmt.UNKNOWN
        }
    }

    private fun zipKind(file: File): Fmt = try {
        ZipFile(file).use { z ->
            val mime = z.getEntry("mimetype")?.let { e ->
                z.getInputStream(e).use { ins ->
                    val raw = ByteArray(64)
                    var off = 0
                    while (off < raw.size) {
                        val n = ins.read(raw, off, raw.size - off)
                        if (n < 0) break
                        off += n
                    }
                    String(raw, 0, off, Charsets.US_ASCII).trim()
                }
            }
            when {
                mime != null && mime.startsWith("application/epub") -> Fmt.EPUB
                mime != null && mime.contains("opendocument.text") -> Fmt.ODT
                z.getEntry("word/document.xml") != null -> Fmt.DOCX
                z.getEntry("xl/workbook.xml") != null -> Fmt.XLSX
                z.getEntry("ppt/presentation.xml") != null -> Fmt.PPTX
                z.entries().toList().any { !it.isDirectory && Magic.isImageName(it.name) } -> Fmt.CBZ
                else -> Fmt.ARCHIVE
            }
        }
    } catch (_: IOException) {
        Fmt.UNKNOWN
    }

    fun readHead(file: File, want: Int): ByteArray = try {
        RandomAccessFile(file, "r").use { raf ->
            val len = minOf(want.toLong(), raf.length()).toInt()
            val b = ByteArray(len)
            if (len > 0) raf.readFully(b)
            b
        }
    } catch (_: IOException) {
        ByteArray(0)
    }
}
