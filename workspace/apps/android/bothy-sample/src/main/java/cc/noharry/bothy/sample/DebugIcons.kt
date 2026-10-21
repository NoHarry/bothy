package cc.noharry.bothy.sample

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Navigation icon set for the debug console chrome.
 * material-icons-core covers Settings/List; the chat bubble and dashboard
 * glyphs below are hand-built from the material path data (24dp viewport).
 */

/** Filled chat bubble (material ChatBubble); core set has no chat icon. */
internal val DebugIconConversation: ImageVector by lazy {
    ImageVector.Builder(
        name = "DebugIconConversation",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(20f, 2f)
        horizontalLineTo(4f)
        curveTo(2.9f, 2f, 2f, 2.9f, 2f, 4f)
        verticalLineToRelative(18f)
        lineToRelative(4f, -4f)
        horizontalLineToRelative(14f)
        curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
        verticalLineTo(4f)
        curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
        close()
    }.build()
}

/** Filled dashboard grid (material Dashboard); core set has no dashboard icon. */
internal val DebugIconBoard: ImageVector by lazy {
    ImageVector.Builder(
        name = "DebugIconBoard",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 13f)
        horizontalLineToRelative(8f)
        verticalLineTo(3f)
        horizontalLineTo(3f)
        verticalLineToRelative(10f)
        close()
        moveTo(3f, 21f)
        horizontalLineToRelative(8f)
        verticalLineToRelative(-6f)
        horizontalLineTo(3f)
        verticalLineToRelative(6f)
        close()
        moveTo(13f, 21f)
        horizontalLineToRelative(8f)
        verticalLineTo(11f)
        horizontalLineToRelative(-8f)
        verticalLineToRelative(10f)
        close()
        moveTo(13f, 3f)
        verticalLineToRelative(6f)
        horizontalLineToRelative(8f)
        verticalLineTo(3f)
        horizontalLineToRelative(-8f)
        close()
    }.build()
}

/** Icon per navigation destination; labels stay on [DebugNavigationDestination.label]. */
internal fun DebugNavigationDestination.icon(): ImageVector = when (this) {
    DebugNavigationDestination.CONVERSATION -> DebugIconConversation
    DebugNavigationDestination.SESSIONS -> Icons.AutoMirrored.Filled.List
    DebugNavigationDestination.BOARD -> DebugIconBoard
    DebugNavigationDestination.SETTINGS -> Icons.Filled.Settings
}

/** Fullscreen expand glyph (material Fullscreen); core set has no fullscreen icon. */
internal val DebugIconFullscreen: ImageVector by lazy {
    ImageVector.Builder(
        name = "DebugIconFullscreen",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(7f, 14f)
        horizontalLineTo(5f)
        verticalLineToRelative(5f)
        horizontalLineToRelative(5f)
        verticalLineToRelative(-2f)
        horizontalLineTo(7f)
        verticalLineToRelative(-3f)
        close()
        moveTo(5f, 10f)
        horizontalLineToRelative(2f)
        verticalLineTo(7f)
        horizontalLineToRelative(3f)
        verticalLineTo(5f)
        horizontalLineTo(5f)
        verticalLineToRelative(5f)
        close()
        moveTo(17f, 17f)
        horizontalLineToRelative(-3f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(5f)
        verticalLineToRelative(-5f)
        horizontalLineToRelative(-2f)
        verticalLineToRelative(3f)
        close()
        moveTo(14f, 5f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(3f)
        verticalLineToRelative(3f)
        horizontalLineToRelative(2f)
        verticalLineTo(5f)
        horizontalLineToRelative(-5f)
        close()
    }.build()
}

/** Filled folder glyph (material Folder); core set has no folder icon. */
internal val DebugIconFolder: ImageVector by lazy {
    ImageVector.Builder(
        name = "DebugIconFolder",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(10f, 4f)
        horizontalLineTo(4f)
        curveTo(2.9f, 4f, 2.01f, 4.9f, 2.01f, 6f)
        lineTo(2f, 18f)
        curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
        horizontalLineToRelative(16f)
        curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
        verticalLineTo(8f)
        curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
        horizontalLineToRelative(-8f)
        lineToRelative(-2f, -2f)
        close()
    }.build()
}

/** Filled document glyph (material InsertDriveFile); core set has no file icon. */
internal val DebugIconFile: ImageVector by lazy {
    ImageVector.Builder(
        name = "DebugIconFile",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(6f, 2f)
        curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
        lineTo(4f, 20f)
        curveToRelative(0f, 1.1f, 0.89f, 2f, 1.99f, 2f)
        horizontalLineTo(18f)
        curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
        verticalLineTo(8f)
        lineToRelative(-6f, -6f)
        horizontalLineTo(6f)
        close()
        moveTo(13f, 9f)
        verticalLineTo(3.5f)
        lineTo(18.5f, 9f)
        horizontalLineTo(13f)
        close()
    }.build()
}

/** Filled microphone (material Mic); core set has no mic icon. */
internal val DebugIconMic: ImageVector by lazy {
    ImageVector.Builder(
        name = "DebugIconMic",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 14f)
        curveToRelative(1.66f, 0f, 2.99f, -1.34f, 2.99f, -3f)
        lineTo(15f, 5f)
        curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f)
        reflectiveCurveTo(9f, 3.34f, 9f, 5f)
        verticalLineToRelative(6f)
        curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f)
        close()
        moveTo(17.3f, 11f)
        curveToRelative(0f, 3f, -2.54f, 5.1f, -5.3f, 5.1f)
        reflectiveCurveTo(6.7f, 14f, 6.7f, 11f)
        lineTo(5f, 11f)
        curveToRelative(0f, 3.41f, 2.72f, 6.23f, 6f, 6.72f)
        lineTo(11f, 21f)
        horizontalLineToRelative(2f)
        verticalLineToRelative(-3.28f)
        curveToRelative(3.28f, -0.48f, 6f, -3.3f, 6f, -6.72f)
        horizontalLineToRelative(-1.7f)
        close()
    }.build()
}
