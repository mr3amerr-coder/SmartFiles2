package com.example.smartfiles

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

// ---------- Messages (snackbars, with optional Undo) ----------
object Notify {
    lateinit var host: SnackbarHostState
    lateinit var scope: CoroutineScope
    val ready: Boolean get() = ::host.isInitialized && ::scope.isInitialized

    fun say(msg: String, action: String? = null, onAction: (() -> Unit)? = null) {
        if (!ready) return
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val r = host.showSnackbar(msg, action, duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

// ---------- File kinds: one place decides the icon, colour and label for everything ----------
enum class Kind(val label: String, val icon: ImageVector, val color: Color) {
    Folder("Folders", Icons.Rounded.Folder, Color(0xFFF2A93B)),
    Image("Images", Icons.Rounded.Image, Color(0xFF2FB87D)),
    Video("Videos", Icons.Rounded.Movie, Color(0xFFE5484D)),
    Audio("Audio", Icons.Rounded.MusicNote, Color(0xFF8E6CF5)),
    Doc("Documents", Icons.Rounded.Description, Color(0xFF3D7BFA)),
    Apk("App installers", Icons.Rounded.Android, Color(0xFF3DDC84)),
    Archive("Archives", Icons.Rounded.Archive, Color(0xFFEF8A3C)),
    Other("Other", Icons.Rounded.InsertDriveFile, Color(0xFF8A93A6)),
}

val THUMB_EXT = setOf("jpg", "jpeg", "png", "gif", "webp")
val TEXT_EXT = setOf("txt", "md", "json", "csv", "xml", "log", "html")

fun kindOfExt(e: String): Kind = when (e) {
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp" -> Kind.Image
    "mp4", "mkv", "avi", "mov", "3gp", "webm", "m4v" -> Kind.Video
    "mp3", "wav", "m4a", "flac", "ogg", "opus", "aac", "amr" -> Kind.Audio
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "rtf", "odt", "epub" -> Kind.Doc
    "apk" -> Kind.Apk
    "zip", "rar", "7z", "tar", "gz" -> Kind.Archive
    else -> Kind.Other
}

fun kindOf(f: File): Kind = if (f.isDirectory) Kind.Folder else kindOfExt(f.extension.lowercase())

// ---------- Building blocks ----------
@Composable fun Panel(
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val color = MaterialTheme.colorScheme.surfaceContainer
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    if (onClick == null) Surface(modifier = modifier, shape = shape, color = color, border = border) {
        Column(Modifier.padding(padding), content = content)
    } else Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = border) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
}

@Composable fun ScreenHeader(title: String, sub: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

/** A rounded, tinted square holding an icon. Used for files, apps, tweaks, everything. */
@Composable fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size / 3)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size / 2), tint = tint)
    }
}

/** Pill-style segmented control. Pass selected = -1 when none of the options matches the current value. */
@Composable fun Seg(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHigh).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            val bg by animateColorAsState(if (on) cs.primary else Color.Transparent, label = "seg")
            Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(bg).clickable { onSelect(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center) {
                Text(o, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    color = if (on) cs.onPrimary else cs.onSurfaceVariant)
            }
        }
    }
}

@Composable fun ToggleRow(icon: ImageVector, title: String, sub: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, MaterialTheme.colorScheme.primary, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable fun BigButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(16.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text)
    }
}

@Composable fun EmptyState(icon: ImageVector, title: String, sub: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(icon, MaterialTheme.colorScheme.outline, 72.dp)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
