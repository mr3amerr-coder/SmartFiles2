package com.example.smartfiles

import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun CleanScreen() {
    var page by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ScreenHeader("Clean", "Free up space safely")
        Seg(listOf("Junk", "Bin"), page, Modifier.padding(bottom = 10.dp)) { page = it }
        if (page == 0) JunkPage() else BinPage()
    }
}

// ---------------- JUNK ----------------
@Composable private fun JunkPage() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val junk = Session.junk
    val sel = Session.junkSel
    val busy = Session.scanning || Session.cleaning
    var confirm by remember { mutableStateOf(false) }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { Session.refreshStats() }

    val groups = remember(junk) { (junk ?: emptyList()).groupBy { it.type }.toList().sortedBy { it.first.ordinal } }
    val selBytes = remember(junk, sel) { (junk ?: emptyList()).filter { it.f in sel }.sumOf { it.size } }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            item { StorageHero() }
            item {
                Panel(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    if (Session.scanning) {
                        Text("Scanning your storage…", style = MaterialTheme.typography.titleMedium)
                        Text("${Session.scanCount} files checked", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    } else {
                        Text(if (junk == null) "Find junk, duplicates and big files" else if (junk.isEmpty()) "Nothing to clean 🎉" else "${junk.size} items · ${fmt(junk.sumOf { it.size })}",
                            style = MaterialTheme.typography.titleMedium)
                        Text(if (junk == null) "Installers and temp files are pre-selected. Everything else is up to you."
                             else if (junk.isEmpty()) "Your storage looks tidy." else "Installers and temp files are pre-selected. Review the rest before cleaning.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BigButton(if (junk == null) "Scan for junk" else "Scan again", Modifier.weight(1f), Icons.Rounded.Search, enabled = !busy) { Session.startScan() }
                            OutlinedButton(onClick = { safeStart(ctx, Intent(StorageManager.ACTION_CLEAR_APP_CACHE)) }, modifier = Modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) { Text("App caches") }
                        }
                    }
                    Session.note?.let { Text(it, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = cs.primary) }
                }
            }
            groups.forEach { (type, list) ->
                val key = type.name
                val ids = list.map { it.f }
                val all = ids.all { it in sel }
                val some = ids.any { it in sel }
                item(key = "h-$key") {
                    GroupHeader(type, list.size, list.sumOf { it.size }, expanded = key in expandedGroups, checked = all, partial = some && !all,
                        onToggleOpen = { expandedGroups = if (key in expandedGroups) expandedGroups - key else expandedGroups + key },
                        onToggleAll = { Session.junkSel = if (all) sel - ids.toSet() else sel + ids })
                }
                if (key in expandedGroups) items(list, key = { it.f.path }) { j ->
                    JunkRow(j, j.f in sel) { Session.junkSel = if (j.f in sel) sel - j.f else sel + j.f }
                }
            }
        }
        if (junk != null && junk.isNotEmpty()) BigButton(
            "Clean ${fmt(selBytes)}", Modifier.fillMaxWidth().padding(vertical = 8.dp), Icons.Rounded.CleaningServices,
            enabled = sel.isNotEmpty() && !busy,
        ) { confirm = true }
    }

    if (confirm) {
        var forever by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Clean ${sel.size} items?") },
            text = {
                Column {
                    Text(if (forever) "They will be deleted permanently and the space is freed right now. This can’t be undone."
                         else "They move to the Bin for 30 days. The space is freed once you empty the Bin.")
                    Row(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).clickable { forever = !forever }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(forever, { forever = it })
                        Text("Delete permanently")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; Session.cleanSelected(permanent = forever) }) {
                    Text(if (forever) "Delete forever" else "Move to Bin", color = if (forever) cs.error else Color.Unspecified)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    }
}

/** Storage ring plus a colour-coded bar of what's using the space. Cached, so it opens instantly. */
@Composable private fun StorageHero() {
    val cs = MaterialTheme.colorScheme
    val st = remember(Session.cleaning, Session.scanning) { StatFs(Environment.getExternalStorageDirectory().path) }
    val total = st.totalBytes.coerceAtLeast(1L); val free = st.availableBytes
    val used = (total - free).toFloat() / total
    val anim by animateFloatAsState(used, tween(900), label = "used")
    val stats = Session.stats
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { anim }, modifier = Modifier.size(84.dp), color = if (used > 0.9f) cs.error else cs.primary,
                    trackColor = cs.surfaceContainerHigh, strokeWidth = 9.dp, strokeCap = StrokeCap.Round)
                Text("${(used * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(18.dp))
            Column {
                Text("${fmt(free)} free", style = MaterialTheme.typography.titleLarge)
                Text("${fmt(total - free)} used of ${fmt(total)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        if (stats == null) {
            Text("Analysing what’s taking space…", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        } else {
            val sum = stats.sumOf { it.second }.coerceAtLeast(1L)
            Row(Modifier.padding(top = 16.dp).fillMaxWidth().height(12.dp).clip(CircleShape).background(cs.surfaceContainerHigh)) {
                stats.filter { it.second > 0 }.forEach { (k, s) ->
                    Box(Modifier.weight((s.toFloat() / sum).coerceAtLeast(0.02f)).fillMaxHeight().background(k.color))
                }
            }
            Column(Modifier.padding(top = 10.dp)) {
                stats.filter { it.second > 0 }.forEach { (k, s) ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(k.color))
                        Spacer(Modifier.width(10.dp))
                        Text(k.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(fmt(s), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable private fun GroupHeader(
    type: JunkType, count: Int, bytes: Long, expanded: Boolean, checked: Boolean, partial: Boolean,
    onToggleOpen: () -> Unit, onToggleAll: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainer)
        .clickable(onClick = onToggleOpen).padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconBadge(type.icon, type.color, 42.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(type.title, style = MaterialTheme.typography.titleSmall)
            Text("$count items · ${fmt(bytes)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        TriStateCheckbox(
            state = if (checked) androidx.compose.ui.state.ToggleableState.On else if (partial) androidx.compose.ui.state.ToggleableState.Indeterminate else androidx.compose.ui.state.ToggleableState.Off,
            onClick = onToggleAll)
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.padding(end = 8.dp), tint = cs.onSurfaceVariant)
    }
}

@Composable private fun JunkRow(j: Junk, checked: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val kind = kindOf(j.f)
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { onToggle() })
        IconBadge(kind.icon, kind.color, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(j.f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            val where = (j.f.parent ?: "").removePrefix(Environment.getExternalStorageDirectory().path).ifEmpty { "/" }
            Text(listOfNotNull(fmt(j.size), j.note.ifEmpty { null }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Text(where, style = MaterialTheme.typography.labelSmall, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------- BIN ----------------
@Composable private fun BinPage() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var empty by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf<List<Bin>?>(null) }
    var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(tick) {
        val r = withContext(Dispatchers.IO) { val l = binItems(); l to l.associate { it.dir.path to dirSize(it.data) } }
        loaded = r.first; sizes = r.second
    }
    fun act(block: () -> String?) {
        scope.launch {
            busy = true
            val m = withContext(Dispatchers.IO) { runCatching { block() }.getOrNull() }
            busy = false; m?.let { toast(ctx, it) }; tick++
        }
    }
    val bin = loaded ?: emptyList()
    val total = sizes.values.sum()
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize()) {
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Delete, cs.primary, 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (loaded == null) "Recycle Bin" else "${bin.size} items · ${fmt(total)}", style = MaterialTheme.typography.titleMedium)
                    Text("Kept for 30 days. Empty the Bin to free the space now.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            BigButton("Empty Bin", Modifier.fillMaxWidth().padding(top = 12.dp), Icons.Rounded.DeleteForever, enabled = bin.isNotEmpty() && !busy) { empty = true }
        }
        if (busy || loaded == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        if (loaded != null && bin.isEmpty()) EmptyState(Icons.Rounded.Delete, "The Bin is empty", "Deleted files wait here before they’re gone for good.")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)) {
            items(bin, key = { it.dir.path }) { b ->
                val kind = kindOf(b.data)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(kind.icon, kind.color, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(b.orig.substringBeforeLast('/'), style = MaterialTheme.typography.labelSmall, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val left = (30 - (now - b.time) / DAY).coerceAtLeast(0)
                        Text("${fmt(sizes[b.dir.path] ?: 0L)} · $left days left", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = { act { if (restore(b)) "Restored" else "Couldn’t restore. Check that the original folder is available." } }, enabled = !busy) {
                        Icon(Icons.Rounded.RestoreFromTrash, "Restore", tint = cs.primary)
                    }
                    IconButton(onClick = { act { b.dir.deleteRecursively(); null } }, enabled = !busy) {
                        Icon(Icons.Rounded.DeleteForever, "Delete forever", tint = cs.error)
                    }
                }
            }
        }
    }
    if (empty) AlertDialog(onDismissRequest = { empty = false }, title = { Text("Empty the Bin?") }, text = { Text("${bin.size} items will be permanently deleted.") },
        confirmButton = { TextButton(onClick = { empty = false; act { trashRoots().forEach { it.deleteRecursively() }; "Bin emptied" } }) { Text("Empty", color = cs.error) } },
        dismissButton = { TextButton(onClick = { empty = false }) { Text("Cancel") } })
}
