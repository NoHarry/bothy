package cc.noharry.bothy.app.console

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontEndTest {
    @Test
    fun autoFollowsTheScreenAndAChoiceOverridesIt() {
        assertTrue(FrontEnd.resolve(FrontEnd.Choice.AUTO, phoneScreen = true))
        assertFalse(FrontEnd.resolve(FrontEnd.Choice.AUTO, phoneScreen = false))
        assertTrue(FrontEnd.resolve(FrontEnd.Choice.PHONE, phoneScreen = false))
        assertFalse(FrontEnd.resolve(FrontEnd.Choice.CONSOLE, phoneScreen = true))
    }

    @Test
    fun aTabletHeldUprightGetsThePhoneScreensUnlessTheTabletIsChosen() {
        assertTrue(FrontEnd.resolve(FrontEnd.Choice.AUTO, phoneScreen = false, upright = true))
        assertFalse(FrontEnd.resolve(FrontEnd.Choice.AUTO, phoneScreen = false, upright = false))
        assertFalse(FrontEnd.resolve(FrontEnd.Choice.CONSOLE, phoneScreen = false, upright = true))
    }
}
