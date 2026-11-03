package cc.noharry.bothy.app.console

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Bitmaps for the review: the tablet user bubble, and the window-sized layout breakpoint. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w1280dp-h800dp-land")
class ConsoleReviewShotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tabletBubbleAndWindowBreakpoint() {
        val full = consoleLayoutPolicy(851f, 393f)
        val afterKeyboard = consoleLayoutPolicy(851f, 170f)
        val split = consoleLayoutPolicy(600f, 800f)
        val splitMinusPadding = consoleLayoutPolicy(576f, 800f)
        assertFalse(full.useUltraCompactHeightChrome)
        assertTrue(afterKeyboard.useUltraCompactHeightChrome)
        assertEquals(WindowClass.MEDIUM, split.windowClass)
        assertEquals(WindowClass.COMPACT, splitMinusPadding.windowClass)
        compose.setContent {
            ConsoleTheme {
                Column(Modifier.background(ConsoleColors.bg)) {
                    Box(Modifier.fillMaxWidth().height(160.dp).padding(24.dp).testTag("bubble")) {
                        UserTurnBubble("好")
                    }
                    Column(Modifier.fillMaxWidth().background(Color.White).padding(24.dp).testTag("breakpoint")) {
                        Text("Window 851×393 dp, landscape phone. The policy reads the whole window.")
                        Text("ultra-compact chrome: ${full.useUltraCompactHeightChrome}, rail: ${full.useRailChrome}")
                        Text("If the keyboard shrank the measured height to 170 dp:")
                        Text("ultra-compact chrome: ${afterKeyboard.useUltraCompactHeightChrome}")
                        Text("The keyboard does not flip the layout. The inset sits inside the measured window.")
                        Text("A 600 dp window is ${split.windowClass}. 24 dp less would be ${splitMinusPadding.windowClass}.")
                    }
                }
            }
        }
        save(compose.onNodeWithTag("bubble").captureToImage().asAndroidBitmap(), "tablet-user-bubble.png")
        save(compose.onNodeWithTag("breakpoint").captureToImage().asAndroidBitmap(), "window-breakpoint.png")
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
