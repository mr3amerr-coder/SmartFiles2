package com.example.smartfiles

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Everything the list needs, read once on a background thread so scrolling never touches the disk. */
data class FileEntry(val f: File, val dir: Boolean, val size: Long, val mod: Long, val kids: Int)

fun listEntries(dir: File, showHidden: Boolean): List<FileEntry> =
    (dir.listFiles() ?: emptyArray<File>())
        .filter { it.name != TRASH && (showHidden || !it.name.startsWith(".")) }
        .map { val d = it.isDirectory; FileEntry(it, d, if (d) 0L else it.length(), it.lastModified(), if (d) (it.list()?.size ?: 0) else 0) }

private val thumbs = android.util.LruCache<String, ImageBitmap>(300)

@Composable fun FileIcon(e: FileEntry, selected: Boolean) {
    val kind = if (e.dir) Kind.Folder else kindOfExt(e.f.extension.lowercase())
    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
        if (selected) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
        } else if (!e.dir && e.f.extension.lowercase() in THUMB_EXT) {
            val key = e.f.path + "|" + e.mod
            val bmp by produceState<ImageBitmap?>(thumbs.get(key), key) {
                if (value == null) value = withContext(Dispatchers.IO) { loadBmp(e.f, 96)?.asImageBitmap() }?.also { thumbs.put(key, it) }
            }
            val b = bmp
            if (b != null) Image(b, null, Modifier.fillMaxSize().clip(RoundedCornerShape(15.dp)), contentScale = ContentScale.Crop)
            else IconBadge(kind.icon, kind.color, 46.dp)
        } else IconBadge(kind.icon, kind.color, 46.dp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun FilesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme

    var dir by Session::dir
    var volTick by remember { mutableIntStateOf(0) }
    OnResume { volTick++ }
    val vols = remember(volTick) { volumes(ctx) }
    val root = vols.firstOrNull { dir.path == it.dir.path || dir.path.startsWith(it.dir.path + "/") }?.dir ?: Environment.getExternalStorageDirectory()
    val volName = vols.firstOrNull { it.dir == root }?.name ?: "Storage"

    var tick by remember { mutableIntStateOf(0) }
    OnResume(skipFirst = true) { tick++ }          // pick up changes made in other apps
    var sort by rememberSaveable { mutableStateOf(0) }        // 0 name, 1 size, 2 date
    var desc by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val showHidden = Prefs.hidden
    var sortMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    var ren by remember { mutableStateOf<File?>(null) }
    var edit by remember { mutableStateOf<File?>(null) }
    var mk by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<File>()) }
    val df = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    val free = remember(root, tick) { freeOf(root) }
    val shortcuts = remember(root) {
        listOf("Downloads" to "Download", "Camera" to "DCIM", "Pictures" to "Pictures", "Documents" to "Documents").filter { File(root, it.second).isDirectory }
    }

    fun go(d: File) { picked = emptySet(); query = ""; dir = d }
    fun toggle(f: File) { picked = if (f in picked) picked - f else picked + f }

    /** Runs a file operation off the main thread; the block returns a message to show, or null. */
    fun io(block: () -> String?) {
        scope.launch {
            busy = true
            val msg = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { "Something went wrong" } }
            busy = false; msg?.let { toast(ctx, it) }; tick++
        }
    }

    /** Deleting is instant and safe: items go to the Bin and a snackbar offers Undo. */
    fun trash(files: List<File>) {
        scope.launch {
            busy = true
            val bins = withContext(Dispatchers.IO) { files.mapNotNull { runCatching { moveToTrashDir(it) }.getOrNull() } }
            busy = false; tick++
            if (bins.isEmpty()) toast(ctx, "Couldn’t delete. The file may be read-only.")
            else {
                val failed = files.size - bins.size
                val msg = (if (bins.size == 1) "Moved to Bin" else "${bins.size} items moved to Bin") + (if (failed > 0) " · $failed failed" else "")
                Notify.say(msg, "Undo") {
                    Session.bg.launch { bins.forEach { runCatching { restore(Bin(it)) } }; withContext(Dispatchers.Main) { tick++ } }
                }
            }
        }
    }

    BackHandler(dir != root) { go(dir.parentFile ?: root) }
    BackHandler(picked.isNotEmpty()) { picked = emptySet() }

    LaunchedEffect(dir, tick, showHidden) { entries = withContext(Dispatchers.IO) { listEntries(dir, showHidden) } }
    val rows = remember(entries, sort, desc, query) {
        val base: Comparator<FileEntry> = when (sort) {
            1 -> compareBy<FileEntry> { it.size }
            2 -> compareBy<FileEntry> { it.mod }
            else -> compareBy<FileEntry> { it.f.name.lowercase() }
        }
        val cmp = if (desc) base.reversed() else base
        (entries ?: emptyList()).filter { it.f.name.contains(query, true) }
            .sortedWith(compareByDescending<FileEntry> { it.dir }.then(cmp).thenBy { it.f.name.lowercase() })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // ---- Header ----
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dir != root) IconButton(onClick = { go(dir.parentFile ?: root) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(volName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(free, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    listOf("Name", "Size", "Date").forEachIndexed { i, n ->
                        DropdownMenuItem(text = { Text(n) }, onClick = {
                            if (sort == i) desc = !desc else { sort = i; desc = i != 0 }
                            sortMenu = false
                        }, trailingIcon = {
                            if (sort == i) Icon(if (desc) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, null)
                        })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Show hidden files") }, onClick = { Prefs.setHidden(!Prefs.hidden); sortMenu = false },
                        trailingIcon = { if (showHidden) Icon(Icons.Rounded.Check, null) })
                }
            }
            IconButton(onClick = { mk = true }) { Icon(Icons.Rounded.CreateNewFolder, "New folder") }
        }

        // ---- Breadcrumb: tap any part of the path to jump there ----
        val crumbScroll = rememberScrollState()
        val rel = dir.path.removePrefix(root.path).trim('/').split('/').filter { it.isNotEmpty() }
        LaunchedEffect(dir) { delay(60); crumbScroll.animateScrollTo(crumbScroll.maxValue) }
        Row(Modifier.fillMaxWidth().horizontalScroll(crumbScroll).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Crumb(volName, last = rel.isEmpty()) { go(root) }
            var acc = root
            rel.forEachIndexed { i, seg ->
                acc = File(acc, seg)
                val target = acc
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = cs.outline)
                Crumb(seg, last = i == rel.lastIndex) { go(target) }
            }
        }

        // ---- Search ----
        TextField(
            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            placeholder = { Text("Search this folder") }, singleLine = true, shape = RoundedCornerShape(28.dp),
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = cs.surfaceContainerHigh, unfocusedContainerColor = cs.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
            ),
        )

        // ---- Storage + quick folders ----
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vols.forEach { v ->
                FilterChip(selected = v.dir == root, onClick = { go(v.dir) }, label = { Text(v.name) },
                    leadingIcon = { Icon(if (v.internal) Icons.Rounded.PhoneAndroid else Icons.Rounded.SdCard, null, Modifier.size(18.dp)) })
            }
            shortcuts.forEach { (n, p) ->
                AssistChip(onClick = { go(File(root, p)) }, label = { Text(n) }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)) })
            }
        }

        // ---- Selection bar ----
        if (picked.isNotEmpty()) Surface(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(20.dp), color = cs.primaryContainer) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { picked = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection") }
                Text("${picked.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
                IconButton(onClick = { picked = rows.map { it.f }.toSet() }) { Icon(Icons.Rounded.SelectAll, "Select all") }
                IconButton(onClick = { Session.clip.clear(); Session.clip.addAll(picked); Session.cut = false; picked = emptySet() }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                IconButton(onClick = { Session.clip.clear(); Session.clip.addAll(picked); Session.cut = true; picked = emptySet() }) { Icon(Icons.Rounded.ContentCut, "Move") }
                IconButton(onClick = { val l = picked.toList(); picked = emptySet(); trash(l) }) { Icon(Icons.Rounded.Delete, "Delete", tint = cs.error) }
            }
        }

        if (busy || entries == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))

        // ---- List ----
        Box(Modifier.weight(1f)) {
            if (entries != null && rows.isEmpty()) {
                if (query.isEmpty()) EmptyState(Icons.Rounded.FolderOpen, "This folder is empty")
                else EmptyState(Icons.Rounded.Search, "No matches", "Nothing here is named “$query”")
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)) {
                items(rows, key = { it.f.path }) { e ->
                    val f = e.f
                    val sel = f in picked
                    var menu by remember { mutableStateOf(false) }
                    val bg by animateColorAsState(if (sel) cs.primaryContainer else Color.Transparent, label = "row")
                    Row(
                        Modifier.animateItem().fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
                            .combinedClickable(
                                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); toggle(f) },
                                onClick = { if (picked.isNotEmpty()) toggle(f) else if (e.dir) go(f) else openFile(ctx, f) },
                            ).padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FileIcon(e, sel)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = if (e.dir) (if (e.kids == 1) "1 item" else "${e.kids} items") else fmt(e.size)
                            Text(sub + " · " + df.format(e.mod), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (!e.dir) DropdownMenuItem(text = { Text("Open") }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) },
                                    onClick = { menu = false; openFile(ctx, f) })
                                if (!e.dir) DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                    onClick = { menu = false; shareFile(ctx, f) })
                                if (!e.dir && e.size < 500_000 && f.extension.lowercase() in TEXT_EXT)
                                    DropdownMenuItem(text = { Text("Edit text") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                        onClick = { menu = false; edit = f })
                                DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                                    onClick = { menu = false; Session.clip.clear(); Session.clip.add(f); Session.cut = false })
                                DropdownMenuItem(text = { Text("Move") }, leadingIcon = { Icon(Icons.Rounded.ContentCut, null) },
                                    onClick = { menu = false; Session.clip.clear(); Session.clip.add(f); Session.cut = true })
                                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                    onClick = { menu = false; ren = f })
                                DropdownMenuItem(text = { Text("Delete", color = cs.error) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = cs.error) },
                                    onClick = { menu = false; trash(listOf(f)) })
                            }
                        }
                    }
                }
            }
        }

        // ---- Paste bar ----
        if (Session.clip.isNotEmpty()) Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(20.dp), color = cs.secondaryContainer) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ContentPaste, null, tint = cs.onSecondaryContainer)
                Spacer(Modifier.width(10.dp))
                Text("${Session.clip.size} to ${if (Session.cut) "move" else "copy"}", Modifier.weight(1f), color = cs.onSecondaryContainer)
                IconButton(onClick = { Session.clip.clear() }) { Icon(Icons.Rounded.Close, "Cancel", tint = cs.onSecondaryContainer) }
                Button(onClick = {
                    val files = Session.clip.toList(); val cut = Session.cut; val target = dir
                    Session.clip.clear()
                    io { val n = pasteInto(target, files, cut); if (n == files.size) "Done" else "$n of ${files.size} done" }
                }) { Text("Paste here") }
            }
        }
    }

    // ---- Dialogs ----
    if (mk) {
        var nm by remember { mutableStateOf("New folder") }
        AlertDialog(onDismissRequest = { mk = false }, title = { Text("New folder") },
            text = { OutlinedTextField(nm, { nm = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { mk = false; val t = dir; val n = nm; io { makeFolder(t, n) } }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { mk = false }) { Text("Cancel") } })
    }
    ren?.let { f ->
        // The name (without its extension) is pre-selected, so you can just start typing.
        var tf by remember(f) { mutableStateOf(TextFieldValue(f.name, TextRange(0, f.nameWithoutExtension.length))) }
        val focus = remember { FocusRequester() }
        AlertDialog(onDismissRequest = { ren = null }, title = { Text("Rename") },
            text = {
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                OutlinedTextField(tf, { tf = it }, Modifier.focusRequester(focus), singleLine = true)
            },
            confirmButton = { TextButton(onClick = { val n = tf.text; ren = null; io { renameFile(f, n) } }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { ren = null }) { Text("Cancel") } })
    }
    edit?.let { f ->
        val initial = remember(f) { runCatching { f.readText() }.getOrNull() }
        if (initial == null) LaunchedEffect(f) { toast(ctx, "Couldn’t read this file"); edit = null }
        else {
            var text by remember(f) { mutableStateOf(initial) }
            AlertDialog(onDismissRequest = { edit = null }, title = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(max = 400.dp)) },
                confirmButton = {
                    TextButton(onClick = {
                        val t = text; edit = null
                        io { if (runCatching { f.writeText(t) }.isSuccess) "Saved" else "Couldn’t save. The file may be read-only." }
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } })
        }
    }
}

@Composable private fun Crumb(text: String, last: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(text, Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp), maxLines = 1,
        style = MaterialTheme.typography.labelLarge, color = if (last) cs.onSurface else cs.primary)
}
