package com.example.smartfiles

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

fun stickerRoot(c: Context) = File(c.filesDir, "stickers")
fun myPacks(c: Context): List<File> = stickerRoot(c).listFiles()?.filter { File(it, "name.txt").exists() }?.sortedByDescending { it.lastModified() } ?: emptyList()

/** WhatsApp / WhatsApp Business sticker folders (old and new locations). */
fun findStickers(): List<File> {
    val r = Environment.getExternalStorageDirectory()
    return listOf(
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Stickers",
        "WhatsApp/Media/WhatsApp Stickers",
        "WhatsApp Business/Media/WhatsApp Business Stickers"
    ).map { File(r, it) }.filter { it.isDirectory }
        .flatMap { d -> d.walkTopDown().filter { it.isFile && it.length() > 0 && it.extension.equals("webp", true) }.toList() }
        .distinctBy { it.path }.sortedByDescending { it.lastModified() }
}

fun isAnimated(f: File): Boolean = runCatching {
    f.inputStream().use { val h = ByteArray(32); val n = it.read(h); n >= 21 && String(h, 12, 4, Charsets.US_ASCII) == "VP8X" && (h[20].toInt() and 2) != 0 }
}.getOrDefault(false)

fun loadBmp(f: File, target: Int = 0): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val m = maxOf(info.size.width, info.size.height)
        if (target > 0 && m > target * 2) d.setTargetSampleSize(m / target)
    }
}.getOrNull()

private fun bounds(f: File): Pair<Int, Int> {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o); return o.outWidth to o.outHeight
}

/** WhatsApp rules: static 512x512 WebP <=100 KB. Resizes/recompresses when needed. */
fun makeStatic(f: File, out: File): Boolean {
    if (bounds(f) == (512 to 512) && f.length() <= 100_000) { f.copyTo(out, true); return true }
    val b = loadBmp(f) ?: return false
    val sq = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    val s = 512f / maxOf(b.width, b.height); val w = b.width * s; val h = b.height * s
    Canvas(sq).drawBitmap(b, null, RectF((512 - w) / 2, (512 - h) / 2, (512 + w) / 2, (512 + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
    for (q in listOf(90, 80, 70, 60, 50, 40, 30)) {
        val bo = ByteArrayOutputStream(); sq.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, bo)
        if (bo.size() <= 100_000) { out.writeBytes(bo.toByteArray()); return true }
    }
    return false
}

/** Builds a sticker pack in app storage; returns its id, or null if fewer than 3 usable stickers. */
fun buildPack(c: Context, name: String, picks: List<File>): String? {
    val animPack = picks.isNotEmpty() && picks.all { isAnimated(it) }
    val id = "pack_" + System.currentTimeMillis()
    val dir = File(stickerRoot(c), id); dir.mkdirs()
    var n = 0; var first: Bitmap? = null
    for (f in picks.take(30)) {
        if (isAnimated(f) != animPack) continue
        val out = File(dir, "s$n.webp")
        val ok = if (animPack) f.length() <= 500_000 && bounds(f) == (512 to 512) && runCatching { f.copyTo(out, true); true }.getOrDefault(false)
                 else runCatching { makeStatic(f, out) }.getOrDefault(false)
        if (ok) { n++; if (first == null) first = loadBmp(f, 96) }
    }
    val t = first
    if (n < 3 || t == null) { dir.deleteRecursively(); return null }
    File(dir, "tray.png").outputStream().use { Bitmap.createScaledBitmap(t, 96, 96, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
    File(dir, "name.txt").writeText(name)
    if (animPack) File(dir, "animated").createNewFile()
    return id
}

/** WhatsApp's Sticker Pack API: WhatsApp reads packs from this provider. */
class StickerProvider : ContentProvider() {
    private val m = UriMatcher(UriMatcher.NO_MATCH)
    private val auth get() = context!!.packageName + ".stickercontentprovider"
    override fun onCreate(): Boolean {
        val a = context!!.packageName + ".stickercontentprovider"
        m.addURI(a, "metadata", 1); m.addURI(a, "metadata/*", 2); m.addURI(a, "stickers/*", 3); m.addURI(a, "stickers_asset/*/*", 4)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val c = context!!
        val cur = when (m.match(uri)) {
            1 -> packCursor(myPacks(c))
            2 -> packCursor(myPacks(c).filter { it.name == uri.lastPathSegment })
            3 -> stickerCursor(File(stickerRoot(c), uri.lastPathSegment ?: ""))
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        cur.setNotificationUri(c.contentResolver, uri); return cur
    }

    private fun packCursor(packs: List<File>): Cursor {
        val cur = MatrixCursor(arrayOf("sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher", "sticker_pack_icon",
            "android_play_store_link", "ios_app_download_link", "sticker_pack_publisher_email", "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website", "sticker_pack_license_agreement_website", "image_data_version", "avoid_cache", "animated_sticker_pack"))
        packs.forEach { d ->
            cur.newRow().add(d.name).add(File(d, "name.txt").readText()).add("Smart Files").add("tray.png").add("").add("").add("").add("").add("").add("")
                .add(d.lastModified().toString()).add(0).add(if (File(d, "animated").exists()) 1 else 0)
        }
        return cur
    }

    private fun stickerCursor(d: File): Cursor {
        val cur = MatrixCursor(arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text"))
        d.listFiles()?.filter { it.name.startsWith("s") && it.name.endsWith(".webp") }?.sortedBy { it.name.removePrefix("s").removeSuffix(".webp").toIntOrNull() ?: 0 }
            ?.forEach { cur.newRow().add(it.name).add("😀").add("Sticker") }
        return cur
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (m.match(uri) != 4) return null
        val s = uri.pathSegments; val base = stickerRoot(context!!)
        val f = File(File(base, s[s.size - 2]), s.last())
        if (!f.isFile || !f.canonicalPath.startsWith(base.canonicalPath)) return null
        return AssetFileDescriptor(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun getType(uri: Uri): String? = when (m.match(uri)) {
        1 -> "vnd.android.cursor.dir/vnd.$auth.metadata"
        2 -> "vnd.android.cursor.item/vnd.$auth.metadata"
        3 -> "vnd.android.cursor.dir/vnd.$auth.stickers"
        4 -> if (uri.lastPathSegment?.endsWith(".png") == true) "image/png" else "image/webp"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
