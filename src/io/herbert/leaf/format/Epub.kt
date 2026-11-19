package io.herbert.leaf.format

import io.herbert.leaf.core.Html
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/**
 * EPUB: container.xml -> OPF manifest/spine -> one Chapter per spine item, with
 * manifest images unpacked next to the chapters so <img> tags resolve through the
 * WebView base URL.
 */
object Epub {

    fun load(file: File, cacheRoot: File): Pair<List<Chapter>, String> = try {
        ZipFile(file).use { z -> loadFrom(z, cacheRoot) }
    } catch (_: Throwable) {
        emptyList<Chapter>() to ""
    }

    private fun loadFrom(z: ZipFile, cacheRoot: File): Pair<List<Chapter>, String> {
        val container = Xmls.textOf(z, "META-INF/container.xml") ?: ""
        val opfPath = Regex("<rootfile[^>]*full-path\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(container)?.groupValues?.get(1)
            ?: z.entries().toList().firstOrNull { it.name.endsWith(".opf") }?.name
            ?: return emptyList<Chapter>() to ""

        val opf = Xmls.textOf(z, opfPath) ?: return emptyList<Chapter>() to ""
        val opfDir = opfPath.substringBeforeLast('/', "")

        val hrefs = HashMap<String, String>()
        val media = HashMap<String, String>()
        val spine = ArrayList<String>()

        val xp = Xmls.parser(opf.byteInputStream())
        var ev = xp.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (Xmls.local(xp)) {
                    "item" -> {
                        val id = Xmls.attr(xp, "id")
                        val href = Xmls.attr(xp, "href")
                        if (id != null && href != null) {
                            hrefs[id] = href
                            media[id] = Xmls.attr(xp, "media-type") ?: ""
                        }
                    }
                    "itemref" -> Xmls.attr(xp, "idref")?.let { spine.add(it) }
                }
            }
            ev = xp.next()
        }

        val outDir = File(cacheRoot, "epub").apply { mkdirs() }
        val base = "file://" + outDir.absolutePath + "/"

        // Unpack images so relative src attributes work from the file:// base URL.
        for ((id, href) in hrefs) {
            val type = media[id] ?: ""
            if (!type.startsWith("image/") && !io.herbert.leaf.core.Magic.isImageName(href)) continue
            val path = resolve(opfDir, href)
            if (path.isEmpty()) continue
            val entry = z.getEntry(path) ?: continue
            val dest = File(outDir, path)
            dest.parentFile?.mkdirs()
            try {
                z.getInputStream(entry).use { ins -> dest.outputStream().use { ins.copyTo(it, 64 * 1024) } }
            } catch (_: Throwable) {
            }
        }

        val chapters = ArrayList<Chapter>()
        for ((n, idref) in spine.withIndex()) {
            val href = hrefs[idref] ?: continue
            val path = resolve(opfDir, href)
            if (path.isEmpty()) continue
            val raw = Xmls.textOf(z, path) ?: continue
            val body = absolutize(Html.bodyOf(raw), path, base)
            if (body.isBlank()) continue
            val title = titleOf(body) ?: "Section ${n + 1}"
            chapters.add(Chapter(title, body))
        }

        // Some books have a broken spine; fall back to every XHTML file in the archive.
        if (chapters.isEmpty()) {
            for (e in z.entries()) {
                val n = e.name.lowercase()
                if (e.isDirectory || !(n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm"))) continue
                val raw = Xmls.textOf(z, e.name) ?: continue
                val body = absolutize(Html.bodyOf(raw), e.name, base)
                if (body.isNotBlank()) chapters.add(Chapter(titleOf(body) ?: e.name.substringAfterLast('/'), body))
            }
        }

        return chapters to base
    }

    /**
     * Chapter HTML usually sits in a subdirectory and refers to "../images/x.png".
     * Rewrite those image sources to absolute file:// URLs so one base URL works for
     * every chapter.
     */
    private fun absolutize(body: String, chapterPath: String, base: String): String {
        val dir = chapterPath.substringBeforeLast('/', "")
        return body.replace(Regex("(?i)(<img\\b[^>]*?\\bsrc\\s*=\\s*)([\"'])([^\"']+)\\2")) { m ->
            val raw = m.groupValues[3].trim()
            val url = if (raw.contains("://") || raw.startsWith("data:") || raw.startsWith("#")) {
                raw
            } else {
                val resolved = resolve(dir, raw)
                if (resolved.isEmpty()) raw else base + resolved.replace(" ", "%20")
            }
            m.groupValues[1] + "\"" + url + "\""
        }
    }

    private fun titleOf(body: String): String? {
        val h = Regex("(?is)<h[1-2][^>]*>(.*?)</h[1-2]>").find(body)?.groupValues?.get(1)
        val t = h ?: Regex("(?is)<title[^>]*>(.*?)</title>").find(body)?.groupValues?.get(1)
        val cleaned = t?.let { Html.stripTags(it) }?.trim()?.replace(Regex("\\s+"), " ")
        return cleaned?.take(120)?.takeIf { it.isNotEmpty() }
    }

    /** Resolves an href against the OPF directory, decoding %20 and collapsing "..". */
    fun resolve(dir: String, href: String): String {
        var h = href.substringBefore('#')
        if (h.contains("://") || h.startsWith("mailto:") || h.startsWith("data:")) return ""
        h = try {
            java.net.URLDecoder.decode(h, "UTF-8")
        } catch (_: Throwable) {
            h
        }
        val parts = ArrayList<String>()
        if (!h.startsWith("/") && dir.isNotEmpty()) parts.addAll(dir.split('/'))
        for (seg in h.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }
}
