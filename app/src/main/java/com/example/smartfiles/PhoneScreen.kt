package com.example.smartfiles

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.InsertEmoticon
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun PhoneScreen(goTab: (Int) -> Unit) {
    var sub by rememberSaveable { mutableStateOf(0) }
    if (sub == 1) {
        BackHandler { sub = 0 }
        StickersScreen(onBack = { sub = 0 })
    } else PhoneMain(goTab = goTab, openStickers = { sub = 1 })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PhoneMain(goTab: (Int) -> Unit, openStickers: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    // `live` refreshes the gauges every few seconds; `key` re-reads system settings after a change or on return from Settings.
    var live by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(4000); live++ } }
    var key by remember { mutableIntStateOf(0) }
    OnResume(skipFirst = true) { key++; live++ }

    val st = remember(live) { readStats(ctx) }
    val diskUsed = remember(live) { StatFs(Environment.getExternalStorageDirectory().path).let { 1f - it.availableBytes.toFloat() / it.totalBytes.coerceAtLeast(1L) } }
    val writeOk = remember(key) { canWriteSettings(ctx) }
    val secureOk = remember(key) { canWriteSecure(ctx) }
    val tw = remember(key) { readTweaks(ctx) }
    var brightness by remember(key) { mutableFloatStateOf(sysInt(ctx, Settings.System.SCREEN_BRIGHTNESS, 128).toFloat()) }
    var boosting by remember { mutableStateOf(false) }
    var boostMsg by remember { mutableStateOf<String?>(null) }
    var setup by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }

    fun askWriteAccess() {
        toast(ctx, "Turn on “Modify system settings” for Smart Files")
        safeStart(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }
    fun withWrite(block: () -> Unit) { if (writeOk) { block(); key++ } else askWriteAccess() }

    fun boost() {
        if (boosting) return
        boosting = true; boostMsg = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { boostMemory(ctx) }
            boosting = false; live++
            boostMsg = if (r.after - r.before > 50L * 1024 * 1024) "Free memory: ${fmt(r.before)} → ${fmt(r.after)}"
                       else "Memory was already in good shape (${fmt(r.after)} free)."
        }
    }

    fun applyPreset(p: Preset) {
        if (!writeOk && !secureOk) { askWriteAccess(); return }
        val before = readTweaks(ctx)
        val skipped = applyTweaks(ctx, p.t)
        key++
        Notify.say(p.title + " applied" + if (skipped) " · animation speed needs setup" else "", "Undo") { applyTweaks(ctx, before); key++ }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenHeader("Phone", deviceName()) {
            IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.Settings, "Settings") }
        }

        // ---- Health ----
        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Ring(diskUsed, "Storage", "${(diskUsed * 100).toInt()}%", "used", usageColor(diskUsed))
                val ramUsed = 1f - st.ramFree.toFloat() / st.ramTotal.coerceAtLeast(1L)
                Ring(ramUsed, "Memory", "${(ramUsed * 100).toInt()}%", "${fmt(st.ramFree)} free", usageColor(ramUsed))
                val b = st.battery.coerceAtLeast(0) / 100f
                Ring(b, "Battery", if (st.battery >= 0) "${st.battery}%" else "–", if (st.charging) "charging" else "%.0f°C".format(st.tempC),
                    if (st.charging) Color(0xFF2FB87D) else if (b < 0.2f) cs.error else if (b < 0.5f) Color(0xFFF2A93B) else Color(0xFF2FB87D))
            }
        }

        // ---- Quick actions ----
        SectionTitle("Quick actions")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile("Boost", Icons.Rounded.Memory, cs.primary, Modifier.weight(1f), busy = boosting) { boost() }
            ActionTile("Clear caches", Icons.Rounded.CleaningServices, Color(0xFF2FB8C9), Modifier.weight(1f)) {
                safeStart(ctx, Intent(android.os.storage.StorageManager.ACTION_CLEAR_APP_CACHE))
            }
            ActionTile("Find junk", Icons.Rounded.Storage, Color(0xFFF2A93B), Modifier.weight(1f)) { goTab(1) }
        }
        Text(boostMsg ?: "Boost asks Android to drop background processes of apps you installed. Android manages memory itself, so the gain is short-term.",
            Modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = if (boostMsg != null) cs.primary else cs.onSurfaceVariant)

        // ---- Presets ----
        SectionTitle("One-tap presets")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Preset.entries.forEach { p ->
                Panel(Modifier.weight(1f), onClick = { applyPreset(p) }, padding = 12.dp) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconBadge(p.icon, p.color, 44.dp)
                        Text(p.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                        Text(p.sub, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = cs.onSurfaceVariant)
                    }
                }
            }
        }

        // ---- Tweaks ----
        SectionTitle("Fine-tune")
        Panel(Modifier.fillMaxWidth()) {
            if (!writeOk) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow Smart Files to change screen and sound settings.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    TextButton(onClick = { askWriteAccess() }) { Text("Allow") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }

            TweakTitle(Icons.Rounded.Animation, "Animation speed", if (secureOk) "Faster animations make the phone feel quicker" else "Needs a one-time setup")
            val a = tw.anim
            Seg(listOf("Off", "0.5×", "1×"), when (a) { 0f -> 0; 0.5f -> 1; 1f -> 2; else -> -1 }, Modifier.padding(top = 8.dp, bottom = 12.dp)) { i ->
                if (!secureOk) setup = true else { setAnimScale(ctx, floatArrayOf(0f, 0.5f, 1f)[i]); key++ }
            }

            TweakTitle(Icons.Rounded.Timer, "Screen timeout", "Shorter saves battery")
            Seg(TIMEOUT_LABELS, TIMEOUT_MS.indexOf(tw.timeout), Modifier.padding(top = 8.dp, bottom = 8.dp)) { i ->
                withWrite { putSysInt(ctx, Settings.System.SCREEN_OFF_TIMEOUT, TIMEOUT_MS[i]) }
            }

            ToggleRow(Icons.Rounded.Brightness4, "Adaptive brightness", "Let the phone pick the level", tw.adaptive == 1) { on ->
                withWrite { putSysInt(ctx, Settings.System.SCREEN_BRIGHTNESS_MODE, if (on) 1 else 0) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Brightness6, null, tint = cs.onSurfaceVariant)
                Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 1f..255f, enabled = tw.adaptive != 1, modifier = Modifier.weight(1f).padding(start = 12.dp),
                    onValueChangeFinished = { withWrite { putSysInt(ctx, Settings.System.SCREEN_BRIGHTNESS, brightness.toInt()) } })
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ToggleRow(Icons.Rounded.ScreenRotation, "Auto-rotate", null, sysInt(ctx, Settings.System.ACCELEROMETER_ROTATION, 1) == 1) { on ->
                withWrite { putSysInt(ctx, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0) }
            }
            ToggleRow(Icons.Rounded.VolumeUp, "Touch sounds", null, tw.sound == 1) { on ->
                withWrite { putSysInt(ctx, Settings.System.SOUND_EFFECTS_ENABLED, if (on) 1 else 0) }
            }
            ToggleRow(Icons.Rounded.Vibration, "Touch vibration", null, tw.haptic == 1) { on ->
                withWrite { putSysInt(ctx, Settings.System.HAPTIC_FEEDBACK_ENABLED, if (on) 1 else 0) }
            }
        }

        // ---- Shortcuts ----
        SectionTitle("System shortcuts")
        SHORTCUTS.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { s ->
                    Panel(Modifier.weight(1f), onClick = { safeStart(ctx, Intent(s.action)) }, padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp), tint = cs.outline)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        // ---- Tools ----
        SectionTitle("Tools")
        Panel(Modifier.fillMaxWidth(), onClick = openStickers) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.InsertEmoticon, Color(0xFF2FB87D), 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("WhatsApp stickers", style = MaterialTheme.typography.titleSmall)
                    Text("Rescue received stickers and re-add them as a pack", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = cs.outline)
            }
        }

        // ---- About ----
        SectionTitle("About this phone")
        Panel(Modifier.fillMaxWidth()) {
            InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            InfoRow("Security patch", Build.VERSION.SECURITY_PATCH)
            InfoRow("Kernel", System.getProperty("os.version") ?: "–")
            InfoRow("Memory", fmt(st.ramTotal))
            InfoRow("Battery health", st.health)
            InfoRow("Up for", uptimeText())
        }
        Spacer(Modifier.height(24.dp))
    }

    if (setup) AlertDialog(onDismissRequest = { setup = false }, title = { Text("One-time setup") },
        text = {
            Text("Android only lets apps change animation speed after a one-time permission from a computer. Connect your phone with USB debugging on and run:\n\n${adbGrantCommand(ctx)}\n\nThen come back here. It stays granted.")
        },
        confirmButton = {
            TextButton(onClick = { copyText(ctx, "adb command", adbGrantCommand(ctx)); setup = false; toast(ctx, "Command copied") }) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy command")
            }
        },
        dismissButton = { TextButton(onClick = { setup = false }) { Text("Close") } })

    if (settings) ModalBottomSheet(onDismissRequest = { settings = false }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            SectionTitle("Appearance")
            Seg(listOf("System", "Light", "Dark"), Prefs.theme) { Prefs.setTheme(it) }
            ToggleRow(Icons.Rounded.Brightness4, "Pure black in dark mode", "Saves battery on OLED screens", Prefs.amoled) { Prefs.setAmoled(it) }
            ToggleRow(Icons.Rounded.Palette, "Match wallpaper colours", if (Build.VERSION.SDK_INT >= 31) "Use Material You colours" else "Needs Android 12 or newer",
                Prefs.wallpaper, enabled = Build.VERSION.SDK_INT >= 31) { Prefs.setWallpaper(it) }
            SectionTitle("Files")
            ToggleRow(Icons.Rounded.Visibility, "Show hidden files", null, Prefs.hidden) { Prefs.setHidden(it) }
            val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
            Text("Smart Files $ver", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

private fun usageColor(used: Float): Color = if (used > 0.9f) Color(0xFFE5484D) else if (used > 0.75f) Color(0xFFF2A93B) else Color(0xFF3D7BFA)

@Composable private fun Ring(value: Float, label: String, center: String, caption: String, color: Color) {
    val a by animateFloatAsState(value.coerceIn(0f, 1f), tween(900), label = "ring")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { a }, modifier = Modifier.size(78.dp), color = color, trackColor = color.copy(alpha = 0.16f),
                strokeWidth = 8.dp, strokeCap = StrokeCap.Round)
            Text(center, style = MaterialTheme.typography.titleSmall)
        }
        Text(label, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelLarge)
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ActionTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, modifier: Modifier, busy: Boolean = false, onClick: () -> Unit) {
    Panel(modifier, onClick = onClick, padding = 12.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (busy) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp) }
            else IconBadge(icon, tint, 44.dp)
            Text(if (busy) "Working…" else label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable private fun TweakTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, MaterialTheme.colorScheme.primary, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(k, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v)
    }
}
