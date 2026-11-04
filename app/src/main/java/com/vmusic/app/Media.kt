package com.vmusic.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class Track(val uri: Uri, val title: String, val artist: String, val added: Long,
                 val isVideo: Boolean, val path: String, val duration: Long) {
    val id: String get() = uri.toString()
}

object ShortCfg {
    var sec = 60
    private var prefs: android.content.SharedPreferences? = null
    fun load(ctx: Context) { prefs = ctx.getSharedPreferences("vm", 0); sec = prefs!!.getInt("short_sec", 60) }
    fun updateSec(v: Int) { sec = v.coerceIn(5, 600); prefs?.edit()?.putInt("short_sec", sec)?.apply() }
}
fun Track.isShort() = !isVideo && duration in 1 until ShortCfg.sec * 1000L

data class LrcLine(val time: Long, val text: String)

fun Track.toItem(): MediaItem = MediaItem.Builder().setUri(uri).setMediaId(id)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).build()).build()

/** Newest first, by the later of DATE_ADDED / DATE_MODIFIED (seconds). */
fun loadTracks(ctx: Context): List<Track> {
    val out = mutableListOf<Track>()
    fun scan(base: Uri, video: Boolean) {
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATA,
            if (video) MediaStore.Video.Media.ARTIST else MediaStore.Audio.Media.ARTIST,
            if (video) MediaStore.Video.Media.DURATION else MediaStore.Audio.Media.DURATION,
            MediaStore.MediaColumns.DATE_MODIFIED)
        try {
            ctx.contentResolver.query(base, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(3) ?: ""
                    val lp = path.lowercase()
                    if (!video && (lp.contains("/ringtones/") || lp.contains("/notifications/") || lp.contains("/alarms/"))) continue
                    val id = c.getLong(0)
                    out += Track(Uri.withAppendedPath(base, id.toString()), c.getString(1) ?: "Unknown",
                        c.getString(4)?.takeIf { it != "<unknown>" } ?: "", maxOf(c.getLong(2), c.getLong(6)),
                        video, path, c.getLong(5))
                }
            }
        } catch (_: Exception) {}
    }
    scan(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false)
    scan(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
    return out.sortedByDescending { it.added }
}

private val coverCache = LruCache<String, Bitmap>(80)

suspend fun loadCover(ctx: Context, t: Track, px: Int): Bitmap? = withContext(Dispatchers.IO) {
    val key = t.id + "@" + px
    coverCache.get(key)?.let { return@withContext it }
    var res: Bitmap? = null
    try {
        if (!t.isVideo) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, t.uri)
                r.embeddedPicture?.let { b ->
                    val o = BitmapFactory.Options().apply { inSampleSize = if (px <= 160) 4 else 1 }
                    res = BitmapFactory.decodeByteArray(b, 0, b.size, o)
                }
            } finally { r.release() }
        }
        if (res == null && Build.VERSION.SDK_INT >= 29) res = ctx.contentResolver.loadThumbnail(t.uri, Size(px, px), null)
    } catch (e: Exception) {}
    res?.let { coverCache.put(key, it) }
    res
}

private val rx = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]")
fun parseLrc(text: String): List<LrcLine> {
    val out = mutableListOf<LrcLine>()
    for (raw in text.lines()) {
        val ms = rx.findAll(raw).toList()
        val txt = raw.replace(rx, "").trim()
        if (ms.isEmpty()) { if (txt.isNotEmpty() && !txt.startsWith("[")) out += LrcLine(-1, txt) }
        else ms.forEach { m ->
            val fr = m.groupValues[3]
            val f = if (fr.isEmpty()) 0L else fr.padEnd(3, '0').take(3).toLong()
            out += LrcLine(m.groupValues[1].toLong() * 60000 + m.groupValues[2].toLong() * 1000 + f, txt)
        }
    }
    return if (out.any { it.time >= 0 }) out.filter { it.time >= 0 }.sortedBy { it.time } else out
}

suspend fun loadLrc(t: Track): List<LrcLine> = withContext(Dispatchers.IO) {
    try {
        val f = File(t.path.substringBeforeLast('.') + ".lrc")
        if (f.exists()) parseLrc(f.readText()) else emptyList()
    } catch (e: Exception) { emptyList() }
}

/* ---------- wallpapers: bundled (assets/wallpapers) + added by the user (filesDir/wallpapers) ---------- */
fun wallpaperIds(ctx: Context): List<String> {
    val a = try { ctx.assets.list("wallpapers")?.filter { it.lowercase().matches(Regex(".*\\.(jpg|jpeg|png|webp)")) }?.sorted()?.map { "asset:$it" } } catch (_: Exception) { null } ?: emptyList()
    val f = File(ctx.filesDir, "wallpapers").listFiles()?.sortedBy { it.name }?.map { it.absolutePath } ?: emptyList()
    return a + f
}

suspend fun loadWallpaper(ctx: Context, id: String, maxW: Int): Bitmap? = withContext(Dispatchers.IO) {
    try {
        fun open() = if (id.startsWith("asset:")) ctx.assets.open("wallpapers/" + id.removePrefix("asset:")) else File(id).inputStream()
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1; while (o.outWidth / (s * 2) >= maxW) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        open().use { BitmapFactory.decodeStream(it, null, o2) }
    } catch (e: Exception) { null }
}

fun importWallpapers(ctx: Context, uris: List<Uri>) {
    val dir = File(ctx.filesDir, "wallpapers").apply { mkdirs() }
    uris.forEachIndexed { i, u ->
        try {
            ctx.contentResolver.openInputStream(u)?.use { inp ->
                File(dir, "w_${System.currentTimeMillis()}_$i.jpg").outputStream().use { inp.copyTo(it) }
            }
        } catch (_: Exception) {}
    }
}

suspend fun accentOf(b: Bitmap, fallback: Color): Color = withContext(Dispatchers.Default) {
    try {
        val sm = Bitmap.createScaledBitmap(b, 96, 96, true)
        val p = Palette.from(sm).maximumColorCount(16).generate()
        val c = p.getVibrantColor(p.getLightVibrantColor(p.getDominantColor(fallback.toArgb())))
        Color(c)
    } catch (e: Exception) { fallback }
}
