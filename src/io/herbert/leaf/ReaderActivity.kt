package io.herbert.leaf

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import io.herbert.leaf.core.Chunker
import io.herbert.leaf.core.Markdown
import io.herbert.leaf.core.Rtf
import io.herbert.leaf.core.TextCodec
import io.herbert.leaf.format.Chapter
import io.herbert.leaf.format.Docx
import io.herbert.leaf.format.Odt
import io.herbert.leaf.format.Ooxml
import io.herbert.leaf.format.Pptx
import io.herbert.leaf.reader.HtmlReader
import io.herbert.leaf.reader.PageKind
import io.herbert.leaf.reader.PageSource
import io.herbert.leaf.reader.PagerReader
import io.herbert.leaf.reader.PdfSource
import io.herbert.leaf.reader.TextReader
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * HTML a single Word/OpenDocument chapter is allowed to reach: enough for a long
 * chapter, small enough that the WebView never holds a whole book at once.
 */
private const val DOC_CHARS = 200_000

/**
 * The reading screen. Parsing and copying happen on a background thread; only the
 * finished, lightweight views are built on the UI thread.
 */
class ReaderActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var bottomHost: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var backView: TextView
    private lateinit var menuView: TextView

    private var reader: Reader? = null
    private var dark = true
    private var fontPx = 17

    private var docName = ""
    private var docFormat: Fmt = Fmt.UNKNOWN
    private var docSize = 0L
    private var docPath = ""

    /** Name supplied by the recents list, used when the provider can no longer tell us. */
    private var nameHint: String? = null

    /** Bumped for every open; a result that arrives late is discarded, not shown. */
    private var generation = 0
    private var destroyed = false

    private lateinit var searchBar: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var searchCount: TextView
    private val searchButtons = ArrayList<TextView>()
    private val searchDelay = Handler(Looper.getMainLooper())
    private var searchQuery = ""

    private val runSearch = Runnable {
        val q = searchField.text?.toString().orEmpty()
        searchQuery = q
        val r = reader
        if (r == null) return@Runnable
        if (q.trim().isEmpty()) {
            r.clearSearch()
            searchCount.text = ""
            return@Runnable
        }
        r.search(q) { current, total ->
            searchCount.text = when {
                total <= 0 -> "0"
                else -> "$current/$total"
            }
        }
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "leaf-open") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = Prefs.dark(this)
        fontPx = Prefs.fontPx(this)
        Ui.systemBars(this, dark)

        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        // Remember files opened from other apps too, when the provider allows it.
        rememberGrant(uri)
        nameHint = intent?.getStringExtra(EXTRA_NAME)
        buildChrome()
        open(uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                object : OnBackInvokedCallback {
                    override fun onBackInvoked() = handleBack()
                },
            )
        }
    }

    /**
     * Back closes the find bar when it is open and leaves the document otherwise. The
     * platform only routes Back through [OnBackInvokedCallback] on API 33+ (and only
     * when the manifest enables it), so API 26-32 come through [onBackPressed] instead.
     */
    private fun handleBack() {
        if (::searchBar.isInitialized && searchBar.visibility == View.VISIBLE) {
            closeSearch()
            hideKeyboard()
            return
        }
        finish()
    }

    @Deprecated("Superseded by the OnBackInvokedCallback registered on API 33+")
    override fun onBackPressed() {
        handleBack()
    }

    private fun d(v: Int) = Ui.dp(this, v)

    private fun buildChrome() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg(dark))
        }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.surface(dark))
            setPadding(d(4), 0, d(4), 0)
        }

        backView = TextView(this).apply {
            text = "\u2039"
            textSize = 26f
            setTextColor(Ui.text(dark))
            // Wide enough for a full thumb-sized tap target.
            setPadding(d(12), 0, d(16), 0)
            isClickable = true
            setOnClickListener { finish() }
            contentDescription = "Back"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        titleView = TextView(this).apply {
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextColor(Ui.text(dark))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        menuView = TextView(this).apply {
            text = "\u22ee"
            textSize = 21f
            setTextColor(Ui.text(dark))
            // Wide enough for a full thumb-sized tap target.
            setPadding(d(16), 0, d(20), 0)
            isClickable = true
            setOnClickListener { showMenu() }
            contentDescription = "Menu"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        topBar.addView(backView, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        topBar.addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        topBar.addView(menuView, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))

        content = FrameLayout(this)

        bottomHost = FrameLayout(this)

        searchBar = buildSearchBar()

        root.addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, d(52)))
        root.addView(searchBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(bottomHost, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
    }

    // ---- search bar ------------------------------------------------------------

    private fun buildSearchBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.surface(dark))
            setPadding(d(12), d(2), d(6), d(2))
            visibility = View.GONE
        }

        searchField = EditText(this).apply {
            hint = "Search"
            textSize = 15f
            setSingleLine(true)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Ui.text(dark))
            setHintTextColor(Ui.dim(dark))
            setPadding(0, d(6), d(8), d(6))
        }

        searchCount = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.dim(dark))
            setPadding(d(4), 0, d(8), 0)
            minWidth = d(44)
            gravity = Gravity.CENTER
        }

        val prev = searchButton("\u2039", "Previous match") { reader?.findNext(false) }
        val next = searchButton("\u203a", "Next match") { reader?.findNext(true) }
        val close = searchButton("\u2715", "Close search") {
            closeSearch()
            hideKeyboard()
        }

        bar.addView(searchField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(searchCount, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        bar.addView(prev, LinearLayout.LayoutParams(d(44), d(44)))
        bar.addView(next, LinearLayout.LayoutParams(d(44), d(44)))
        bar.addView(close, LinearLayout.LayoutParams(d(44), d(44)))

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchDelay.removeCallbacks(runSearch)
                searchDelay.postDelayed(runSearch, 220)
            }
        })

        searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchDelay.removeCallbacks(runSearch)
                runSearch.run()
                hideKeyboard()
                true
            } else {
                false
            }
        }

        return bar
    }

    private fun searchButton(glyph: String, contentDescription: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Ui.text(dark))
            isClickable = true
            setOnClickListener { onClick() }
            this.contentDescription = contentDescription
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            searchButtons.add(this)
        }

    private fun openSearch() {
        val r = reader ?: return
        if (!r.searchable) return
        searchBar.visibility = View.VISIBLE
        searchField.requestFocus()
        searchField.setSelection(searchField.text.length)
        // The bar was GONE a moment ago; post so the IME can attach to a laid-out view.
        searchField.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeSearch() {
        searchDelay.removeCallbacks(runSearch)
        searchQuery = ""
        searchBar.visibility = View.GONE
        searchField.setText("")
        searchCount.text = ""
        reader?.clearSearch()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchField.windowToken, 0)
        searchField.clearFocus()
    }

    // ---- opening ---------------------------------------------------------------

    private fun open(uri: Uri) {
        val gen = ++generation
        titleView.text = "Opening\u2026"
        io.execute {
            val outcome = try {
                Result.success(prepare(uri))
            } catch (t: Throwable) {
                Result.failure<Prepared>(t)
            }
            runOnUiThread {
                // Leaving the screen (or opening another file) while the copy/parse is
                // still running must not build a reader that nobody can close.
                if (destroyed || gen != generation) {
                    outcome.getOrNull()?.release()
                    return@runOnUiThread
                }
                outcome.fold({ show(it) }, { fail(it) })
            }
        }
    }

    private fun prepare(uri: Uri): Prepared {
        val hinted = nameHint
        val name = if (!hinted.isNullOrBlank()) hinted else Docs.displayName(this, uri)
        docName = name

        // Only the formats Leaf renders are copied at all. Checking the name first means
        // picking a video or an archive costs nothing.
        if (!Sniff.supportedName(name)) {
            docPath = ""
            // File info still gets a size; asking the provider is one cheap call and
            // nothing is copied.
            docSize = try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            } catch (_: Exception) {
                0L
            }
            docFormat = Fmt.UNKNOWN
            return unsupported(name, wrongContents = false)
        }

        val file = Docs.materialize(this, uri, name)
        docPath = file.absolutePath
        docSize = file.length()

        // The name decides Markdown and code-font handling, and the bytes decide the
        // rest; a file whose name lost its extension is still recognised if its bytes
        // say PDF, ZIP or text.
        val fmt = Sniff.detect(file, name)
        docFormat = fmt
        // A .txt that is really an EPUB, or a .pdf that is really a ZIP, is reported
        // instead of being handed to a renderer the user did not ask for.
        if (!Sniff.renderable(fmt)) return unsupported(name, wrongContents = true)
        if (file.length() > 0) Docs.remember(this, uri, name)

        return when (fmt) {
            Fmt.PDF -> Prepared.Pager(PdfSource(file))

            // Word and OpenDocument text get a white page with pictures, tables and the
            // paragraph layout the file asks for.
            Fmt.DOCX -> {
                val media = File(cacheDir, "docx/" + Docs.shortHash(file.absolutePath))
                Prepared.Web(
                    ZipFile(file).use { z -> parts(name, Docx.html(z, media, DOC_CHARS)) },
                    "file://" + media.absolutePath + "/",
                    PageKind.DOC,
                )
            }

            Fmt.ODT -> {
                val media = File(cacheDir, "odt/" + Docs.shortHash(file.absolutePath))
                Prepared.Web(
                    ZipFile(file).use { z -> parts(name, Odt.html(z, media, DOC_CHARS)) },
                    "file://" + media.absolutePath + "/",
                    PageKind.DOC,
                )
            }

            Fmt.XLSX -> Prepared.Web(
                ZipFile(file).use { Ooxml.xlsx(it) }, null
            )

            // Slides are drawn as white canvases with position, pictures and text kept,
            // so a deck looks like the deck instead of a list of sentences.
            Fmt.PPTX -> {
                val media = File(cacheDir, "slides/" + Docs.shortHash(file.absolutePath))
                Prepared.Web(
                    ZipFile(file).use { Pptx.load(it, media) },
                    "file://" + media.absolutePath + "/",
                    PageKind.DECK,
                )
            }

            Fmt.MARKDOWN -> {
                val (text, cut) = readText(file)
                Prepared.Web(listOf(Chapter(name, Markdown.toHtml(text) + truncated(cut))), null)
            }

            Fmt.RTF -> {
                val (text, cut) = readText(file)
                Prepared.Plain(Rtf.toText(text) + truncated(cut), false)
            }

            Fmt.TEXT -> {
                val (text, cut) = readText(file)
                if (text.isBlank()) Prepared.Plain("This file is empty.", false)
                else Prepared.Plain(text + truncated(cut), looksLikeCode(name))
            }

            // Kept exhaustive without offering EPUB, MOBI, comic or archive rendering.
            else -> unsupported(name, wrongContents = true)
        }
    }

    /**
     * What the reader shows for a file outside the supported set. [wrongContents] means
     * the extension was fine but the bytes were not, which usually means a renamed or
     * damaged file.
     */
    private fun unsupported(name: String, wrongContents: Boolean): Prepared.Plain {
        val ext = Sniff.extension(name)
        val what = if (ext.isEmpty()) "This file" else "This .$ext file"
        val message = if (wrongContents) {
            "$what does not contain a document Leaf can read.\n\n" +
                "It may be damaged, or renamed from another format."
        } else {
            "$what is not a format Leaf opens.\n\n" +
                "Leaf reads PDF (.pdf), DOCX (.docx), TXT (.txt), XLSX (.xlsx), " +
                "PPTX (.pptx), ODT (.odt), RTF (.rtf), Markdown (.md) and CSV (.csv)."
        }
        return Prepared.Plain(message, false)
    }

    private fun show(prepared: Prepared) {
        val r: Reader = when (prepared) {
            is Prepared.Pager -> PagerReader(this, prepared.source, dark)
            is Prepared.Web -> HtmlReader(
                this, prepared.chapters, prepared.base, dark, fontPx, prepared.kind
            )
            is Prepared.Plain -> TextReader(this, Chunker.split(prepared.text), dark, fontPx, prepared.mono)
        }

        // A new document starts with a clean search state, and resumes where it left off.
        if (docPath.isNotEmpty()) r.positionKey = "leaf/" + Docs.shortHash(docPath)
        val key = r.positionKey
        val saved = key?.let { Prefs.position(this, it) } ?: -1

        releaseReader()
        reader = r

        content.addView(r.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        r.controls?.let { bottomHost.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

        if (key != null) r.restorePosition(if (saved >= 0) saved else r.position())

        titleView.text = docName
        // A new document starts with a clean search state.
        if (::searchBar.isInitialized && searchBar.visibility == View.VISIBLE) {
            closeSearch()
            hideKeyboard()
        }
    }

    /**
     * Detach views before tearing the reader down: destroying a WebView while it is
     * still in the hierarchy is a known source of crashes.
     */
    private fun releaseReader() {
        content.removeAllViews()
        bottomHost.removeAllViews()
        val old = reader
        val key = old?.positionKey
        val pos = old?.position() ?: 0
        reader = null
        old?.close()
        // Remember where the reader was, so the next open resumes here.
        if (key != null) Prefs.savePosition(this, key, pos)
    }

    private fun fail(t: Throwable) {
        val raw = t.message ?: t.javaClass.simpleName
        val message = when {
            raw.contains("cannot be opened", ignoreCase = true) ->
                "Leaf could not read this file.\n\nThere is no stored copy left, and the " +
                    "app that owns the file can no longer open it. The file may have been " +
                    "moved or deleted, or Android may have cleared Leaf's cache of it.\n\n" +
                    "Pick the file again from the home screen."
            raw.contains("DRM", ignoreCase = true) -> raw
            raw.contains("HUFF", ignoreCase = true) -> raw
            else -> raw
        }
        titleView.text = docName.ifEmpty { "Could not open" }
        releaseReader()
        content.addView(
            TextView(this).apply {
                text = "Could not open this file.\n\n$message"
                textSize = 15f
                setTextColor(Ui.dim(dark))
                setPadding(d(24), d(32), d(24), d(24))
            },
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )
    }

    // ---- menu ------------------------------------------------------------------

    private fun showMenu() {
        val r = reader
        // The activity theme is Material (dark); a light-theme reader needs a light menu.
        val menu = PopupMenu(Ui.chromeContext(this, dark), menuView)
        if (r != null) {
            // Search lives here rather than in the toolbar: one icon fewer, and it is
            // the first thing in the menu because it is the thing people reach for.
            if (r.searchable) menu.menu.add(0, 7, 0, "Search")
            if (r.textScalable) {
                menu.menu.add(0, 1, 1, "Bigger text")
                menu.menu.add(0, 2, 2, "Smaller text")
            }
            menu.menu.add(0, 3, 3, if (dark) "Light theme" else "Dark theme")
            val contents = r.contents()
            if (!contents.isNullOrEmpty() && contents.size > 1) menu.menu.add(0, 4, 4, "Contents")
            if (r.pager && r.pageCount > 1) menu.menu.add(0, 5, 5, "Go to page\u2026")
        }
        menu.menu.add(0, 6, 6, "File info")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> changeText(1)
                2 -> changeText(-1)
                3 -> toggleTheme()
                4 -> showContents()
                5 -> askPage()
                6 -> showInfo()
                7 -> openSearch()
            }
            true
        }
        menu.show()
    }

    private fun changeText(delta: Int) {
        val next = (fontPx + delta).coerceIn(11, 34)
        if (next == fontPx) return
        fontPx = next
        Prefs.setFontPx(this, fontPx)
        reader?.textScale(delta)
    }

    private fun toggleTheme() {
        dark = !dark
        Prefs.setDark(this, dark)
        Ui.systemBars(this, dark)
        root.setBackgroundColor(Ui.bg(dark))
        topBar.setBackgroundColor(Ui.surface(dark))
        val fg = Ui.text(dark)
        backView.setTextColor(fg)
        menuView.setTextColor(fg)
        titleView.setTextColor(fg)
        if (::searchBar.isInitialized) {
            searchBar.setBackgroundColor(Ui.surface(dark))
            searchField.setTextColor(fg)
            searchField.setHintTextColor(Ui.dim(dark))
            searchCount.setTextColor(Ui.dim(dark))
            for (b in searchButtons) b.setTextColor(fg)
        }
        reader?.themeChanged(dark)
    }

    private fun showContents() {
        val titles = reader?.contents() ?: return
        val dlg = Ui.dialogContext(this, dark)
        AlertDialog.Builder(dlg, Ui.dialogTheme(dark))
            .setTitle("Contents")
            .setItems(titles.toTypedArray()) { _, which -> reader?.goToContents(which) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun askPage() {
        val r = reader ?: return
        // Build the field with the dialog's theme so its text colour matches the dialog.
        val dlg = Ui.dialogContext(this, dark)
        val field = EditText(dlg).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 \u2013 ${r.pageCount}"
            setText((r.currentPage + 1).toString())
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(dlg, Ui.dialogTheme(dark))
            .setTitle("Go to page")
            .setView(field)
            .setPositiveButton("Go") { _, _ ->
                val n = field.text.toString().toIntOrNull() ?: return@setPositiveButton
                r.goToPage(n - 1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The installed build's version, so the info sheet cannot drift from the manifest. */
    private val appVersion: String by lazy {
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun showInfo() {
        val message = buildString {
            append(docName).append("\n\n")
            append("Format: ").append(docFormat.label).append('\n')
            append("Size: ").append(humanSize(docSize)).append('\n')
            append("Pages: ").append(reader?.pageCount?.takeIf { it > 0 } ?: "\u2014").append('\n')
            if (docPath.isNotEmpty()) append("Cached copy: ").append(docPath).append("\n\n")
            else append('\n')
            append(if (appVersion.isEmpty()) "Leaf" else "Leaf $appVersion")
                .append(" \u00b7 no network access, no tracking, no libraries.")
        }
        val dlg = Ui.dialogContext(this, dark)
        AlertDialog.Builder(dlg, Ui.dialogTheme(dark))
            .setTitle("File info")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ---- helpers ---------------------------------------------------------------

    /**
     * Word and OpenDocument files are cut into screen-sized parts by the parser. Name
     * them so Contents lists "Part 2 of 17" instead of seventeen copies of the file name.
     */
    private fun parts(name: String, bodies: List<String>): List<Chapter> =
        if (bodies.size <= 1) bodies.map { Chapter(name, it) }
        else bodies.mapIndexed { i, body -> Chapter("Part ${i + 1} of ${bodies.size}", body) }

    private fun readText(file: File, cap: Int = 12 * 1024 * 1024): Pair<String, Boolean> {
        val take = minOf(file.length(), cap.toLong()).toInt()
        val bytes = ByteArray(take)
        var off = 0
        try {
            file.inputStream().use { ins ->
                while (off < take) {
                    val n = ins.read(bytes, off, take - off)
                    if (n < 0) break
                    off += n
                }
            }
        } catch (_: Throwable) {
        }
        val slice = if (off == bytes.size) bytes else bytes.copyOf(off)
        return TextCodec.decode(slice) to (file.length() > cap)
    }

    private fun truncated(cut: Boolean): String =
        if (cut) "\n\n[Leaf shows the first 12 MB of this file.]" else ""

    private fun looksLikeCode(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "json", "xml", "yml", "yaml", "toml", "ini", "cfg", "conf", "csv", "tsv",
            "log", "kt", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp",
            "rs", "go", "rb", "sh", "bash", "css", "scss", "sql", "gradle", "properties"
        )
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1048576.0)
    }

    override fun onDestroy() {
        destroyed = true
        generation++
        super.onDestroy()
        releaseReader()
        io.shutdownNow()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val uri = intent?.data ?: return
        // Keep the intent in step with what is on screen for the system back stack.
        setIntent(intent)
        // A document opened while the reader is already showing (singleTop) brings a
        // fresh grant; persist it too, or its recents entry would only reach the cache.
        rememberGrant(uri)
        nameHint = intent?.getStringExtra(EXTRA_NAME)
        releaseReader()
        open(uri)
    }

    /** Keeps the provider's read grant across restarts when the provider allows it. */
    private fun rememberGrant(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
            // Not persistable (a one-shot grant) or already held: the cached copy covers it.
        }
    }

    companion object {
        /** Display name carried from the recents list; optional for every other caller. */
        const val EXTRA_NAME = "io.herbert.leaf.EXTRA_NAME"
    }
}

private sealed class Prepared {
    class Pager(val source: PageSource) : Prepared() {
        override fun release() = source.close()
    }

    class Web(
        val chapters: List<Chapter>,
        val base: String?,
        val kind: PageKind = PageKind.TEXT,
    ) : Prepared()

    class Plain(val text: String, val mono: Boolean) : Prepared()

    /** Frees anything that must be closed when the result is thrown away. */
    open fun release() {}
}
