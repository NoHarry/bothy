package cc.noharry.bothy.app.phone

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import cc.noharry.bothy.app.console.ConsoleAppearance
import cc.noharry.bothy.app.console.ConsoleThemeColors
import cc.noharry.bothy.app.console.LocalConsoleColors
import cc.noharry.bothy.app.console.materialColors

/**
 * The phone's look, after its design in Stitch ("Bothy 手机端", v2): a paper ground, white cards with a hairline, pine
 * green for what acts, amber for what waits. It stands in for the console's colours, so the panes the phone borrows
 * from the tablet (the settings, a reply's text, the full trajectory) take it on too.
 */
internal val LightPhoneColors = ConsoleThemeColors(
    bg = Color(0xFFFBF9F4),
    panel = Color(0xFFF5F4EE),
    elevated = Color(0xFFFFFFFF),
    border = Color(0xFFE2DFD8),
    borderSubtle = Color(0xFFE9E8E3),
    text = Color(0xFF1B1C19),
    muted = Color(0xFF56615B),
    tertiary = Color(0xFF6F7973),
    accent = Color(0xFF005238),
    accentSoft = Color(0xFFDAE5DD),
    ink = Color(0xFF1B1C19),
    inkSoft = Color(0xFF3F4943),
    activity = Color(0xFF904E00),
    activitySoft = Color(0xFFFFDCC2),
    danger = Color(0xFFBA1A1A),
    success = Color(0xFF1F6B4E),
    warning = Color(0xFF904E00),
    userBubble = Color(0xFFD6E5D8),
    codeBg = Color(0xFFEFEEE9),
    isDark = false,
)

/** The design's dark screen: warm charcoal, cards a step lighter, mint for what acts. */
internal val DarkPhoneColors = ConsoleThemeColors(
    bg = Color(0xFF1B1A17),
    panel = Color(0xFF21201D),
    elevated = Color(0xFF2A2925),
    border = Color(0xFF3A3934),
    borderSubtle = Color(0xFF31302B),
    text = Color(0xFFEDEAE2),
    muted = Color(0xFFA3A49C),
    tertiary = Color(0xFF85867E),
    accent = Color(0xFF78D5A3),
    accentSoft = Color(0xFF1F362B),
    ink = Color(0xFFEDEAE2),
    inkSoft = Color(0xFFC9C6BE),
    activity = Color(0xFFFFB77A),
    activitySoft = Color(0xFF3A2A1C),
    danger = Color(0xFFFFB4AB),
    success = Color(0xFF78D5A3),
    warning = Color(0xFFFFB77A),
    userBubble = Color(0xFF24493A),
    codeBg = Color(0xFF161512),
    isDark = true,
)

/** The design's type: headlines for titles and the greeting, body-lg for a reply, labels for what is said about it. */
internal object PhoneType {
    val greeting = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val cardTitle = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val reply = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val label = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
    val tool = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace)
}

/** The phone's colours for the app's appearance (light, dark, or the system's). */
@Composable
internal fun phoneColors(): ConsoleThemeColors {
    val dark = when (ConsoleAppearance.current) {
        ConsoleAppearance.Mode.LIGHT -> false
        ConsoleAppearance.Mode.DARK -> true
        ConsoleAppearance.Mode.SYSTEM -> isSystemInDarkTheme()
    }
    return if (dark) DarkPhoneColors else LightPhoneColors
}

/** The phone's colours and type over [content], and its ground under the status bar. */
@Composable
internal fun PhoneTheme(content: @Composable () -> Unit) {
    val colors = phoneColors()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colors.bg.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !colors.isDark
        }
    }
    CompositionLocalProvider(LocalConsoleColors provides colors) {
        MaterialTheme(colorScheme = materialColors(colors), typography = PhoneTypography, content = content)
    }
}

/**
 * The tablet's panes on a phone: their type a little larger, so the console's 14sp body reads as the phone's 16sp
 * reply. The user's own font scale still applies.
 */
@Composable
internal fun ConsoleTypeOnPhone(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * CONSOLE_TYPE_ON_PHONE), content = content)
}

private val PhoneTypography = Typography(
    titleLarge = PhoneType.title,
    titleMedium = PhoneType.cardTitle,
    bodyLarge = PhoneType.reply,
    bodyMedium = PhoneType.body,
    bodySmall = PhoneType.small,
    labelLarge = PhoneType.label,
    labelMedium = PhoneType.label,
    labelSmall = PhoneType.small,
)

/** The margin a phone page keeps at either side (the design's 20px). */
internal val PHONE_MARGIN = 20.dp

private const val CONSOLE_TYPE_ON_PHONE = 16f / 14f
