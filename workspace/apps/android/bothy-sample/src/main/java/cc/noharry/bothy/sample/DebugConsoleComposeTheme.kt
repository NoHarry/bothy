package cc.noharry.bothy.sample

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bothy's console look, after a mountain shelter at dusk:
 * - Warm oat and stone neutrals in light, deep forest surfaces in dark, pine and sage green as the brand accent
 * - Ember is reserved for running / live signals only: the fire is lit while the agent works
 */
@Immutable
internal data class DebugThemeColors(
    val bg: Color,
    val panel: Color,
    val elevated: Color,
    val border: Color,
    val borderSubtle: Color,
    val text: Color,
    val muted: Color,
    val tertiary: Color,
    val accent: Color,
    val accentSoft: Color,
    val ink: Color,
    val inkSoft: Color,
    val activity: Color,
    val activitySoft: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    val userBubble: Color,
    val codeBg: Color,
    val isDark: Boolean,
)

internal val LightDebugColors = DebugThemeColors(
    bg = Color(0xFFF3EFE7),
    panel = Color(0xFFFBF8F3),
    elevated = Color(0xFFFFFFFF),
    border = Color(0xFFE4DDD0),
    borderSubtle = Color(0xFFEDE7DC),
    text = Color(0xFF1E231F),
    muted = Color(0xFF6A665D),
    tertiary = Color(0xFF9B958A),
    accent = Color(0xFF2F6B4F),
    accentSoft = Color(0xFFE2EDE5),
    ink = Color(0xFF1E231F),
    inkSoft = Color(0xFF2D332F),
    activity = Color(0xFFC9612A),
    activitySoft = Color(0xFFF9E9DC),
    danger = Color(0xFFC8372D),
    success = Color(0xFF2E9C63),
    warning = Color(0xFFB7791F),
    userBubble = Color(0xFFECE6DA),
    codeBg = Color(0xFFF1ECE3),
    isDark = false,
)

internal val DarkDebugColors = DebugThemeColors(
    bg = Color(0xFF0E1210),
    panel = Color(0xFF141917),
    elevated = Color(0xFF1B221E),
    border = Color(0xFF29322D),
    borderSubtle = Color(0xFF1F2723),
    text = Color(0xFFE8E5DD),
    muted = Color(0xFFA29D92),
    tertiary = Color(0xFF6E6A62),
    accent = Color(0xFF8CC7A5),
    accentSoft = Color(0xFF1D2E25),
    ink = Color(0xFFE8E5DD),
    inkSoft = Color(0xFF2A322D),
    activity = Color(0xFFF2925A),
    activitySoft = Color(0xFF33221A),
    danger = Color(0xFFF07A6E),
    success = Color(0xFF5CCB8E),
    warning = Color(0xFFE6B655),
    userBubble = Color(0xFF1E2622),
    codeBg = Color(0xFF111613),
    isDark = true,
)

internal val LocalDebugColors = staticCompositionLocalOf { LightDebugColors }

/** Theme-aware colors inside composables. */
internal val DebugColors: DebugThemeColors
    @Composable
    @ReadOnlyComposable
    get() = LocalDebugColors.current

/** Shared type scale; prefer these over ad-hoc fontSize literals in new UI. */
internal object DebugType {
    val hero: TextStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val panelTitle: TextStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val body: TextStyle = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
    )
    val meta: TextStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
    val bodySmall: TextStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)

    /** Uppercase transform is applied by the call site. */
    val label: TextStyle = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
    )
    val code: TextStyle = TextStyle(
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = 18.sp,
    )
}

internal object DebugDimens {
    val spaceXxs: Dp = 2.dp
    val spaceXs: Dp = 4.dp
    val spaceXsMd: Dp = 6.dp
    val spaceSm: Dp = 8.dp
    val spaceMd: Dp = 12.dp
    val spaceLg: Dp = 16.dp
    val spaceXl: Dp = 24.dp
    val radiusSm: Dp = 6.dp
    val radiusMd: Dp = 10.dp
    val radiusLg: Dp = 14.dp
    val radiusFull: Dp = 999.dp
    val panelPadding: Dp = 12.dp
    val toggleHeight: Dp = 44.dp
    /** Wide enough for reliable finger/adb center taps on large tablet displays. */
    val toggleMinWidth: Dp = 88.dp
}

internal typealias DebugProviderFetchAction =
    (String, DebugProvider, DebugProviderEndpoint, String, String, String, (Result<List<String>>) -> Unit) -> Unit

@Composable
internal fun DebugConsoleTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkDebugColors else LightDebugColors
    CompositionLocalProvider(LocalDebugColors provides colors) {
        MaterialTheme(
            colorScheme = if (colors.isDark) {
                darkColorScheme(
                    primary = colors.accent,
                    secondary = colors.activity,
                    surface = colors.panel,
                    background = colors.bg,
                    onPrimary = colors.bg,
                    onSurface = colors.text,
                    onBackground = colors.text,
                    error = colors.danger,
                    outline = colors.border,
                )
            } else {
                lightColorScheme(
                    primary = colors.accent,
                    secondary = colors.activity,
                    surface = colors.panel,
                    background = colors.bg,
                    onPrimary = Color.White,
                    onSurface = colors.text,
                    onBackground = colors.text,
                    error = colors.danger,
                    outline = colors.border,
                )
            },
            typography = DebugTypography,
            content = content,
        )
    }
}

/** MaterialTheme default text styles mapped onto the shared type scale. */
private val DebugTypography = Typography(
    titleLarge = DebugType.hero,
    titleMedium = DebugType.panelTitle,
    bodyLarge = DebugType.body,
    bodyMedium = DebugType.meta,
    bodySmall = DebugType.label,
    labelMedium = DebugType.meta,
    labelSmall = DebugType.label,
)

/** Outlined text field skin on Debug tokens; shared by composer and settings forms. */
@Composable
internal fun debugOutlinedTextFieldColors(): TextFieldColors {
    val colors = DebugColors
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.accent.copy(alpha = FOCUSED_FIELD_BORDER_ALPHA),
        unfocusedBorderColor = colors.borderSubtle,
        focusedContainerColor = colors.panel.copy(alpha = FIELD_CONTAINER_ALPHA),
        unfocusedContainerColor = colors.panel.copy(alpha = FIELD_CONTAINER_ALPHA),
        cursorColor = colors.accent,
        focusedTextColor = colors.text,
        unfocusedTextColor = colors.text,
        focusedPlaceholderColor = colors.muted,
        unfocusedPlaceholderColor = colors.muted,
    )
}

/** Switch skin on Debug tokens: accent track when checked, quiet border/muted when off. */
@Composable
internal fun debugSwitchColors(): SwitchColors {
    val colors = DebugColors
    return SwitchDefaults.colors(
        checkedThumbColor = colors.bg,
        checkedTrackColor = colors.accent,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = colors.muted,
        uncheckedTrackColor = colors.elevated,
        uncheckedBorderColor = colors.border,
    )
}

/** AlertDialog skin on Debug tokens: elevated container, token-driven title/text colors. */
@Composable
internal fun DebugAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    val colors = DebugColors
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        title = title,
        text = text,
        containerColor = colors.elevated,
        titleContentColor = colors.text,
        textContentColor = colors.muted,
    )
}

private const val FIELD_CONTAINER_ALPHA = 0.42f
private const val FOCUSED_FIELD_BORDER_ALPHA = 0.65f

internal data class DebugConsoleActions(
    val submit: (String) -> Unit,
    val steer: (String) -> Unit,
    val approveConfirmation: (String) -> Unit,
    val denyConfirmation: (String) -> Unit,
    val selectConfirmationMode: (DebugConfirmationMode) -> Unit,
    val stop: () -> Unit,
    val newSession: () -> Unit,
    val selectSession: (String) -> Unit,
    val deleteSession: (String) -> Unit,
    /** Wipe sessions, workspace memory, and local runtime evidence (not provider config). */
    val clearRuntimeData: () -> Unit,
    /** 设置 → 记忆's 清除记忆和对话记录: [clearRuntimeData] and the user memories, then whether all of it went. */
    val clearMemoriesAndRuntimeData: (done: (Boolean) -> Unit) -> Unit = { done -> done(false) },
    val selectProvider: (DebugProvider) -> Unit,
    val selectProfile: (String) -> Unit,
    val setManualProfileEnabled: (Boolean) -> Unit,
    val selectModel: (String) -> Unit,
    val selectVariant: (String) -> Unit,
    val toggleStream: () -> Unit,
    val selectBackend: (Boolean) -> Unit,
    val reloadConfig: () -> Unit,
    val saveConfig: (DebugProvider, String, String, String, DebugProviderEndpoint) -> Unit,
    val saveNewProfile: (String, DebugProvider, DebugProviderEndpoint, String, String, String) -> String,
    val deleteProfile: (String) -> Unit,
    val fetchModels: DebugProviderFetchAction,
    val saveProviderModels: (DebugProviderEditState, List<String>) -> Unit,
    val toggleItem: (DebugConversationItem) -> Unit,
    val setMcpProviderEnabled: (String, Boolean) -> Unit,
    /** Adds or edits a configured MCP server (设置 → 连接 → MCP); returns a validation error or null. */
    val saveMcpServer: (DebugMcpServerDraft) -> String? = { null },
    val deleteMcpServer: (String) -> Unit = {},
    val setA2aProfileEnabled: (String, Boolean) -> Unit,
    /** Persists a user A2A profile (label, moduleId, endpoint, allowLoopback); returns a validation error or null. */
    val saveA2aProfile: (DebugA2aProfileDraft) -> String?,
    val deleteA2aProfile: (String) -> Unit,
    val pickSafWorkspace: () -> Unit,
    val clearSafWorkspace: () -> Unit,
    val openOverlay: () -> Unit,
    /** Opens a fullscreen WebView preview for an in-memory HTML page (title + source). */
    val openHtmlViewer: (String, String) -> Unit,
    /** Grouped so the read-only Trajectory view does not keep widening this constructor. */
    val trajectory: TrajectoryActions = TrajectoryActions(),
    val answerQuestion: (DebugUserQuestion, String) -> Unit = { _, _ -> },
)

internal data class TrajectoryActions(
    val refresh: () -> Unit = {},
    val loadOlderRuns: () -> Unit = {},
    val loadRun: (String) -> Unit = {},
)
