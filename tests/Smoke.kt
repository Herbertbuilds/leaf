import io.herbert.leaf.core.Chunker
import io.herbert.leaf.core.Finder
import io.herbert.leaf.core.Head
import io.herbert.leaf.core.Html
import io.herbert.leaf.core.Magic
import io.herbert.leaf.core.Markdown
import io.herbert.leaf.core.PalmDoc
import io.herbert.leaf.core.Rtf
import io.herbert.leaf.core.TextCodec

/**
 * Desktop smoke test for the pure parsers. No Android classes here on purpose, so this
 * runs on a plain JVM:
 *
 *   ./test.sh
 */

private var failures = 0

private fun check(name: String, ok: Boolean) {
    if (ok) {
        println("  ok    $name")
    } else {
        println("  FAIL  $name")
        failures++
    }
}

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

fun main() {
    println("magic")
    check("pdf", Magic.head(("%PDF-1.7\n").toByteArray()) == Head.PDF)
    check("zip", Magic.head(bytes(0x50, 0x4B, 0x03, 0x04, 0, 0)) == Head.ZIP)
    check("png", Magic.head(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)) == Head.PNG)
    check("jpeg", Magic.head(bytes(0xFF, 0xD8, 0xFF, 0xE0)) == Head.JPEG)
    check("rtf", Magic.head(("{\\rtf1\\ansi test}").toByteArray()) == Head.RTF)
    check("html", Magic.head("<html><body>hi</body></html>".toByteArray()) == Head.HTML)
    check("text", Magic.head("hello world\nsecond line\n".toByteArray()) == Head.TEXT)
    check("binary", Magic.head(bytes(0, 0, 0, 0, 1, 2, 3, 0)) == Head.BINARY)
    check("mobi", Magic.head(ByteArray(80).also {
        "BOOKMOBI".toByteArray().copyInto(it, 60)
    }) == Head.MOBI)

    println("textcodec")
    val bom = bytes(0xEF, 0xBB, 0xBF) + "héllo".toByteArray()
    check("utf8 bom", TextCodec.decode(bom) == "héllo")
    check("latin1 fallback", TextCodec.decode(bytes(0xE9, 0x20, 0x78)) == "é x")
    check("ascii", TextCodec.decode("plain".toByteArray()) == "plain")

    println("chunker")
    val long = buildString { repeat(400) { append("line $it padding padding padding\n") } }
    val chunks = Chunker.split(long)
    check("lossless", chunks.joinToString("") == long)
    check("bounded", chunks.all { it.length <= Chunker.TARGET })
    check("multi", chunks.size > 1)

    println("markdown")
    val md = Markdown.toHtml("# Title\n\nSome **bold** and *it* text.\n\n- one\n- two\n\n```\ncode <x>\n```")
    check("heading", md.contains("<h1>Title</h1>"))
    check("strong", md.contains("<strong>bold</strong>"))
    check("em", md.contains("<em>it</em>"))
    check("list", md.contains("<ul><li>one</li><li>two</li></ul>"))
    check("code fence", md.contains("<pre><code>code &lt;x&gt;"))
    check(
        "link quotes cannot break the attribute",
        Markdown.toHtml("[a](x\"y)").contains("href=\"x&quot;y\"")
    )

    println("palmdoc")
    val compressed = bytes(0x61, 0x62, 0x63, 0x80, 0x18)
    check("back reference", String(PalmDoc.decompress(compressed, 4096)) == "abcabc")
    check("literal run", String(PalmDoc.decompress(bytes(0x02, 0x68, 0x69), 4096)) == "hi")

    println("rtf")
    check("paragraphs", Rtf.toText("{\\rtf1\\ansi Hello\\par World}") == "Hello\nWorld")
    check("escaped brace", Rtf.toText("{\\rtf1 a\\{b\\}c}") == "a{b}c")
    check("ignored font table", Rtf.toText("{\\rtf1{\\fonttbl{\\f0 Arial;}}Body}").trim() == "Body")
    check(
        "unicode escape drops fallback",
        Rtf.toText("{\\rtf1\\ansi\\uc1 caf\\u233? na\\u239?ve}") == "caf\u00e9 na\u00efve"
    )
    check("unicode escape with uc0", Rtf.toText("{\\rtf1\\ansi\\uc0 caf\\u233}") == "caf\u00e9")
    check(
        "uc2 drops two fallbacks",
        Rtf.toText("{\\rtf1\\ansi\\uc2 a\\u26408??b") == "a\u6728b"
    )

    println("html")
    check("strip tags", Html.stripTags("<p>Hello<br>world</p>") == "Hello\nworld")
    check("body of", Html.bodyOf("<html><head><title>t</title></head><body><p>y</p></body></html>").trim() == "<p>y</p>")
    check("escape", Html.esc("a<b&c") == "a&lt;b&amp;c")
    check("escape quotes", Html.esc("say \"hi\"") == "say &quot;hi&quot;")

    println("finder")
    val finderChunks = listOf("Hello world, hello.", "nothing here", "HELLO again")
    val hits = Finder.all(finderChunks, "hello")
    check("case insensitive count", hits.size == 3)
    check("chunk indexes", hits.map { it.chunk } == listOf(0, 0, 2))
    check("offsets", hits.map { it.offset } == listOf(0, 13, 0))
    check("offset lands on text", finderChunks[0].substring(13, 18) == "hello")
    check("no match", Finder.all(finderChunks, "zzz").isEmpty())
    check("empty query", Finder.all(finderChunks, "").isEmpty())
    check("limit respected", Finder.all(finderChunks, "l", 3).size == 3)
    check("all l's", Finder.all(finderChunks, "l").size == 7)
    check("accented text", Finder.all(listOf("café CAFÉ"), "café").size == 2)

    if (failures > 0) {
        println("\n$failures check(s) failed")
        kotlin.system.exitProcess(1)
    }
    println("\nall checks passed")
}
