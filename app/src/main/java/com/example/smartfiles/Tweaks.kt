package com.example.smartfiles

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

// =====================================================================
//  Live phone readings
// =====================================================================
data class PhoneStats(
    val ramTotal: Long, val ramFree: Long,
    val battery: Int, val tempC: Float, val charging: Boolean, val health: String,
)

fun readStats(c: Context): PhoneStats {
    val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    // Battery is a "sticky" broadcast: passing a null receiver just returns the latest value.
    val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    return PhoneStats(
        ramTotal = mi.totalMem, ramFree = mi.availMem,
        battery = if (level >= 0 && scale > 0) level * 100 / scale else -1,
        tempC = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f,
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        health = when (b?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Weak"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Fault"
            else -> "Unknown"
        },
    )
}

fun uptimeText(): String {
    val m = SystemClock.elapsedRealtime() / 60_000
    val d = m / 1440; val h = (m % 1440) / 60; val mm = m % 60
    return if (d > 0) "${d}d ${h}h" else if (h > 0) "${h}h ${mm}m" else "${mm}m"
}

fun deviceName(): String =
    (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).trim()

// =====================================================================
//  Boost: ask Android to drop background processes of apps you installed
// =====================================================================
class BoostResult(val before: Long, val after: Long)

/** Apps we never touch: ourselves, the launcher (so the home screen doesn't reload) and the keyboard. */
private fun protectedPackages(c: Context): Set<String> {
    val pm = c.packageManager
    val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    val ime = Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
    return setOfNotNull(c.packageName, home, ime)
}

/** Blocking (about a second): call from a background thread. */
fun boostMemory(c: Context): BoostResult {
    val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    fun avail() = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
    val before = avail()
    val keep = protectedPackages(c)
    c.packageManager.getInstalledApplications(0)
        .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName !in keep }
        .forEach { runCatching { am.killBackgroundProcesses(it.packageName) } }
    Thread.sleep(900)   // give the system a moment to reclaim the memory before measuring again
    return BoostResult(before, avail())
}

// =====================================================================
//  System tweaks
// =====================================================================
/** Needs the "Modify system settings" special access, which the user grants in one tap. */
fun canWriteSettings(c: Context) = Settings.System.canWrite(c)

/** Needs a one-time `adb shell pm grant`: Android doesn't let apps hold this permission any other way. */
fun canWriteSecure(c: Context) = c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

fun adbGrantCommand(c: Context) = "adb shell pm grant ${c.packageName} android.permission.WRITE_SECURE_SETTINGS"

fun copyText(c: Context, label: String, text: String) {
    (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
}

fun sysInt(c: Context, key: String, def: Int): Int = runCatching { Settings.System.getInt(c.contentResolver, key, def) }.getOrDefault(def)
fun putSysInt(c: Context, key: String, v: Int): Boolean = runCatching { Settings.System.putInt(c.contentResolver, key, v) }.getOrDefault(false)

fun animScale(c: Context): Float = runCatching { Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

fun setAnimScale(c: Context, v: Float): Boolean = runCatching {
    val cr = c.contentResolver
    Settings.Global.putFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, v)
    Settings.Global.putFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, v)
    Settings.Global.putFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, v)
    true
}.getOrDefault(false)

val TIMEOUT_LABELS = listOf("15s", "30s", "1m", "2m", "5m", "10m")
val TIMEOUT_MS = listOf(15_000, 30_000, 60_000, 120_000, 300_000, 600_000)

/** Everything the presets touch, so a preset can be undone. */
data class Tweaks(val timeout: Int, val adaptive: Int, val haptic: Int, val sound: Int, val anim: Float)

fun readTweaks(c: Context) = Tweaks(
    timeout = sysInt(c, Settings.System.SCREEN_OFF_TIMEOUT, 60_000),
    adaptive = sysInt(c, Settings.System.SCREEN_BRIGHTNESS_MODE, 0),
    haptic = sysInt(c, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1),
    sound = sysInt(c, Settings.System.SOUND_EFFECTS_ENABLED, 1),
    anim = animScale(c),
)

/** Applies what the granted permissions allow. Returns true when the animation speed was skipped for lack of the adb grant. */
fun applyTweaks(c: Context, t: Tweaks): Boolean {
    if (canWriteSettings(c)) {
        putSysInt(c, Settings.System.SCREEN_OFF_TIMEOUT, t.timeout)
        putSysInt(c, Settings.System.SCREEN_BRIGHTNESS_MODE, t.adaptive)
        putSysInt(c, Settings.System.HAPTIC_FEEDBACK_ENABLED, t.haptic)
        putSysInt(c, Settings.System.SOUND_EFFECTS_ENABLED, t.sound)
    }
    return if (canWriteSecure(c)) { setAnimScale(c, t.anim); false } else true
}

enum class Preset(val title: String, val sub: String, val icon: ImageVector, val color: Color, val t: Tweaks) {
    Battery("Battery saver", "Short timeout, quiet", Icons.Rounded.BatteryFull, Color(0xFF2FB87D), Tweaks(30_000, 1, 0, 0, 0.5f)),
    Balanced("Balanced", "Everyday defaults", Icons.Rounded.FlashOn, Color(0xFF3D7BFA), Tweaks(60_000, 1, 1, 1, 1f)),
    Snappy("Snappy", "Faster animations", Icons.Rounded.Speed, Color(0xFFF2A93B), Tweaks(120_000, 1, 1, 0, 0.5f)),
}

// ---- Shortcuts to system screens that matter for speed and battery ----
class Shortcut(val label: String, val action: String)

val SHORTCUTS = listOf(
    Shortcut("Battery saver", Settings.ACTION_BATTERY_SAVER_SETTINGS),
    Shortcut("Battery optimisation", Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    Shortcut("Display", Settings.ACTION_DISPLAY_SETTINGS),
    Shortcut("Data usage", Settings.ACTION_DATA_USAGE_SETTINGS),
    Shortcut("All apps", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
    Shortcut("Default apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
    Shortcut("Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
    Shortcut("Developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
)
