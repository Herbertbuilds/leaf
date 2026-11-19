package io.herbert.leaf.format

import android.util.Xml
import io.herbert.leaf.core.TextCodec
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipFile

/** One rendered section: a book chapter, a spreadsheet sheet, a slide. */
data class Chapter(val title: String, val body: String)

/** XML/ZIP helpers shared by the format parsers. */
object Xmls {

    fun parser(input: InputStream): XmlPullParser = Xml.newPullParser().apply { setInput(input, null) }

    /** Local element name, with any namespace prefix stripped. */
    fun local(xp: XmlPullParser): String = xp.name.substringAfter(':')

    /** Attribute lookup by local name, ignoring namespaces (w:val, opf:val, ...). */
    fun attr(xp: XmlPullParser, name: String): String? {
        for (i in 0 until xp.attributeCount) {
            if (xp.getAttributeName(i).substringAfter(':').equals(name, ignoreCase = true)) {
                return xp.getAttributeValue(i)
            }
        }
        return null
    }

    fun isOn(xp: XmlPullParser): Boolean {
        val v = attr(xp, "val") ?: return true
        return !(v == "0" || v.equals("false", true) || v.equals("off", true))
    }

    fun textOf(z: ZipFile, path: String): String? {
        val e = z.getEntry(path) ?: return null
        return z.getInputStream(e).use { TextCodec.decode(it.readBytes()) }
    }

    fun text(xp: XmlPullParser): String = xp.nextText()

    /**
     * Shown at the end of a document a parser could only read part of. Without it a
     * damaged file silently looks like a short one.
     */
    const val PARTIAL =
        "<p class=\"note\">Leaf could not read all of this file; it may be damaged.</p>"
}
