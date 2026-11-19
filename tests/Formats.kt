import io.herbert.leaf.Fmt
import io.herbert.leaf.Sniff
import io.herbert.leaf.core.Html
import io.herbert.leaf.core.PalmDoc
import io.herbert.leaf.format.Epub
import io.herbert.leaf.format.Mobi
import io.herbert.leaf.format.Odt
import io.herbert.leaf.format.Docx
import io.herbert.leaf.format.Ooxml
import io.herbert.leaf.format.Pptx
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Format-layer tests: really builds EPUB/DOCX/XLSX/ODT/MOBI containers in a temp folder,
 * then runs the same parsers the app uses. Runs on a desktop JVM with kxml2 standing in
 * for Android's XML parser, so no device is needed.
 */

private var failures = 0

private fun check(name: String, ok: Boolean) {
    if (ok) println("  ok    $name") else {
        println("  FAIL  $name"); failures++
    }
}

private fun zipTo(file: File, entries: List<Pair<String, String>>, bin: List<Pair<String, ByteArray>> = emptyList()) {
    ZipOutputStream(file.outputStream()).use { out ->
        for ((name, data) in entries) {
            out.putNextEntry(ZipEntry(name))
            out.write(data.toByteArray())
            out.closeEntry()
        }
        for ((name, data) in bin) {
            out.putNextEntry(ZipEntry(name))
            out.write(data)
            out.closeEntry()
        }
    }
}

private fun putU16(b: ByteArray, off: Int, v: Int) {
    b[off] = ((v shr 8) and 0xFF).toByte()
    b[off + 1] = (v and 0xFF).toByte()
}

private fun putI32(b: ByteArray, off: Int, v: Int) {
    b[off] = ((v shr 24) and 0xFF).toByte()
    b[off + 1] = ((v shr 16) and 0xFF).toByte()
    b[off + 2] = ((v shr 8) and 0xFF).toByte()
    b[off + 3] = (v and 0xFF).toByte()
}

/** Literal-only PalmDOC stream: valid LZ77 input, exercises the decoder end to end. */
private fun literalCompress(src: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    for (b in src) {
        val c = b.toInt() and 0xFF
        if (c in 0x09..0x7F) {
            out.write(c)
        } else {
            out.write(1)
            out.write(c)
        }
    }
    return out.toByteArray()
}

private fun buildMobi(file: File, html: String, compressed: Boolean) {
    val raw = html.toByteArray()
    val body = if (compressed) literalCompress(raw) else raw

    val rec0 = ByteArray(16)
    putU16(rec0, 0, if (compressed) 2 else 1)
    putI32(rec0, 4, raw.size)
    putU16(rec0, 8, 1)
    putU16(rec0, 10, 4096)
    putU16(rec0, 12, 0)

    val tableEnd = 78 + 2 * 4
    val header = ByteArray(tableEnd)
    "BOOKMOBI".toByteArray().copyInto(header, 60)
    putU16(header, 76, 2)
    putI32(header, 78, tableEnd)
    putI32(header, 82, tableEnd + rec0.size)

    file.outputStream().use { out ->
        out.write(header)
        out.write(rec0)
        out.write(body)
    }
}

fun main() {
    val tmp = File(System.getProperty("java.io.tmpdir"), "leaf-tests-" + System.nanoTime()).apply { mkdirs() }

    // ---- EPUB ----------------------------------------------------------------
    println("epub")
    val epub = File(tmp, "sample.epub")
    zipTo(
        epub,
        listOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to
                """<?xml version="1.0"?><container><rootfiles>
                   <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                   </rootfiles></container>""",
            "OEBPS/content.opf" to
                """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf">
                   <manifest>
                     <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                     <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                     <item id="pic" href="images/pic.png" media-type="image/png"/>
                   </manifest>
                   <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
                   </package>""",
            "OEBPS/text/ch1.xhtml" to
                """<html><head><title>Chapter One</title></head><body>
                   <h1>Chapter One</h1><p>Hello <b>world</b>.</p>
                   <img src="../images/pic.png" alt="pic"/></body></html>""",
            "OEBPS/text/ch2.xhtml" to
                """<html><body><h2>Second</h2><p>More text.</p></body></html>""",
        ),
        listOf("OEBPS/images/pic.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)),
    )

    check("sniff epub", Sniff.detect(epub, "sample.epub") == Fmt.EPUB)
    val (chapters, base) = Epub.load(epub, File(tmp, "cache"))
    check("chapter count", chapters.size == 2)
    check("chapter title from h1", chapters.firstOrNull()?.title == "Chapter One")
    check("chapter body kept markup", chapters.firstOrNull()?.body?.contains("<b>world</b>") == true)
    check("image src absolutized", chapters.firstOrNull()?.body?.contains("file://") == true &&
        chapters.firstOrNull()?.body?.contains("OEBPS/images/pic.png") == true)
    check("image extracted to cache", File(tmp, "cache/epub/OEBPS/images/pic.png").isFile)
    check("base url is a dir", base.startsWith("file://") && base.endsWith("/"))

    // ---- DOCX ----------------------------------------------------------------
    println("docx")
    val docx = File(tmp, "sample.docx")
    val docxMedia = File(tmp, "docx-media")
    zipTo(
        docx,
        listOf(
            "word/document.xml" to
                """<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                   <w:background w:color="FFF8E7"/>
                   <w:body>
                     <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Report</w:t></w:r></w:p>
                     <w:p><w:r><w:t xml:space="preserve">Body </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>bold</w:t></w:r></w:p>
                     <w:p><w:pPr><w:jc w:val="center"/><w:ind w:left="720"/>
                         <w:shd w:val="clear" w:fill="FFF2CC"/></w:pPr>
                       <w:r><w:rPr><w:i/><w:color w:val="C00000"/><w:sz w:val="28"/></w:rPr><w:t>Centred</w:t></w:r></w:p>
                     <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>
                       <w:r><w:t>First item</w:t></w:r></w:p>
                     <w:p><w:r><w:drawing><wp:inline xmlns:wp="wp">
                         <wp:extent cx="952500" cy="476250"/>
                         <a:graphic xmlns:a="a"><a:graphicData>
                           <pic:pic xmlns:pic="pic"><pic:blipFill><a:blip r:embed="rId1" xmlns:r="r"/></pic:blipFill></pic:pic>
                         </a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>
                     <w:tbl>
                       <w:tr>
                         <w:tc><w:tcPr><w:shd w:val="clear" w:fill="D9D9D9"/></w:tcPr>
                           <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Head</w:t></w:r></w:p></w:tc>
                         <w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr>
                           <w:p><w:r><w:t>Wide</w:t></w:r></w:p></w:tc>
                       </w:tr>
                     </w:tbl>
                     <w:p><w:r><w:br w:type="page"/></w:r><w:r><w:t>Tail</w:t></w:r></w:p>
                   </w:body></w:document>""",
            "word/_rels/document.xml.rels" to
                """<Relationships><Relationship Id="rId1"
                   Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
                   Target="media/logo.png"/></Relationships>""",
            "word/numbering.xml" to
                """<w:numbering xmlns:w="w">
                   <w:abstractNum w:abstractNumId="0">
                     <w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl>
                   </w:abstractNum>
                   <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
                 </w:numbering>""",
        ),
        listOf("word/media/logo.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 7, 8)),
    )
    check("sniff docx", Sniff.detect(docx, "sample.docx") == Fmt.DOCX)
    ZipFile(docx).use { z ->
        val html = Docx.html(z, docxMedia).first()
        check("heading mapped", html.contains("<h1>Report</h1>"))
        check("bold run kept", html.contains("<b>bold</b>"))
        check("paragraph text with preserved space", html.contains("<p>Body&nbsp;<b>bold</b></p>"))
        check("page background", html.contains("""class="docpage" style="background:#FFF8E7""""))
        check("centred paragraph", html.contains("text-align:center"))
        check("paragraph indent", html.contains("margin-left:36.0pt"))
        check("paragraph shading", html.contains("background:#FFF2CC"))
        check("italic colour size", html.contains("color:#C00000") && html.contains("font-size:14.0pt") && html.contains("<i>"))
        check("numbered list item", html.contains("""<span class="mk">1.</span>"""))
        check("picture extracted", File(docxMedia, "word_media_logo.png").isFile)
        check("picture with real width", html.contains("""<img class="docimg" src="word_media_logo.png" alt="" style="width:100.0px">"""))
        check("table streamed", html.contains("<table class=\"doc\"><tr><td"))
        check("cell shading", html.contains("style=\"background:#D9D9D9\""))
        check("merged cell", html.contains("colspan=\"2\""))
        check("page break", html.contains("""<div class="pgbrk"></div>"""))
        check("trailing paragraph", html.contains("<p>Tail</p>"))
        check("complete file is not flagged", !html.contains("could not read all of this file"))
    }

    // Long documents are cut into capped chunks before they reach the WebView.
    val longDocx = File(tmp, "long.docx")
    val longBody = buildString {
        repeat(40) {
            append("""<w:p><w:r><w:t>Paragraph number $it with filler text to make this block longer than a hundred characters in total.</w:t></w:r></w:p>""")
        }
    }
    zipTo(
        longDocx,
        listOf(
            "word/document.xml" to
                """<?xml version="1.0"?><w:document xmlns:w="w"><w:body>$longBody</w:body></w:document>""",
        ),
    )
    ZipFile(longDocx).use { z ->
        val chunks = Docx.html(z, File(tmp, "long-media"), 300)
        check("long docx split into chunks", chunks.size > 1)
        check("long docx chunks bounded", chunks.all { it.length <= 300 })
        check("long docx keeps content", chunks.joinToString("") { it }.contains("Paragraph number 7"))
    }

    // Void tags have no closing tag: a splitter that keeps them on the stack would stop
    // finding block boundaries after the first one.
    val withRule = Docx.splitTopLevel("<p>a</p><hr><p>b</p><p>c</p>", 10)
    check("void tag does not block splitting", withRule.size == 3)

    // A file that stops mid-element must show what it could read and say so, instead of
    // looking like a document that genuinely ended there.
    val broken = File(tmp, "broken.docx")
    zipTo(
        broken,
        listOf(
            "word/document.xml" to
                """<?xml version="1.0"?><w:document xmlns:w="w"><w:body>
                   <w:p><w:r><w:t>Good</w:t></w:r></w:p>
                   <w:p><w:r><w:t>Cut"""
        ),
    )
    ZipFile(broken).use { z ->
        val html = Docx.html(z, File(tmp, "broken-media")).first()
        check("damaged docx keeps readable text", html.contains("<p>Good</p>"))
        check("damaged docx warns", html.contains("could not read all of this file"))
    }

    // ---- XLSX ----------------------------------------------------------------
    println("xlsx")
    val xlsx = File(tmp, "sample.xlsx")
    zipTo(
        xlsx,
        listOf(
            "xl/workbook.xml" to
                """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                   <sheets><sheet name="Data" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to
                """<Relationships><Relationship Id="rId1"
                   Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
                   Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/styles.xml" to
                """<styleSheet><fonts><font><sz val="11"/></font><font><b/><sz val="11"/></font></fonts>
                   <fills/><borders/><cellStyleXfs count="1"><xf numFmtId="0" fontId="0"/></cellStyleXfs>
                   <cellXfs count="2"><xf numFmtId="0" fontId="0"/><xf numFmtId="0" fontId="1"/></cellXfs>
                   </styleSheet>""",
            "xl/sharedStrings.xml" to
                """<sst><si><t>Ada</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to
                """<worksheet><cols><col min="1" max="1" width="18"/></cols><sheetData>
                   <row r="1"><c r="A1" t="s" s="1"><v>0</v></c><c r="B1"><v>7</v></c></row>
                   <row r="2"><c r="A2"><v>42</v></c><c r="C2" t="s"><v>0</v></c></row>
                   </sheetData></worksheet>""",
        ),
    )
    check("sniff xlsx", Sniff.detect(xlsx, "sample.xlsx") == Fmt.XLSX)
    ZipFile(xlsx).use { z ->
        val sheets = Ooxml.xlsx(z)
        check("one sheet", sheets.size == 1)
        check("sheet name from workbook", sheets.firstOrNull()?.title == "Data")
        val body = sheets.firstOrNull()?.body ?: ""
        check("column letters", body.contains("<th>A</th><th>B</th><th>C</th>"))
        check("row numbers", body.contains("<tr><th>1</th>") && body.contains("<tr><th>2</th>"))
        check("bold header from styles", body.contains("""<td class="b">Ada</td>"""))
        check("numbers right aligned", body.contains("""<td class="n">42</td>"""))
        check("column width from cols", body.contains("""<col style="width:18ch">"""))
        check("gap filled for C column", body.contains("""<td class="n">42</td><td></td><td>Ada</td>"""))
    }

    // A single cell far down the sheet must not expand into thousands of blank rows.
    val farXlsx = File(tmp, "far.xlsx")
    zipTo(
        farXlsx,
        listOf(
            "xl/workbook.xml" to
                """<workbook xmlns:r="r"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to
                """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"
                   Type="worksheet"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to
                """<worksheet><sheetData>
                   <row r="1"><c r="A1"><v>1</v></c></row>
                   <row r="200000"><c r="A200000"><v>2</v></c></row>
                   </sheetData></worksheet>""",
        ),
    )
    ZipFile(farXlsx).use { z ->
        val body = Ooxml.xlsx(z).first().body
        check("far row gap summarised", body.contains("empty rows not shown"))
        check("far row gap stays small", body.length < 20_000)
    }

    // ---- PPTX ----------------------------------------------------------------
    println("pptx")
    val pptx = File(tmp, "deck.pptx")
    val slideMedia = File(tmp, "slide-media")
    zipTo(
        pptx,
        listOf(
            "ppt/presentation.xml" to
                """<p:presentation xmlns:p="p"><p:sldSz cx="12192000" cy="6858000"/></p:presentation>""",
            "ppt/slides/slide1.xml" to
                """<p:sld xmlns:p="p" xmlns:a="a" xmlns:r="r"><p:cSld>
                   <p:bg><p:bgPr><a:solidFill><a:srgbClr val="123456"/></a:solidFill></p:bgPr></p:bg>
                   <p:spTree>
                     <p:pic><p:nvPicPr><p:cNvPr id="2" name="Logo"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
                       <p:blipFill><a:blip r:embed="rId2"/></p:blipFill>
                       <p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1219200" cy="685800"/></a:xfrm></p:spPr>
                     </p:pic>
                     <p:sp><p:nvSpPr><p:cNvPr id="3" name="Title"/><p:cNvSpPr/><p:nvPr>
                         <p:ph type="title"/></p:nvPr></p:nvSpPr>
                       <p:spPr/>
                       <p:txBody><a:bodyPr/><a:p><a:pPr algn="ctr"/>
                         <a:r><a:rPr sz="4000" b="1"/><a:t>Big title</a:t></a:r></a:p></p:txBody>
                     </p:sp>
                     <p:sp><p:nvSpPr><p:cNvPr id="4" name="Box"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
                       <p:spPr><a:xfrm><a:off x="1219200" y="685800"/><a:ext cx="2438400" cy="685800"/></a:xfrm>
                         <a:solidFill><a:srgbClr val="EEEEEE"/></a:solidFill></p:spPr>
                       <p:txBody><a:bodyPr/><a:p><a:r><a:rPr sz="1800"/><a:t>Body copy</a:t></a:r></a:p></p:txBody>
                     </p:sp>
                     <p:grpSp><p:nvGrpSpPr><p:cNvPr id="5" name="Group"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                       <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="2000000" cy="2000000"/>
                         <a:chOff x="0" y="0"/><a:chExt cx="1000000" cy="1000000"/></a:xfrm></p:grpSpPr>
                       <p:sp><p:nvSpPr><p:cNvPr id="6" name="Inside"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
                         <p:spPr><a:xfrm><a:off x="100000" y="100000"/><a:ext cx="100000" cy="100000"/></a:xfrm></p:spPr>
                       </p:sp>
                     </p:grpSp>
                   </p:spTree></p:cSld></p:sld>""",
            "ppt/slides/_rels/slide1.xml.rels" to
                """<Relationships xmlns="r">
                   <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
                   <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/pic1.png"/>
                   </Relationships>""",
            "ppt/slideLayouts/slideLayout1.xml" to
                """<p:sldLayout xmlns:p="p" xmlns:a="a"><p:cSld><p:spTree>
                   <p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr>
                       <p:ph type="title"/></p:nvPr></p:nvSpPr>
                     <p:spPr><a:xfrm><a:off x="457200" y="274320"/><a:ext cx="8229600" cy="1143000"/></a:xfrm></p:spPr>
                   </p:sp>
                   </p:spTree></p:cSld></p:sldLayout>""",
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to
                """<Relationships><Relationship Id="rId1"
                   Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster"
                   Target="../slideMasters/slideMaster1.xml"/></Relationships>""",
            "ppt/slideMasters/slideMaster1.xml" to
                """<p:sldMaster xmlns:p="p"><p:cSld><p:spTree/></p:cSld></p:sldMaster>""",
        ),
        bin = listOf("ppt/media/pic1.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)),
    )
    check("sniff pptx", Sniff.detect(pptx, "deck.pptx") == Fmt.PPTX)
    ZipFile(pptx).use { z ->
        val slides = Pptx.load(z, slideMedia)
        check("slide count", slides.size == 1)
        val slide = slides.firstOrNull()?.body ?: ""
        check("canvas with aspect ratio", slide.contains("""<div class="slide" style="padding-top:56.25%"""))
        check("slide background", slide.contains("background:#123456"))
        check("picture extracted", File(slideMedia, "ppt_media_pic1.png").isFile)
        check("picture references file", slide.contains("""<img src="ppt_media_pic1.png" alt="">"""))
        check("picture at slide origin", slide.contains("""left:0.00%;top:0.00%;width:10.00%;height:10.00%"""))
        check("placeholder inherits layout box", slide.contains("""left:3.75%;top:4.00%;width:67.50%;height:16.67%"""))
        check("run size scaled to viewport", slide.contains("font-size:4.17vw"))
        check("bold run kept", slide.contains("font-weight:600"))
        check("centred paragraph", slide.contains("text-align:center"))
        check("group scale applied", slide.contains("""left:1.64%;top:2.92%;width:1.64%;height:2.92%"""))
        check("shape fill kept", slide.contains("background:#EEEEEE"))

        val page = Html.page(slide, true, 17, true, "deck")
        check("deck page class", page.contains("<body class=\"deck\">"))
        check("slide canvas css", page.contains(".slide{position:relative"))
        check("deck css balanced", page.count { it == '{' } == page.count { it == '}' })
    }

    // ---- ODT -----------------------------------------------------------------
    println("odt")
    val odt = File(tmp, "sample.odt")
    val odtMedia = File(tmp, "odt-media")
    zipTo(
        odt,
        listOf(
            "mimetype" to "application/vnd.oasis.opendocument.text",
            "styles.xml" to
                """<?xml version="1.0"?><office:document-styles
                   xmlns:office="o" xmlns:style="s" xmlns:fo="f" xmlns:text="t">
                   <office:styles>
                     <style:style style:name="Quote" style:family="paragraph">
                       <style:paragraph-properties fo:text-align="center" fo:margin-left="1in"/>
                     </style:style>
                     <style:style style:name="Strong" style:family="text">
                       <style:text-properties fo:font-weight="bold" fo:color="#C00000"/>
                     </style:style>
                   </office:styles></office:document-styles>""",
            "content.xml" to
                """<?xml version="1.0"?><office:document-content
                   xmlns:office="o" xmlns:text="t" xmlns:style="s" xmlns:table="tb"
                   xmlns:draw="d" xmlns:xlink="x" xmlns:svg="v" xmlns:fo="f">
                   <office:automatic-styles>
                     <style:style style:name="P1" style:family="paragraph">
                       <style:paragraph-properties fo:background-color="#FFF2CC" fo:text-align="justify"/>
                     </style:style>
                   </office:automatic-styles>
                   <office:body><office:text>
                     <text:h text:outline-level="2">Section</text:h>
                     <text:p text:style-name="P1">Hello <text:span text:style-name="Strong">there</text:span>.</text:p>
                     <text:p text:style-name="Quote">Centred</text:p>
                     <text:list><text:list-item><text:p>Item</text:p></text:list-item></text:list>
                     <text:p><draw:frame><svg:width>4cm</svg:width><svg:height>2cm</svg:height>
                       <draw:image xlink:href="Pictures/pic.png"/></draw:frame></text:p>
                     <table:table>
                       <table:table-row>
                         <table:table-cell table:number-columns-spanned="2"><text:p>Wide</text:p></table:table-cell>
                         <table:table-cell><text:p>B</text:p></table:table-cell>
                       </table:table-row>
                     </table:table>
                   </office:text></office:body></office:document-content>""",
        ),
        listOf("Pictures/pic.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9)),
    )
    check("sniff odt", Sniff.detect(odt, "sample.odt") == Fmt.ODT)
    ZipFile(odt).use { z ->
        val html = Odt.html(z, odtMedia).first()
        check("heading level", html.contains("<h2>Section</h2>"))
        check("paragraph with styled run", html.contains("""Hello <span style="color:#C00000;"><b>there</b></span>."""))
        check("named text style", html.contains("color:#C00000"))
        check("automatic paragraph style", html.contains("""<p style="text-align:justify;background:#FFF2CC;">"""))
        check("named paragraph style", html.contains("""<p style="text-align:center;margin-left:1in;">"""))
        check("list", html.contains("<ul><li><p>Item</p></li></ul>"))
        check("picture extracted", File(odtMedia, "Pictures_pic.png").isFile)
        check("picture size", html.contains("""style="width:4cm;height:2cm;""""))
        check("table with span", html.contains("""<table class="doc"><tr><td colspan="2">""") &&
            html.contains("<td><p>B</p></td></tr></table>"))
    }

    // ---- MOBI ----------------------------------------------------------------
    println("mobi")
    for (compressed in listOf(false, true)) {
        val mobi = File(tmp, if (compressed) "c.mobi" else "u.mobi")
        buildMobi(mobi, "<html><body><mbp:pagebreak/><p>Hello MOBI</p><p>second</p></body></html>", compressed)
        check("sniff mobi (${if (compressed) "lz77" else "stored"})", Sniff.detect(mobi, "x.mobi") == Fmt.MOBI)
        val text = Mobi.text(mobi)
        check(
            "decode (${if (compressed) "lz77" else "stored"})",
            text.contains("Hello MOBI") && text.contains("second")
        )
    }

    // ---- plain text ----------------------------------------------------------
    println("text")
    val txt = File(tmp, "notes.md")
    txt.writeText("# Title\n\nBody.\n")
    check("sniff markdown by name", Sniff.detect(txt, "notes.md") == Fmt.MARKDOWN)
    val txt2 = File(tmp, "notes.txt")
    txt2.writeText("just some words\n")
    check("sniff text", Sniff.detect(txt2, "notes.txt") == Fmt.TEXT)
    val rtf = File(tmp, "old.rtf")
    rtf.writeText("{\\rtf1\\ansi Hi\\par there}")
    check("sniff rtf", Sniff.detect(rtf, "old.rtf") == Fmt.RTF)
    val csv = File(tmp, "data.csv")
    csv.writeText("name,count\nada,7\n")
    check("sniff csv", Sniff.detect(csv, "data.csv") == Fmt.TEXT)
    val utf16 = File(tmp, "utf16.txt")
    utf16.writeBytes(
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hello".toByteArray(Charsets.UTF_16LE)
    )
    check("utf16 txt is text", Sniff.detect(utf16, "utf16.txt") == Fmt.TEXT)

    // ---- supported format policy ---------------------------------------------
    println("supported formats")
    for (name in listOf("a.pdf", "a.DOCX", "a.txt", "a.xlsx", "a.pptx", "a.odt", "a.rtf", "a.md", "a.csv")) {
        check("$name accepted", Sniff.supportedName(name))
    }
    for (name in listOf("a.epub", "a.mobi", "a.azw3", "a.cbz", "a.html", "a.png", "a.zip")) {
        check("$name refused", !Sniff.supportedName(name))
    }
    check("extensionless name is sniffed", Sniff.supportedName("README"))
    check("markdown long name accepted", Sniff.supportedName("a.markdown"))
    check("epub is not renderable", !Sniff.renderable(Fmt.EPUB))
    check("mobi is not renderable", !Sniff.renderable(Fmt.MOBI))
    check("cbz is not renderable", !Sniff.renderable(Fmt.CBZ))
    check("text is renderable", Sniff.renderable(Fmt.TEXT))
    check("markdown is renderable", Sniff.renderable(Fmt.MARKDOWN))

    // ---- palmDoc round trip --------------------------------------------------
    println("palmdoc round trip")
    val original = ByteArray(400) { (32 + it % 90).toByte() }
    check("decompress(literalCompress(x)) == x", PalmDoc.decompress(literalCompress(original), 4096).contentEquals(original))

    tmp.deleteRecursively()

    if (failures > 0) {
        println("\n$failures check(s) failed")
        kotlin.system.exitProcess(1)
    }
    println("\nall format checks passed")
}
