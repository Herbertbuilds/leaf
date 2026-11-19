package io.herbert.leaf.format

import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/** One relationship in an OOXML/ODF package. */
internal class Rel(val type: String, val target: String)

/**
 * Shared package plumbing for the zip-based formats: relationship maps, part paths and
 * media extraction. Kept in one place so Word, slides and OpenDocument agree on how a
 * `Target="../media/image1.png"` is resolved and named.
 */
internal object Parts {

    fun rels(z: ZipFile, path: String): Map<String, Rel> {
        val entry = z.getEntry(path) ?: return emptyMap()
        val out = HashMap<String, Rel>()
        try {
            z.getInputStream(entry).use { ins ->
                val xp = Xmls.parser(ins)
                var ev = xp.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && Xmls.local(xp) == "Relationship") {
                        val id = Xmls.attr(xp, "Id")
                        val target = Xmls.attr(xp, "Target")
                        if (id != null && !target.isNullOrEmpty()) {
                            out[id] = Rel(Xmls.attr(xp, "Type") ?: "", target)
                        }
                    }
                    ev = xp.next()
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    /** Resolves a relationship target against the part's directory ("word", "ppt/slides", ""). */
    fun resolve(baseDir: String, target: String): String? {
        var t = target.replace('\\', '/')
        if (t.contains("://") || t.startsWith("#")) return null
        val parts = ArrayList<String>()
        if (t.startsWith("/")) {
            t = t.removePrefix("/")
        } else {
            parts.addAll(baseDir.split('/').filter { it.isNotEmpty() })
        }
        for (seg in t.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    /** Unpacks a media part next to the chapters and returns its flat file name. */
    fun extract(z: ZipFile, path: String, dir: File): String? {
        val entry = z.getEntry(path) ?: return null
        // Flat names keep the src a single path segment; the reader's base URL is the dir.
        val name = path.replace('/', '_').replace(' ', '_')
        val dest = File(dir, name)
        if (!dest.isFile || dest.length() != entry.size) {
            try {
                dest.parentFile?.mkdirs()
                z.getInputStream(entry).use { ins ->
                    dest.outputStream().use { ins.copyTo(it, 64 * 1024) }
                }
            } catch (_: Throwable) {
                return null
            }
        }
        return name
    }
}
