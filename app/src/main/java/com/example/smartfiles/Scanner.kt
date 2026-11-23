package com.example.smartfiles

import android.os.Environment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

// ---------- What the scanner can find ----------
enum class JunkType(val title: String, val hint: String, val icon: ImageVector, val color: Color, val safe: Boolean) {
    Apk("Installer files", "APKs you have probably already installed", Icons.Rounded.Android, Color(0xFF3DDC84), true),
    Temp("Temp & cache files", "Safe to remove", Icons.Rounded.CleaningServices, Color(0xFF2FB8C9), true),
    Duplicate("Duplicates", "Extra copies. The oldest one is kept", Icons.Rounded.ContentCopy, Color(0xFF8E6CF5), false),
    OldDownload("Old downloads", "Not touched in 90+ days", Icons.Rounded.FileDownload, Color(0xFFF2A93B), false),
    Large("Large files", "Bigger than 100 MB. Check before deleting", Icons.Rounded.Storage, Color(0xFFE5484D), false),
    Backup("Backups & .old files", "Check before deleting", Icons.Rounded.Backup, Color(0xFF8A93A6), false),
}

data class Junk(val f: File, val type: JunkType, val size: Long, val note: String = "")

class Analysis(val junk: List<Junk>, val stats: List<Pair<Kind, Long>>)

// ---------- One fast walk over storage ----------
/** NIO hands us size + modified time with the directory read, so each file costs one system call instead of three. */
fun walkStorage(roots: List<File>, skipDir: (Path) -> Boolean, onFile: (Path, BasicFileAttributes) -> Unit) {
    for (r in roots) {
        runCatching {
            Files.walkFileTree(r.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (skipDir(dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) onFile(file, attrs)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
    }
}

fun md5(f: File): String {
    val d = MessageDigest.getInstance("MD5"); val buf = ByteArray(65536)
    f.inputStream().use { s -> while (true) { val n = s.read(buf); if (n < 0) break; d.update(buf, 0, n) } }
    return d.digest().joinToString("") { "%02x".format(it) }
}

/** Cheap fingerprint (first and last 64 KB) so big videos are only fully hashed when they really look identical. */
fun quickKey(f: File): String = runCatching {
    RandomAccessFile(f, "r").use { r ->
        val len = r.length(); val buf = ByteArray(65536); val d = MessageDigest.getInstance("MD5")
        val a = r.read(buf); if (a > 0) d.update(buf, 0, a)
        if (len > 131072) { r.seek(len - 65536); val b = r.read(buf); if (b > 0) d.update(buf, 0, b) }
        d.digest().joinToString("") { "%02x".format(it) }
    }
}.getOrDefault(f.path)

private class Cand(val f: File, val mod: Long)

/** Sizes by file type, and (when [withJunk]) the junk list, from a single pass. */
fun analyze(roots: List<File>, withJunk: Boolean, progress: (Int) -> Unit = {}): Analysis {
    val totals = HashMap<Kind, Long>()
    val out = ArrayList<Junk>()
    val bySize = HashMap<Long, MutableList<Cand>>()
    val now = System.currentTimeMillis()
    val temp = setOf("tmp", "temp", "log"); val review = setOf("bak", "old")
    var count = 0

    walkStorage(roots, skipDir = { it.fileName?.toString() == TRASH }) { p, a ->
        val path = p.toString()
        val ext = path.substringAfterLast('.', "").lowercase()
        val size = a.size()
        val k = kindOfExt(ext)
        totals[k] = (totals[k] ?: 0L) + size
        count++
        if (count % 2000 == 0) progress(count)

        // App-private folders (Android/...) count towards the totals but are never offered for cleaning.
        if (withJunk && !path.contains("/Android/")) {
            val mod = a.lastModifiedTime().toMillis()
            val type = when {
                ext == "apk" -> JunkType.Apk
                ext in temp || path.contains("/.thumbnails/") -> JunkType.Temp
                ext in review -> JunkType.Backup
                path.contains("/Download/") && now - mod > 90L * DAY -> JunkType.OldDownload
                size > 100_000_000 -> JunkType.Large
                else -> null
            }
            if (type != null) out.add(Junk(File(path), type, size))
            if (size > 1_000_000) bySize.getOrPut(size) { ArrayList() }.add(Cand(File(path), mod))
        }
    }
    progress(count)

    if (withJunk) {
        val seen = out.mapTo(HashSet()) { it.f }
        for (g in bySize.values) {
            if (g.size < 2) continue
            for (maybe in g.groupBy { quickKey(it.f) }.values) {
                if (maybe.size < 2) continue
                for (d in maybe.groupBy { runCatching { md5(it.f) }.getOrDefault(it.f.path) }.values) {
                    if (d.size < 2) continue
                    val keep = d.minBy { it.mod }
                    d.filter { it !== keep && seen.add(it.f) }.forEach { out.add(Junk(it.f, JunkType.Duplicate, it.f.length(), "Same as ${keep.f.name}")) }
                }
            }
        }
    }
    val stats = Kind.entries.filter { it != Kind.Folder }.map { it to (totals[it] ?: 0L) }.sortedByDescending { it.second }
    return Analysis(out.sortedByDescending { it.size }, stats)
}

// ---------- App-wide state (survives tab switches and rotation) ----------
object Session {
    var dir by mutableStateOf(File(Environment.getExternalStorageDirectory().path))
    var cut by mutableStateOf(false)
    var roots by mutableStateOf(listOf(File(Environment.getExternalStorageDirectory().path)))
    val clip = mutableStateListOf<File>()

    /** Process-wide scope: scans, cleaning and Undo keep working when you switch tabs or rotate. */
    val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var junk by mutableStateOf<List<Junk>?>(null)
    var junkSel by mutableStateOf<Set<File>>(emptySet())
    var scanning by mutableStateOf(false)
    var scanCount by mutableIntStateOf(0)
    var cleaning by mutableStateOf(false)
    var note by mutableStateOf<String?>(null)

    var stats by mutableStateOf<List<Pair<Kind, Long>>?>(null)
    private var statsAt = 0L
    private var statsBusy = false

    fun startScan() {
        if (scanning || cleaning) return
        scanning = true; note = null; scanCount = 0
        val rs = roots
        bg.launch {
            val a = runCatching { analyze(rs, true) { scanCount = it } }.getOrNull()
            withContext(Dispatchers.Main) {
                if (a != null) {
                    junk = a.junk; junkSel = a.junk.filter { it.type.safe }.map { it.f }.toSet()
                    stats = a.stats; statsAt = System.currentTimeMillis()
                } else note = "The scan didn’t finish. Please try again."
                scanning = false
            }
        }
    }

    /** Storage breakdown, cached for 5 minutes so opening the Clean tab doesn't re-walk the whole phone. */
    fun refreshStats(force: Boolean = false) {
        if (statsBusy || scanning) return
        if (!force && stats != null && System.currentTimeMillis() - statsAt < 5 * 60_000L) return
        statsBusy = true
        val rs = roots
        bg.launch {
            val a = runCatching { analyze(rs, false) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (a != null) { stats = a.stats; statsAt = System.currentTimeMillis() }
                statsBusy = false
            }
        }
    }

    fun cleanSelected(permanent: Boolean) {
        if (scanning || cleaning || junkSel.isEmpty()) return
        val files = junkSel.toList(); cleaning = true
        bg.launch {
            var n = 0; var bytes = 0L
            val bins = ArrayList<File>()
            for (f in files) {
                val s = f.length()
                if (permanent) { if (f.deleteRecursively()) { n++; bytes += s } }
                else moveToTrashDir(f)?.let { bins.add(it); n++; bytes += s }
            }
            withContext(Dispatchers.Main) {
                junk = junk?.filter { it.f.exists() }
                junkSel = junkSel.filter { it.exists() }.toSet()
                note = if (permanent) "Deleted $n files and freed ${fmt(bytes)}."
                       else "Moved $n files to the Bin. ${fmt(bytes)} is freed once you empty the Bin."
                cleaning = false
                if (!permanent && bins.isNotEmpty()) Notify.say("Moved $n files to the Bin", "Undo") { undo(bins) }
            }
            refreshStats(force = true)
        }
    }

    private fun undo(bins: List<File>) {
        bg.launch {
            bins.forEach { runCatching { restore(Bin(it)) } }
            withContext(Dispatchers.Main) { note = "Restored ${bins.size} files."; junk = null; junkSel = emptySet() }
            refreshStats(force = true)
        }
    }
}
