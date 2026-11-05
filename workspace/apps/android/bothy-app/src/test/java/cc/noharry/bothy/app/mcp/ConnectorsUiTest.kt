package cc.noharry.bothy.app.mcp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh")
class ConnectorsUiTest {
    @Test
    fun thePairingLinkBecomesAQrCodeWithFinderPatterns() {
        val modules = qrModules("https://relay.example.com/connect?code=ABCD-EFGH")

        assertTrue(modules.size >= 21)
        assertTrue(modules.all { it.size == modules.size })
        val last = modules.size - 1
        for ((row, column) in listOf(0 to 0, 0 to last - 6, last - 6 to 0)) {
            assertTrue((0..6).all { modules[row][column + it] && modules[row + it][column] })
            assertFalse(modules[row + 1][column + 1])
            assertTrue(modules[row + 3][column + 3])
        }
    }
}
