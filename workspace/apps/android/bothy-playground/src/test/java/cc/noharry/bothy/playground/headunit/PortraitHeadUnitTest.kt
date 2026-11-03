package cc.noharry.bothy.playground.headunit

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import cc.noharry.bothy.playground.PlaygroundStore
import cc.noharry.bothy.playground.ServerState
import cc.noharry.bothy.playground.cabin.Seat
import cc.noharry.bothy.playground.home.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The portrait head unit actually calls the cabin actions, not only the pure hit tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w411dp-h2000dp-port")
class PortraitHeadUnitTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aTapAndALongPressOnThePortraitCabinReachTheStore() {
        val store = PlaygroundStore(clock = { 0L })
        compose.setContent {
            PortraitHeadUnit(
                world = World(),
                calls = emptyList(),
                server = ServerState(),
                flash = Flash(emptySet(), mutableFloatStateOf(0f)),
                flashLevel = 0f,
                store = store,
                onHome = {},
                initialTab = PortraitTab.CABIN,
            )
        }
        compose.onNodeWithTag("portrait-cabin").performTouchInput {
            val seat = CabinSpots.seats.getValue(Seat.DRIVER)
            click(androidx.compose.ui.geometry.Offset(seat.x * width, seat.y * height))
        }
        assertTrue(store.calls.value.joinToString { it.summary }, Seat.DRIVER !in store.read().cabin.occupants)

        compose.onNodeWithTag("portrait-cabin").performTouchInput {
            val seat = CabinSpots.seats.getValue(Seat.PASSENGER)
            longClick(androidx.compose.ui.geometry.Offset(seat.x * width, seat.y * height))
        }
        assertEquals(Seat.PASSENGER, store.read().cabin.speaker)
    }

    @Test
    fun theMenuHoldsTheHomeAndTheSettings() {
        compose.setContent {
            PortraitHeadUnit(
                world = World(),
                calls = emptyList(),
                server = ServerState(),
                flash = Flash(emptySet(), mutableFloatStateOf(0f)),
                flashLevel = 0f,
                store = PlaygroundStore(clock = { 0L }),
                onHome = {},
            )
        }
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        compose.onNodeWithContentDescription("菜单").assert(button).performClick()
        compose.onNodeWithText("虚拟小家").assertExists()
        compose.onNodeWithText("设置与控制").assertExists()
    }
}
