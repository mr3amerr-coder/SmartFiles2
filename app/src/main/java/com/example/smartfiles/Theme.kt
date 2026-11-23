package com.example.smartfiles

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** User settings: saved in SharedPreferences and observable from Compose. */
object Prefs {
    private var sp: SharedPreferences? = null

    /** 0 = follow system, 1 = light, 2 = dark */
    var theme by mutableIntStateOf(0)
        private set
    var wallpaper by mutableStateOf(false)
        private set
    var amoled by mutableStateOf(false)
        private set
    var hidden by mutableStateOf(false)
        private set

    fun init(c: Context) {
        val p = c.getSharedPreferences("smartfiles", Context.MODE_PRIVATE)
        sp = p
        theme = p.getInt("theme", 0)
        wallpaper = p.getBoolean("dynamic", false)
        amoled = p.getBoolean("amoled", false)
        hidden = p.getBoolean("hidden", false)
    }

    fun setTheme(v: Int) { theme = v; sp?.edit()?.putInt("theme", v)?.apply() }
    fun setWallpaper(v: Boolean) { wallpaper = v; sp?.edit()?.putBoolean("dynamic", v)?.apply() }
    fun setAmoled(v: Boolean) { amoled = v; sp?.edit()?.putBoolean("amoled", v)?.apply() }
    fun setHidden(v: Boolean) { hidden = v; sp?.edit()?.putBoolean("hidden", v)?.apply() }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D5AFE), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF), onPrimaryContainer = Color(0xFF0A1B7A),
    secondary = Color(0xFF5A6384), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E4F7), onSecondaryContainer = Color(0xFF171E3D),
    tertiary = Color(0xFF00897B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBDF2EA), onTertiaryContainer = Color(0xFF00201C),
    background = Color(0xFFF4F5FA), onBackground = Color(0xFF12141A),
    surface = Color(0xFFF4F5FA), onSurface = Color(0xFF12141A),
    surfaceVariant = Color(0xFFE4E7F0), onSurfaceVariant = Color(0xFF585E70),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F2F8),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFE9ECF4), surfaceContainerHighest = Color(0xFFE2E6F0),
    outline = Color(0xFF8A90A2), outlineVariant = Color(0xFFD5D9E4),
    error = Color(0xFFD93025), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FB0FF), onPrimary = Color(0xFF0A1B7A),
    primaryContainer = Color(0xFF26338C), onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFFB9C2E6), onSecondary = Color(0xFF232B4B),
    secondaryContainer = Color(0xFF2E3658), onSecondaryContainer = Color(0xFFDCE1FA),
    tertiary = Color(0xFF5ED6C8), onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF005046), onTertiaryContainer = Color(0xFFBDF2EA),
    background = Color(0xFF0E1016), onBackground = Color(0xFFE6E8F0),
    surface = Color(0xFF0E1016), onSurface = Color(0xFFE6E8F0),
    surfaceVariant = Color(0xFF262A37), onSurfaceVariant = Color(0xFFA6ACBD),
    surfaceContainerLowest = Color(0xFF090A0F), surfaceContainerLow = Color(0xFF13151C),
    surfaceContainer = Color(0xFF171A22), surfaceContainerHigh = Color(0xFF1F222C), surfaceContainerHighest = Color(0xFF282C38),
    outline = Color(0xFF6C7286), outlineVariant = Color(0xFF2F3342),
    error = Color(0xFFFF8A80), onError = Color(0xFF410002),
    errorContainer = Color(0xFF5C1A15), onErrorContainer = Color(0xFFFFDAD6),
)

private val baseType = Typography()
private val SmartType = Typography(
    headlineSmall = baseType.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = baseType.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = baseType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = baseType.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = baseType.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val SmartShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)

@Composable fun isDark(): Boolean = when (Prefs.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }

@Composable fun SmartTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isDark()
    var cs = when {
        Prefs.wallpaper && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    if (dark && Prefs.amoled) cs = cs.copy(
        background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF08080A), surfaceContainer = Color(0xFF101013),
        surfaceContainerHigh = Color(0xFF18181C), surfaceContainerHighest = Color(0xFF212126),
    )
    MaterialTheme(colorScheme = cs, typography = SmartType, shapes = SmartShapes, content = content)
}
