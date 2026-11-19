package io.herbert.leaf

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.Locale

/**
 * Home screen: a pick button and the list of recently opened documents.
 */
class MainActivity : Activity() {

    private var dark = true
    private lateinit var root: LinearLayout
    private lateinit var pickButton: TextView
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var clearAll: TextView
    private lateinit var adapter: RecentAdapter
    private val items = ArrayList<Recent>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = Prefs.dark(this)
        Ui.systemBars(this, dark)
        build()
        // Cached copies and unpacked media outlive the recent list; trim them once the
        // screen is up, off the main thread.
        Docs.pruneAsync(applicationContext)
    }

    private fun d(v: Int) = Ui.dp(this, v)

    private fun build() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg(dark))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(24), d(26), d(24), d(6))
        }
        header.addView(Ui.label(this, 13f, Ui.accent(dark), "LEAF").apply {
            letterSpacing = 0.22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        header.addView(Ui.label(this, 30f, Ui.text(dark), "Read documents").apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        header.addView(Ui.label(this, 13f, Ui.dim(dark), "Opens instantly. Nothing leaves your phone.").apply {
            setPadding(0, d(2), 0, 0)
        })

        pickButton = TextView(this).apply {
            text = "Open a file"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (dark) 0xFF062018.toInt() else 0xFFFFFFFF.toInt())
            background = Ui.round(Ui.accent(dark), 14f, this@MainActivity)
            setPadding(0, d(15), 0, d(15))
            isClickable = true
            setOnClickListener { pick() }
        }

        val recentLabel = TextView(this).apply {
            text = "RECENT"
            textSize = 11f
            letterSpacing = 0.14f
            setTextColor(Ui.dim(dark))
        }

        // Sits on the same line as the RECENT heading, right aligned, and only appears
        // when there is something to clear.
        clearAll = TextView(this).apply {
            text = "Clear all"
            textSize = 13f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(Ui.accent(dark))
            // Vertical padding makes the tap target comfortable without moving the text.
            setPadding(d(12), d(8), 0, d(8))
            isClickable = true
            visibility = View.GONE
            setOnClickListener { confirmClearAll() }
        }

        val recentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(24), 0, d(24), 0)
            addView(recentLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(clearAll, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

        adapter = RecentAdapter()
        list = ListView(this).apply {
            this.adapter = this@MainActivity.adapter
            divider = null
            dividerHeight = 0
            setBackgroundColor(Ui.COLOR_NONE)
            cacheColorHint = Ui.COLOR_NONE
            isVerticalScrollBarEnabled = false
            setOnItemClickListener { _, _, position, _ -> open(items[position]) }
            setOnItemLongClickListener { _, _, position, _ ->
                confirmForget(position)
                true
            }
        }

        empty = Ui.label(this, 14f, Ui.dim(dark), "No recent files yet.\nTap \u201cOpen a file\u201d and pick a PDF,\nWord document, spreadsheet or text file.").apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setLineSpacing(d(4).toFloat(), 1f)
        }

        val listHost = FrameLayout(this)
        listHost.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        listHost.addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))

        val footer = Ui.label(
            this, 11f, Ui.dim(dark),
            "PDF \u00b7 DOCX \u00b7 TXT \u00b7 XLSX \u00b7 PPTX \u00b7 ODT \u00b7 RTF \u00b7 MD \u00b7 CSV"
        ).apply {
            gravity = Gravity.CENTER
            setPadding(d(16), d(10), d(16), d(14))
        }

        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(pickButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(d(20), d(16), d(20), d(18))
        })
        root.addView(recentRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(listHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // The reader can flip the theme; rebuild the home screen to match.
        if (Prefs.dark(this) != dark) {
            dark = Prefs.dark(this)
            Ui.systemBars(this, dark)
            recreate()
            return
        }
        refresh()
    }

    private fun refresh() {
        items.clear()
        val saved = Docs.recents(this)
        // Entries saved before Leaf stopped reading EPUBs, comics and the like would only
        // ever produce the "not a format Leaf opens" screen. They are dropped from the
        // saved list (and their cached copies with them) instead of being shown broken.
        val usable = saved.filter { Sniff.supportedName(it.name) }
        if (usable.size != saved.size) {
            Docs.keep(this, usable)
            Docs.pruneAsync(applicationContext)
        }
        items.addAll(usable)
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        clearAll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun pick() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // EXTRA_MIME_TYPES needs "*/*" as the base type, then filters the picker to
            // the formats Leaf reads. Anything offered in an "Open with" sheet is
            // filtered by the manifest instead.
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, Sniff.MIME_TYPES)
        }
        try {
            startActivityForResult(intent, REQ_OPEN)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No file picker found on this device", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OPEN || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Not every provider makes its grant persistable; the cached copy covers it.
        }
        val name = Docs.displayName(this, uri)
        // Only files Leaf can open become recents; picking an .epub or a video should
        // not leave an entry that will refuse to open next time.
        if (Sniff.supportedName(name)) Docs.remember(this, uri, name)
        open(Recent(uri.toString(), name, System.currentTimeMillis()))
    }

    private fun open(recent: Recent) {
        val uri = Uri.parse(recent.uri)
        // No FLAG_GRANT_READ_URI_PERMISSION here: ReaderActivity runs in this app's own
        // UID, so the flag adds no access. When the provider's grant has lapsed (app
        // reinstalled, Android's unused-app cleanup, provider reset) the system rejects
        // the launch with a SecurityException before the reader can fall back to the
        // cached copy Leaf keeps for exactly this case.
        try {
            startActivity(
                Intent(this, ReaderActivity::class.java)
                    .setData(uri)
                    // The name saved with the entry, so a lost provider grant cannot hide
                    // the extension (and with it the Markdown/CSV handling).
                    .putExtra(ReaderActivity.EXTRA_NAME, recent.name)
            )
        } catch (t: Throwable) {
            val dlg = Ui.dialogContext(this, dark)
            AlertDialog.Builder(dlg, Ui.dialogTheme(dark))
                .setTitle("Cannot open")
                .setMessage("${recent.name}\n\nLeaf could not start the reader for this file.\n\nRemove it from the list and pick it again.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun confirmClearAll() {
        if (items.isEmpty()) return
        val count = items.size
        val dlg = Ui.dialogContext(this, dark)
        AlertDialog.Builder(dlg, Ui.dialogTheme(dark))
            .setTitle("Clear all recents?")
            .setMessage(
                if (count == 1) "The one file on this list is removed.\n\nThe file itself is not deleted."
                else "These $count files are removed from the list.\n\nThe files themselves are not deleted."
            )
            .setPositiveButton("Clear all") { _, _ ->
                Docs.clearRecents(this)
                refresh()
                Toast.makeText(this, "Recents cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmForget(position: Int) {
        val recent = items.getOrNull(position) ?: return
        val dlg = Ui.dialogContext(this, dark)
        AlertDialog.Builder(dlg, Ui.dialogTheme(dark))
            .setTitle("Remove from recents?")
            .setMessage(recent.name)
            .setPositiveButton("Remove") { _, _ ->
                Docs.forget(this, recent.uri)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class RecentAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(d(24), d(10), d(24), d(10))
                addView(Ui.bold(this@MainActivity, 15f, Ui.text(dark)))
                addView(Ui.label(this@MainActivity, 12f, Ui.dim(dark)))
            }
            val name = row.getChildAt(0) as TextView
            val meta = row.getChildAt(1) as TextView
            val item = items[position]

            name.setTextColor(Ui.text(dark))
            meta.setTextColor(Ui.dim(dark))
            name.text = item.name
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            meta.text = "opened " + when {
                item.last <= 0L -> "recently"
                System.currentTimeMillis() - item.last < 60_000 -> "just now"
                System.currentTimeMillis() - item.last < 3_600_000 ->
                    "${(System.currentTimeMillis() - item.last) / 60_000} min ago"
                System.currentTimeMillis() - item.last < 86_400_000 ->
                    "${(System.currentTimeMillis() - item.last) / 3_600_000} h ago"
                else -> java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(item.last))
            }
            return row
        }
    }

    private companion object {
        const val REQ_OPEN = 1001
    }
}
