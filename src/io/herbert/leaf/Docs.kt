package io.herbert.leaf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class Recent(val uri: String, val name: String, val last: Long)

/**
 * Document plumbing: display names, opening a picker URI as a real file, and the
 * recent list. Everything here is safe to call off the main thread except the
 * SharedPreferences writes, which are apply() based anyway.
 */
object Docs {

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "document"
        var name: String? = null
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0) }
        } catch (_: Exception) {
        }
        return name
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "document"
    }

    /**
     * Documents arrive as content:// streams, but PDF, ZIP and image readers all want
     * random access, so copy once into the cache and reuse it. The copy is keyed by the
     * URI plus its size, so re-opening is instant.
     */
    fun materialize(ctx: Context, uri: Uri, name: String): File {
        if (uri.scheme == "file") {
            val direct = File(uri.path ?: throw IOException("bad file uri"))
            if (direct.isFile) return direct
        }
        val dir = File(ctx.cacheDir, "docs").apply { mkdirs() }
        val target = cachedCopy(ctx, uri, name)
        val key = target.name.substringBefore('.')

        val knownSize = try {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) {
            -1L
        }
        val stamp = lastModified(ctx, uri)
        // Reuse the cached copy only when the provider confirms it is still the same file.
        // Size alone is not enough: an edited document often keeps its size, and a synced
        // PDF would otherwise show yesterday's pages.
        val sameSize = knownSize <= 0 || target.length() == knownSize
        val sameAge = stamp <= 0 || Math.abs(target.lastModified() - stamp) < 2_000
        if (target.isFile && target.length() > 0 && sameSize && sameAge) return target

        val tmp = File(dir, "$key.part")
        val input = try {
            ctx.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        }
        if (input == null) {
            // The provider grant can lapse without the user doing anything (reinstall,
            // Android's unused-app cleanup, a provider reset). The copy made at first
            // open is still on disk: a recents entry must keep working, so read that
            // instead of reporting a file that "moved".
            cachedCopyFor(ctx, uri)?.let { return it }
            throw IOException("this file cannot be opened")
        }
        try {
            input.use { ins -> tmp.outputStream().use { outs -> ins.copyTo(outs, 128 * 1024) } }
        } catch (t: Throwable) {
            tmp.delete()
            // A copy that fails halfway may be a revoked grant too; the old copy wins.
            cachedCopyFor(ctx, uri)?.let { return it }
            throw IOException("this file cannot be opened", t)
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, true)
            tmp.delete()
        }
        // Stamp the copy with the source's modification time so the check above works
        // next time without storing anything extra.
        if (stamp > 0) target.setLastModified(stamp)
        return target
    }

    /** Where [materialize] keeps its copy of [uri]; also the key its media is unpacked under. */
    fun cachedCopy(ctx: Context, uri: Uri, name: String): File {
        val key = shortHash(uri.toString())
        val ext = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(8).ifBlank { "bin" }
        return File(File(ctx.cacheDir, "docs"), "$key.$ext")
    }

    /**
     * Any finished copy of [uri] on disk, whatever extension the display name gave it.
     * A provider that stops answering `DISPLAY_NAME` changes the extension part of the
     * cache name, but the hash half still identifies the document.
     */
    private fun cachedCopyFor(ctx: Context, uri: Uri): File? {
        val key = shortHash(uri.toString())
        val dir = File(ctx.cacheDir, "docs")
        return dir.listFiles { f ->
            f.isFile && f.length() > 0 && !f.name.endsWith(".part") &&
                f.name.substringBefore('.') == key
        }?.maxByOrNull { it.lastModified() }
    }

    /** The file a document is actually read from once [materialize] has run. */
    fun documentPath(ctx: Context, uri: Uri, name: String): String {
        if (uri.scheme == "file") uri.path?.let { return it }
        return cachedCopy(ctx, uri, name).absolutePath
    }

    private fun lastModified(ctx: Context, uri: Uri): Long {
        // Storage Access Framework providers expose `last_modified`; MediaStore and a
        // few older providers use `date_modified`. Ask for each separately: a provider
        // that does not know a column may reject the whole query.
        for (column in arrayOf("last_modified", "date_modified")) {
            val value = try {
                ctx.contentResolver.query(uri, arrayOf(column), null, null, null)
                    ?.use { c ->
                        if (!c.moveToFirst()) return@use 0L
                        val idx = c.getColumnIndex(column)
                        if (idx < 0 || c.isNull(idx)) 0L else c.getLong(idx)
                    } ?: 0L
            } catch (_: Exception) {
                0L
            }
            if (value > 0) return value
        }
        return 0L
    }

    fun shortHash(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        val sb = StringBuilder(20)
        for (i in 0 until 10) {
            val v = d[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    // ---- recents ----------------------------------------------------------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("leaf", Context.MODE_PRIVATE)

    fun recents(ctx: Context): MutableList<Recent> {
        val raw = prefs(ctx).getString("recents", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Recent>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Recent(o.getString("uri"), o.getString("name"), o.optLong("last")))
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun remember(ctx: Context, uri: Uri, name: String) {
        val list = recents(ctx).filterNot { it.uri == uri.toString() }.toMutableList()
        list.add(0, Recent(uri.toString(), name, System.currentTimeMillis()))
        save(ctx, list.take(40))
    }

    fun forget(ctx: Context, uri: String) {
        save(ctx, recents(ctx).filterNot { it.uri == uri })
    }

    /** Rewrites the saved list in place, used when entries become unreadable. */
    fun keep(ctx: Context, list: List<Recent>) {
        save(ctx, list)
    }

    /**
     * Empties the recent list. The persisted read grants that were taken when each file
     * was opened are released too, so Leaf does not keep access to files the user has
     * cleared, and everything those documents put in the cache is removed.
     */
    fun clearRecents(ctx: Context) {
        for (r in recents(ctx)) {
            try {
                val uri = Uri.parse(r.uri)
                if (uri.scheme == "content") {
                    ctx.contentResolver.releasePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            } catch (_: Exception) {
                // The grant may already be gone, or never have been persistable.
            }
        }
        prefs(ctx).edit().remove("recents").apply()
        pruneAsync(ctx)
    }

    /** Trims the cache on a background thread; the list itself is already cleared. */
    fun pruneAsync(ctx: Context) {
        val app = ctx.applicationContext
        Thread({
            try {
                prune(app)
            } catch (_: Throwable) {
            }
        }, "leaf-prune").start()
    }

    /**
     * Drops cached copies and unpacked media for documents no longer in the recent list.
     * Anything still listed stays, so reopening it is instant. Blocking disk work, so
     * call it through [pruneAsync] from the UI.
     */
    fun prune(ctx: Context) {
        val keep = HashSet<String>()
        for (r in recents(ctx)) {
            keep.add(shortHash(r.uri))
            // Unpacked pictures live in a folder named after the file Leaf reads from,
            // not after the URI, so keep that key too.
            keep.add(shortHash(documentPath(ctx, Uri.parse(r.uri), r.name)))
        }
        for (dirName in CACHE_DIRS) {
            val kids = File(ctx.cacheDir, dirName).listFiles() ?: continue
            for (f in kids) {
                // Cache entries are named "<key>.<ext>" (files) or "<key>" (folders).
                if (f.name.substringBefore('.') !in keep) {
                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                }
            }
        }
        Prefs.prunePositions(ctx, keep)
    }

    private val CACHE_DIRS = arrayOf("docs", "epub", "docx", "odt", "slides")

    private fun save(ctx: Context, list: List<Recent>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().put("uri", r.uri).put("name", r.name).put("last", r.last))
        }
        prefs(ctx).edit().putString("recents", arr.toString()).apply()
    }
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("leaf", Context.MODE_PRIVATE)
    fun dark(ctx: Context): Boolean = p(ctx).getBoolean("dark", true)
    fun setDark(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("dark", v).apply()
    fun fontPx(ctx: Context): Int = p(ctx).getInt("font", 17).coerceIn(11, 34)
    fun setFontPx(ctx: Context, v: Int) = p(ctx).edit().putInt("font", v).apply()

    private const val POS_PREFIX = "pos:"

    /** Remembers a reading position for a document key ("leaf/<hash>"). */
    fun savePosition(ctx: Context, key: String?, pos: Int) {
        if (key == null) return
        p(ctx).edit().putInt(POS_PREFIX + key, pos).apply()
    }

    /** A saved position, or -1 when the document has none yet. */
    fun position(ctx: Context, key: String?): Int =
        if (key == null) -1 else p(ctx).getInt(POS_PREFIX + key, -1)

    /**
     * Drops positions whose document is no longer kept anywhere. [keep] holds the same
     * short hashes the cache uses, so a cleared recent list also forgets where it was.
     */
    fun prunePositions(ctx: Context, keep: Set<String>) {
        val prefs = p(ctx)
        for (key in prefs.all.keys) {
            if (!key.startsWith(POS_PREFIX)) continue
            val hash = key.removePrefix(POS_PREFIX).substringAfterLast('/')
            if (hash !in keep) prefs.edit().remove(key).apply()
        }
    }
}
