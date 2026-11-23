package com.example.smartfiles

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class AppRow(val label: String, val pkg: String, val size: Long, val last: Long, val installed: Long)

fun usageOk(c: Context): Boolean {
    val a = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return a.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
}

/** Real app size (app + data) when Android lets us ask; falls back to the APK size. */
fun appSize(c: Context, a: ApplicationInfo): Long = runCatching {
    val s = (c.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager)
        .queryStatsForPackage(StorageManager.UUID_DEFAULT, a.packageName, Process.myUserHandle())
    s.appBytes + s.dataBytes
}.getOrElse { File(a.sourceDir).length() }

fun loadApps(c: Context): List<AppRow> {
    val pm = c.packageManager; val now = System.currentTimeMillis()
    val stats = (c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).queryAndAggregateUsageStats(now - 90L * DAY, now)
    return pm.getInstalledPackages(0).mapNotNull { p ->
        val a = p.applicationInfo ?: return@mapNotNull null
        if ((a.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || a.packageName == c.packageName || pm.getLaunchIntentForPackage(a.packageName) == null) null
        else AppRow(pm.getApplicationLabel(a).toString(), a.packageName, appSize(c, a), stats[a.packageName]?.lastTimeUsed ?: 0L, p.firstInstallTime)
    }.sortedWith(compareBy<AppRow> { maxOf(it.last, it.installed) }.thenByDescending { it.size })
}

private val appIcons = android.util.LruCache<String, ImageBitmap>(150)

@Composable private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(appIcons.get(pkg), pkg) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(112, 112).asImageBitmap() }.getOrNull()
        }?.also { appIcons.put(pkg, it) }
    }
    val b = bmp
    if (b != null) Image(b, null, Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)))
    else IconBadge(Icons.Rounded.Apps, MaterialTheme.colorScheme.outline, 46.dp)
}

@Composable fun AppsScreen() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var hasUsage by remember { mutableStateOf(usageOk(ctx)) }
    var apps by remember { mutableStateOf<List<AppRow>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    OnResume(skipFirst = true) { hasUsage = usageOk(ctx); tick++ }
    var bySize by rememberSaveable { mutableStateOf(false) }
    var q by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(tick, hasUsage) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }

    val now = System.currentTimeMillis()
    // A new app that was never opened isn't "unused" yet: count from install time.
    fun stale(a: AppRow) = hasUsage && now - maxOf(a.last, a.installed) > 30 * DAY
    fun ago(t: Long): String = when (val d = (now - t) / DAY) { 0L -> "today"; 1L -> "yesterday"; else -> "$d days ago" }

    val old = remember(apps, hasUsage) { (apps ?: emptyList()).filter { stale(it) } }
    val shown = remember(apps, bySize, q) {
        val l = (apps ?: emptyList()).filter { it.label.contains(q, true) }
        if (bySize) l.sortedByDescending { it.size } else l
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ScreenHeader("Apps", if (apps == null) "Loading…" else "${apps!!.size} installed")

        if (!hasUsage) Panel(Modifier.fillMaxWidth()) {
            Text("Spot apps you never open", style = MaterialTheme.typography.titleMedium)
            Text("Allow usage access so Smart Files can tell which apps have gone unused.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            BigButton("Allow usage access", Modifier.fillMaxWidth().padding(top = 12.dp)) { safeStart(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        } else Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${old.size} unused apps", style = MaterialTheme.typography.titleMedium)
                    Text("Not opened for 30+ days", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Text(fmt(old.sumOf { it.size }), style = MaterialTheme.typography.headlineSmall, color = cs.primary)
            }
            Text("to reclaim if you uninstall them", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }

        TextField(
            value = q, onValueChange = { q = it }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            placeholder = { Text("Search apps") }, singleLine = true, shape = RoundedCornerShape(28.dp),
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = cs.surfaceContainerHigh, unfocusedContainerColor = cs.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
            ),
        )
        Seg(listOf("Unused first", "Largest first"), if (bySize) 1 else 0, Modifier.padding(bottom = 6.dp)) { bySize = it == 1 }
        if (apps == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(shown, key = { it.pkg }) { a ->
                val isStale = stale(a)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(a.pkg)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val use = when {
                            !hasUsage -> "usage unknown"
                            a.last == 0L && now - a.installed < 30 * DAY -> "installed ${ago(a.installed)}, not opened yet"
                            a.last == 0L -> "not used in 90+ days"
                            else -> "last used ${ago(a.last)}"
                        }
                        Text("${fmt(a.size)} · $use", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (isStale) cs.error else cs.onSurfaceVariant)
                    }
                    IconButton(onClick = { safeStart(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))) }) {
                        Icon(Icons.Rounded.Info, "App info", tint = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = { safeStart(ctx, Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}"))) }) {
                        Icon(Icons.Rounded.Delete, "Uninstall", tint = cs.error)
                    }
                }
            }
        }
    }
}
