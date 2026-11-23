package com.example.smartfiles

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        setContent {
            val dark = isDark()
            // Status/navigation bar icons must follow the app's theme, not just the system's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                )
                onDispose { }
            }
            SmartTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App() }
            }
        }
    }
}

// ---------- Small shared helpers ----------
fun fmt(b: Long): String {
    if (b < 1024) return "$b B"
    var v = b / 1024.0; var i = 0
    while (v >= 1024 && i < 3) { v /= 1024; i++ }
    return "%.1f %cB".format(v, "KMGT"[i])
}

/** Shows a snackbar (falls back to a Toast before the UI exists). */
fun toast(c: Context, m: String) { if (Notify.ready) Notify.say(m) else Toast.makeText(c, m, Toast.LENGTH_SHORT).show() }

/** Starts a system screen; some phones don't have every settings page. */
fun safeStart(c: Context, i: Intent) {
    try { c.startActivity(i) } catch (e: Exception) { toast(c, "This screen isn’t available on your phone") }
}

fun openFile(c: Context, f: File) {
    try {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.fp", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: ActivityNotFoundException) { toast(c, "No app can open this file")
    } catch (e: Exception) { toast(c, "Couldn’t open this file") }
}

/** Runs [cb] every time the screen comes back to the foreground. */
@Composable fun OnResume(skipFirst: Boolean = false, cb: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(cb)
    DisposableEffect(owner) {
        var first = skipFirst
        val ob = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { if (first) first = false else latest() }
        }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob) }
    }
}

// ---------- App shell ----------
private class NavTab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    NavTab("Files", Icons.Rounded.Folder),
    NavTab("Clean", Icons.Rounded.CleaningServices),
    NavTab("Apps", Icons.Rounded.Apps),
    NavTab("Phone", Icons.Rounded.PhoneAndroid),
)

@Composable fun App() {
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    SideEffect { Notify.host = snack; Notify.scope = scope }

    var ok by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    OnResume { ok = Environment.isExternalStorageManager(); Session.roots = volumes(ctx).map { it.dir } }
    // Clear out Bin items older than 30 days, off the main thread.
    LaunchedEffect(ok) { if (ok) withContext(Dispatchers.IO) { purgeOldBin(volumes(ctx).map { it.dir }) } }

    var tab by rememberSaveable { mutableStateOf(0) }
    BackHandler(ok && tab != 0) { tab = 0 }
    val holder = rememberSaveableStateHolder()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (ok) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label, maxLines = 1) })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (!ok) PermissionGate()
            else Crossfade(targetState = tab, label = "tab") { t ->
                // Each tab keeps its own scroll position, search text and sort order while you visit the others.
                holder.SaveableStateProvider(t) {
                    when (t) { 0 -> FilesScreen(); 1 -> CleanScreen(); 2 -> AppsScreen(); else -> PhoneScreen(goTab = { tab = it }) }
                }
            }
        }
    }
}

@Composable fun PermissionGate() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        IconBadge(Icons.Rounded.Folder, MaterialTheme.colorScheme.primary, 88.dp)
        Spacer(Modifier.height(24.dp))
        Text("Welcome to Smart Files", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("To browse, clean and organise your storage, Android needs you to turn on “All files access”. Smart Files works fully offline. Nothing ever leaves your phone.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        BigButton("Grant access", Modifier.fillMaxWidth()) {
            val pkg = Uri.parse("package:${ctx.packageName}")
            try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)) }
            catch (e: Exception) { safeStart(ctx, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }
}
