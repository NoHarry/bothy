package cc.noharry.bothy.app.overlay
import android.graphics.Color

/**
 * Visual tokens for the floating overlay HUD (classic View system, not Compose).
 *
 * Same source as the app dark theme ([DarkConsoleColors] / [ConsoleType]): deep forest
 * stage surfaces, sage brand accent, and ember reserved for live/running
 * signals. Corner radii stay larger than app panels because the overlay floats over
 * other apps and keeps the softer stage chrome.
 */
internal object OverlayTheme {
    // Stage surfaces — deep forest family (bg / panel / elevated / codeBg).
    val stageBg: Int = Color.rgb(0x0E, 0x12, 0x10)
    val cardBg: Int = Color.rgb(0x14, 0x19, 0x17)
    val toolBg: Int = Color.rgb(0x1B, 0x22, 0x1E)
    val codeBg: Int = Color.rgb(0x11, 0x16, 0x13)

    /** Empty progress track — one step above card surfaces. */
    val track: Int = Color.rgb(0x1F, 0x27, 0x23)

    // Borders / hairlines — #29322D family (outer stage chrome / inner cards).
    val stageBorder: Int = Color.rgb(0x29, 0x32, 0x2D)
    val cardBorder: Int = Color.rgb(0x1F, 0x27, 0x23)

    // Text levels — text / muted / tertiary.
    val text: Int = Color.rgb(0xE8, 0xE5, 0xDD)
    val mutedSoft: Int = Color.rgb(0xA2, 0x9D, 0x92)
    val muted: Int = Color.rgb(0x6E, 0x6A, 0x62)
    val codeText: Int = text

    // Accents — sage brand; ember reserved for live/running signals only.
    val accent: Int = Color.rgb(0x8C, 0xC7, 0xA5)
    val live: Int = Color.rgb(0xF2, 0x92, 0x5A)
    val success: Int = Color.rgb(0x5C, 0xCB, 0x8E)
    val error: Int = Color.rgb(0xF0, 0x7A, 0x6E)
    val warning: Int = Color.rgb(0xE6, 0xB6, 0x55)

    // Soft state washes for plan rows, tool rows, and phase pills.
    // Live takes the app's activitySoft ember wash; success keeps a green one.
    val liveSoft: Int = Color.rgb(0x33, 0x22, 0x1A)
    val successSoft: Int = Color.rgb(0x15, 0x2A, 0x1F)
    val errorSoft: Int = Color.rgb(0x2E, 0x18, 0x16)

    /** Alpha for active/running state borders drawn over the soft washes. */
    const val stateBorderAlpha: Float = 0.55f

    // Type scale — same steps as ConsoleType (hero/body/meta/label/code).
    const val textSizeHeadline: Float = 20f
    const val textSizePlan: Float = 14f
    const val textSizeResult: Float = 14f
    const val textSizePhase: Float = 12f
    const val textSizeTimer: Float = 13f
    const val textSizeBody: Float = 14f
    const val textSizeMeta: Float = 12f
    const val textSizeSection: Float = 11f
    const val textSizeCode: Float = 13f

    // Corner radii — overlay keeps larger rounds than the app panels.
    const val radiusSm: Int = 10
    const val radiusMd: Int = 14
    const val radiusLg: Int = 18
    const val radiusXl: Int = 22
    const val radiusPill: Int = 999
}
