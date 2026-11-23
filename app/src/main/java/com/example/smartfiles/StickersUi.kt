package com.example.smartfiles

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.InsertEmoticon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val stickerThumbs = android.util.LruCache<String, ImageBitmap>(150)

@Composable fun StickerCell(f: File, on: Boolean, click: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bmp by produceState<ImageBitmap?>(stickerThumbs.get(f.path), f) {
        if (value == null) value = withContext(Dispatchers.IO) { loadBmp(f, 256)?.asImageBitmap() }?.also { stickerThumbs.put(f.path, it) }
    }
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp))
        .background(if (on) cs.primaryContainer else cs.surfaceContainerHigh)
        .clickable(onClick = click).padding(6.dp), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize()) }
        if (on) Box(Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, Modifier.size(15.dp), tint = cs.onPrimary)
        }
    }
}

@Composable fun StickersScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var found by remember { mutableStateOf<List<File>?>(null) }
    var packs by remember { mutableStateOf(listOf<File>()) }
    var tick by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("My Stickers") }
    var busy by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf(setOf<File>()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra("validation_error")?.let { toast(ctx, "WhatsApp: $it") }
    }
    val apps = remember { listOf("com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "Business").filter { runCatching { ctx.packageManager.getPackageInfo(it.first, 0) }.isSuccess } }
    LaunchedEffect(tick) { val r = withContext(Dispatchers.IO) { findStickers() to myPacks(ctx) }; found = r.first; packs = r.second }
    fun addTo(pkg: String, id: String, n: String) {
        try {
            launcher.launch(Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").setPackage(pkg).putExtra("sticker_pack_id", id)
                .putExtra("sticker_pack_authority", ctx.packageName + ".stickercontentprovider").putExtra("sticker_pack_name", n))
        } catch (e: Exception) { toast(ctx, "Update WhatsApp to the latest version") }
    }
    val list = found ?: emptyList()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Column {
                Text("WhatsApp stickers", style = MaterialTheme.typography.titleLarge)
                Text(if (found == null) "Searching WhatsApp folders…" else "${list.size} found · tap 3–30 to build a pack",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        LazyVerticalGrid(GridCells.Adaptive(92.dp), Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Panel(Modifier.fillMaxWidth()) {
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Pack name") }, singleLine = true)
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Selected ${sel.size}/30", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            TextButton(onClick = { sel = list.take(30).toSet() }) { Text("First 30") }
                            TextButton(onClick = { sel = emptySet() }) { Text("Clear") }
                        }
                        if (apps.isEmpty()) Text("WhatsApp isn’t installed.", color = cs.error)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            apps.forEach { (pkg, label) ->
                                BigButton(if (busy) "Preparing…" else "Add to $label", Modifier.weight(1f), enabled = sel.size in 3..30 && !busy) {
                                    busy = true
                                    val title = name.trim().ifEmpty { "My Stickers" }; val picks = list.filter { it in sel }
                                    scope.launch {
                                        val id = withContext(Dispatchers.IO) { buildPack(ctx, title, picks) }
                                        busy = false
                                        if (id == null) toast(ctx, "Need at least 3 usable stickers of the same kind (all static or all animated)")
                                        else { sel = emptySet(); tick++; addTo(pkg, id, title) }
                                    }
                                }
                            }
                        }
                    }
                    if (packs.isNotEmpty()) SectionTitle("My sticker packs")
                    packs.forEach { d ->
                        val pn = File(d, "name.txt").readText()
                        Panel(Modifier.fillMaxWidth().padding(bottom = 8.dp), padding = 12.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(Icons.Rounded.InsertEmoticon, cs.primary, 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(pn, Modifier.weight(1f), maxLines = 1)
                                apps.forEach { (pkg, label) -> TextButton(onClick = { addTo(pkg, d.name, pn) }) { Text("Re-add · $label") } }
                                IconButton(onClick = { d.deleteRecursively(); tick++ }) { Icon(Icons.Rounded.Close, "Remove pack") }
                            }
                        }
                    }
                    if (found != null && list.isEmpty()) EmptyState(Icons.Rounded.InsertEmoticon, "No stickers found", "WhatsApp only keeps stickers you received or sent on this phone.")
                    if (list.isNotEmpty()) SectionTitle("From WhatsApp")
                }
            }
            items(list, key = { it.path }) { f -> StickerCell(f, f in sel) { sel = if (f in sel) sel - f else if (sel.size < 30) sel + f else sel } }
        }
    }
}
