package com.example.smartfiles

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

// ---------- Sharing ----------
fun shareFile(c: Context, f: File) {
    try {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.fp", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
    } catch (e: Exception) { toast(c, "Couldn’t share this file") }
}

// ---------- File operations ----------

/** First free name in [dir]: "a.txt" -> "a (1).txt", or "a (restored).txt" when [tag] is given. */
fun freeName(dir: File, name: String, isDir: Boolean, tag: String = ""): File {
    var t = File(dir, name)
    if (!t.exists()) return t
    val ext = if (isDir || File(name).extension.isEmpty()) "" else "." + File(name).extension
    val base = if (isDir) name else File(name).nameWithoutExtension
    var i = 1
    while (t.exists()) {
        t = File(dir, if (tag.isEmpty()) "$base ($i)$ext" else "$base ($tag${if (i > 1) " $i" else ""})$ext")
        i++
    }
    return t
}

/** Returns null on success, or a message that explains what went wrong. */
fun renameFile(f: File, name: String): String? {
    val n = name.trim()
    if (n.isEmpty() || n == "." || n == ".." || '/' in n) return "Enter a valid name"
    val t = File(f.parentFile, n)
    // Storage is case-insensitive, so "a.txt" -> "A.txt" is a rename, not a clash.
    if (t.exists() && !n.equals(f.name, ignoreCase = true)) return "“$n” already exists here"
    return if (f.renameTo(t)) null else "Couldn’t rename"
}

fun makeFolder(dir: File, name: String): String? {
    val n = name.trim()
    if (n.isEmpty() || n == "." || n == ".." || '/' in n) return "Enter a valid name"
    val t = File(dir, n)
    if (t.exists()) return "“$n” already exists here"
    return if (t.mkdirs()) null else "Couldn’t create folder"
}

/** Copies or moves each file into [dir]. Never overwrites; returns how many succeeded. */
fun pasteInto(dir: File, files: List<File>, cut: Boolean): Int {
    var ok = 0
    for (f in files) {
        if (!f.exists()) continue
        if (f.isDirectory && (dir.path == f.path || dir.path.startsWith(f.path + "/"))) continue
        if (cut && f.parentFile?.path == dir.path) { ok++; continue }   // already there
        val t = freeName(dir, f.name, f.isDirectory)
        val done = try {
            if (cut && f.renameTo(t)) true
            else if (f.copyRecursively(t)) { if (cut) f.deleteRecursively(); true }
            else { t.deleteRecursively(); false }
        } catch (e: Exception) { t.deleteRecursively(); false }
        if (done) ok++
    }
    return ok
}

// ---------- Recycle Bin: <volume>/.SmartTrash/<id>/{origin,data} ----------
// Each storage volume keeps its own Bin, so deleting from an SD card is an instant rename
// instead of a slow copy onto internal storage.
const val TRASH = ".SmartTrash"
const val DAY = 86_400_000L

fun volumeRootOf(f: File): File =
    Session.roots.filter { f.path == it.path || f.path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }
        ?: Environment.getExternalStorageDirectory()

fun trashRoots(roots: List<File> = Session.roots): List<File> =
    (roots + Environment.getExternalStorageDirectory()).distinctBy { it.path }.map { File(it, TRASH) }

/** Moves [f] into the Bin and returns the Bin entry folder (so the caller can offer Undo), or null on failure. */
fun moveToTrashDir(f: File): File? {
    val d = File(File(volumeRootOf(f), TRASH), System.nanoTime().toString())
    if (!d.mkdirs()) return null
    if (runCatching { File(d, "origin").writeText(f.path) }.isFailure) { d.deleteRecursively(); return null }
    val t = File(d, "data")
    if (f.renameTo(t)) return d
    val copied = try { f.copyRecursively(t) } catch (e: Exception) { false }
    if (!copied) { d.deleteRecursively(); return null }
    f.deleteRecursively()
    // The full copy is safe in the Bin; only report success if the original is really gone.
    return if (!f.exists()) d else null
}

class Bin(val dir: File) {
    private val originFile = File(dir, "origin")
    val orig = originFile.takeIf { it.exists() }?.readText() ?: dir.name
    val data = File(dir, "data")
    val name: String get() = File(orig).name
    /** When it was deleted (the origin file is written once, at deletion time). */
    val time: Long = if (originFile.exists()) originFile.lastModified() else dir.lastModified()
}

fun binItems(): List<Bin> =
    trashRoots().flatMap { it.listFiles()?.toList() ?: emptyList() }.filter { File(it, "data").exists() }.map { Bin(it) }.sortedByDescending { it.time }

fun dirSize(f: File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

fun purgeOldBin(roots: List<File> = Session.roots) {
    val limit = System.currentTimeMillis() - 30 * DAY
    trashRoots(roots).forEach { r ->
        r.listFiles()?.forEach { d ->
            val o = File(d, "origin")
            if ((if (o.exists()) o.lastModified() else d.lastModified()) < limit) d.deleteRecursively()
        }
    }
}

fun restore(b: Bin): Boolean {
    val orig = File(b.orig)
    val parent = orig.parentFile ?: return false
    parent.mkdirs()
    val t = freeName(parent, orig.name, b.data.isDirectory, "restored")
    if (b.data.renameTo(t)) { b.dir.deleteRecursively(); return true }
    if (try { b.data.copyRecursively(t) } catch (e: Exception) { false }) { b.dir.deleteRecursively(); return true }
    t.deleteRecursively()   // t did not exist before, so this only removes a partial copy
    return false
}

// ---------- Storage volumes (internal, SD card, USB drives) ----------
class Vol(val name: String, val dir: File, val internal: Boolean)

fun volumes(c: Context): List<Vol> {
    val sm = c.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    return sm.storageVolumes.filter { it.state == Environment.MEDIA_MOUNTED && it.directory != null }
        .map { Vol(if (it.isPrimary) "Internal storage" else (it.getDescription(c) ?: "External storage"), it.directory!!, it.isPrimary) }
}

fun freeOf(f: File): String = runCatching { StatFs(f.path).let { "${fmt(it.availableBytes)} free of ${fmt(it.totalBytes)}" } }.getOrDefault("")
