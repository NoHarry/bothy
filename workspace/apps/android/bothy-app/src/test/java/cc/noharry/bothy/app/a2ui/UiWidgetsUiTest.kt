package cc.noharry.bothy.app.a2ui
import android.app.Application
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiClientToServerMessage
import cc.noharry.agent.appserver.protocol.view.BoundCall
import cc.noharry.agent.appserver.protocol.view.CardItem
import cc.noharry.agent.appserver.protocol.view.CardItemKind
import cc.noharry.agent.appserver.protocol.view.CardLayout
import cc.noharry.agent.appserver.protocol.view.CardProgress
import cc.noharry.agent.appserver.protocol.view.ChoiceOffer
import cc.noharry.agent.appserver.protocol.view.ChoiceOption
import cc.noharry.agent.appserver.protocol.view.DisplayChoice
import cc.noharry.agent.appserver.protocol.view.SliderOffer
import cc.noharry.agent.appserver.protocol.view.SwitchOffer
import cc.noharry.agent.appserver.protocol.view.WidgetCard
import cc.noharry.bothy.app.image.isPublic
import cc.noharry.bothy.app.image.limitedTo
import cc.noharry.bothy.app.overlay.iconFrom
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The front end's widgets: what the A2UI engine draws from offers and cards (UiWidgetsTest has the offer tools). A plain
 * Application: the sample one starts servers and background work that would run while the looper is idled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UiWidgetsUiTest {
    @Test
    fun onlyPublicAddressesCountAsTheWebForTheScreensPictures() {
        listOf("10.0.0.1", "172.16.3.4", "192.168.1.1", "127.0.0.1", "169.254.1.1", "100.64.1.1", "0.0.0.0", "::1", "fd00::1", "fe80::1")
            .forEach { assertFalse(it, java.net.InetAddress.getByName(it).isPublic()) }
        listOf("8.8.8.8", "101.6.6.6", "2001:4860:4860::8888").forEach { assertTrue(it, java.net.InetAddress.getByName(it).isPublic()) }
    }

    @Test
    fun aPictureWithoutADeclaredLengthStillStopsAtItsCap() {
        val image = "image/jpeg".toMediaType()
        assertEquals(10, ByteArray(10).toResponseBody(image).limitedTo(16).bytes().size)
        assertThrows(java.io.IOException::class.java) { ByteArray(40).toResponseBody(image).limitedTo(16).bytes() }
    }

    @Test
    fun theEngineAcceptsTheSliderCardAndIconRows() {
        val slider = SliderOffer("亮度", BoundCall("screen", "brightness_set", "mcp__screen__brightness_set", "{}"), "targetPercent", 0.0, 100.0, 5.0, 40.0, "%", true)
        val card = WidgetCard("花都区", "sunny", "31°", "晴", listOf("湿度" to "60%"), listOf(CardItem("rain", "明天", "小雨")))
        val choices = a2uiChoiceMessages("icons", null, listOf(A2uiChoice("充电站", true, "charging"), A2uiChoice("设置", false, "settings")))

        assertEquals(listOf("slider"), accepted(a2uiPanelMessages("slider", listOf(slider, slider.copy(label = "音量")))))
        assertEquals(listOf("card"), accepted(a2uiCardMessages("card", card)))
        assertEquals(listOf("icons"), accepted(choices))
    }

    @Test
    fun theConfirmEventCarriesEachControlsValueInItsType() {
        val call = BoundCall("screen", "x", "mcp__screen__x", "{}")
        val controls = listOf(
            SliderOffer("亮度", call, "targetPercent", 0.0, 100.0, 1.0, 40.0, "%", true),
            SwitchOffer("加热", call, call, false),
            ChoiceOffer("模式", listOf(ChoiceOption("自动", call), ChoiceOption("制冷", call)), listOf(0), multiple = false),
        )
        val event = A2uiClientEventMessage(DEBUG_A2UI_SET_EVENT, "panel", "confirm", 0L, mapOf("v0" to 55.0, "v1" to true, "v2" to listOf("1")))

        assertEquals(listOf(55.0, true, listOf(1)), event.controlValues(controls))
        assertNull(A2uiClientEventMessage(DEBUG_A2UI_SET_EVENT, "panel", "confirm", 0L, mapOf("v0" to 55.0)).controlValues(controls))
    }

    @Test
    fun theEngineAcceptsThePanelAndEveryCardLayout() {
        val call = BoundCall("screen", "x", "mcp__screen__x", "{}")
        val panel = listOf(
            SliderOffer("温度", call, "t", 16.0, 32.0, 0.5, 24.0, "℃", false),
            SwitchOffer("座椅加热", call, call, true),
            ChoiceOffer("模式", listOf(ChoiceOption("自动", call), ChoiceOption("制冷", call)), listOf(0), multiple = false),
            ChoiceOffer("座位", listOf(ChoiceOption("主驾", call), ChoiceOption("后排左侧座椅", call)), emptyList(), multiple = true),
        )
        val items = listOf(
            CardItem("sunny", "明天", "晴", "30/22°", fields = listOf("距离" to "1 km")),
            CardItem("rain", "后天", "小雨", "27/21°", image = "generated-images/delegation-abc/1.jpg", fields = listOf("价格" to "1.2 元")),
        )
        val base = WidgetCard("天气", "sunny", "31°", "晴", listOf("湿度" to "60%"), items, progress = listOf(CardProgress("电量", 78.0, 100.0, null)))
        val trip = base.copy(
            items = listOf(
                CardItem(null, "西湖", "断桥到苏堤", "09:00", image = "https://img.example.com/xihu.jpg", group = "第1天"),
                CardItem(null, "灵隐寺", null, "14:00", image = "https://img.example.com/lingyin.jpg", group = "第1天"),
                CardItem("music", "宋城", "千古情", "19:00", group = "第2天"),
            ),
        )
        assertEquals(listOf("card"), accepted(a2uiCardMessages("card", trip)))

        assertEquals(listOf("panel"), accepted(a2uiPanelMessages("panel", panel)))
        CardLayout.entries.forEach { layout ->
            assertEquals(layout.wire, listOf("card"), accepted(a2uiCardMessages("card", base.copy(layout = layout))))
            assertEquals(layout.wire, listOf("card"), accepted(a2uiCardMessages("card", base.copy(layout = layout)), carLook = false))
        }
    }

    @Test
    fun theEngineValidatesOurComponentsAgainstTheCatalog() {
        val bad = listOf(
            a2uiCreateSurface("bad"),
            a2uiMessage("updateComponents") {
                put("surfaceId", "bad")
                put("components", buildJsonArray {
                    add(a2uiComponent("root", "Column") { putJsonArray("children") { add("p"); add("g") } })
                    add(a2uiComponent("p", PROGRESS_COMPONENT) { put("label", "电量") })
                    add(a2uiComponent("g", "Nope") { put("label", "x") })
                })
            },
        )
        // Without this, an "accepted" surface above would prove nothing about its components.
        val errors = render(bad).second.map { it.message }
        assertTrue(errors.toString(), errors.any { it.contains("'value'") })
        assertTrue(errors.toString(), errors.any { it.contains("'Nope'") })
    }

    @Test
    fun aChoiceShowsTheIconOfferedForItsLabel() {
        val icons = mapOf("充电站" to "charging", "停车场" to "parking")

        assertEquals("charging", DisplayChoice(1, "充电站（推荐，最近）", true).iconFrom(icons))
    }

    @Test
    fun aTripCardShowsTicketsADayPerColumnAndHotelPictures() {
        // What show_card's trip arguments become (UiWidgetsTest checks the parsing).
        val d1 = "D1 10/23 周五"
        val items = listOf(
            CardItem(null, "去程 · 3U6992 川航", "浦东T2 → 天府T2", "11:55 → 14:50", kind = CardItemKind.TRANSPORT),
            CardItem("rain", "小雨", "降雨 60%", "16–22°", group = d1, kind = CardItemKind.WEATHER),
            CardItem(null, "熊猫基地", null, "16:00–18:00", image = "https://img.example.com/panda.jpg", group = d1),
            CardItem(null, "宽窄巷子", null, "晚上", group = d1),
            CardItem(null, "武侯祠", null, "上午", group = "D2 10/24 周六"),
            CardItem(null, "回程 · 9C6102 春秋", null, "08:30 → 11:05", kind = CardItemKind.TRANSPORT),
            CardItem(null, "亚朵S酒店", null, "约 800 元/晚", image = "https://img.example.com/hotel.jpg", kind = CardItemKind.STAY),
        )
        val card = WidgetCard("上海⇄成都 · 3 日行程", null, "3 天", null, emptyList(), items, layout = CardLayout.TRIP)
        val messages = a2uiCardMessages("card", card)

        assertEquals(listOf("card"), accepted(messages))
        val components = messages.joinToString()
        // A ticket per direction, then a column per day (its name, its first photo, a Stop per place), then the hotel.
        assertTrue(components.contains("trip_ticket_1") && !components.contains("trip_ticket_2") && components.contains("\"leg\":\"回程\""))
        assertTrue(components.contains("\"mode\":\"flight\""))
        assertTrue(components.contains("trip_day_1") && !components.contains("trip_day_2") && components.contains("D2 10/24 周六"))
        assertTrue(components.contains("trip_day_0_photo") && components.contains("trip_day_0_weather") && components.contains("小雨 · 16–22° · 降雨 60%"))
        assertTrue(components.contains("\"component\":\"Stay\"") && components.contains("\"image\":\"https://img.example.com/hotel.jpg\""))
        assertEquals(3, Regex("\"component\":\"Stop\"").findAll(components).count())
    }

    @Test
    fun theHostsOwnWordsComeFromTheScreenAndATicketsOtherFlightsStayItsOwn() {
        val items = listOf(
            CardItem(null, "去程 · 3U6992 川航", "浦东T2 → 天府T2 · 约 800 元", "11:55 → 14:50", kind = CardItemKind.TRANSPORT),
            CardItem(null, "去程 · CA4512 国航", null, "13:00 → 16:00", kind = CardItemKind.TRANSPORT),
            CardItem(null, "熊猫基地", null, "16:00–18:00"),
            CardItem(null, "亚朵S酒店", null, "约 800 元/晚", kind = CardItemKind.STAY),
        )
        val trip = WidgetCard("上海⇄成都", null, null, null, emptyList(), items, layout = CardLayout.TRIP)
        val labels = A2uiLabels(confirm = "OK", tripDay = "Trip", stays = "Where to stay")
        val messages = a2uiCardMessages("card", trip, labels)

        assertEquals(listOf("card"), accepted(messages))
        val components = messages.joinToString()
        // The other flight is its own property; the ticket words it in the screen's language.
        assertTrue(components, components.contains("\"note\":\"约 800 元\""))
        assertTrue(components, components.contains("\"alternatives\":\"CA4512 国航 13:00 → 16:00\""))
        assertTrue(components, components.contains("\"text\":\"Trip\""))
        assertTrue(components, components.contains("\"text\":\"Where to stay\""))
        val call = BoundCall("screen", "x", "mcp__screen__x", "{}")
        val panel = a2uiPanelMessages("panel", listOf(SwitchOffer("座椅加热", call, call, true)), labels)
        assertTrue(panel.joinToString().contains("\"text\":\"OK\""))
    }

    @Test
    fun aTripListsItsDaysAsSectionsWithPhotos() {
        // What show_card makes of a two-day trip (UiWidgetsTest checks it).
        val items = listOf(
            CardItem(null, "西湖", null, "09:00", image = "https://img.example.com/xihu.jpg", group = "第1天"),
            CardItem(null, "灵隐寺", null, "14:00", group = "第1天"),
            CardItem(null, "西溪湿地", null, "09:30", group = "第2天"),
        )
        val card = WidgetCard("杭州两日游", null, null, null, emptyList(), items, image = "https://img.example.com/hero.jpg")
        val components = Json.parseToJsonElement(a2uiCardMessages("trip", card).last()).jsonObject["updateComponents"]!!
            .jsonObject["components"]!!.jsonArray.map { it.jsonObject }
        val rows = components.single { it["id"]!!.jsonPrimitive.content == "items" }["children"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("group_0", "item_0", "item_1", "group_2", "item_2"), rows)
        // With stops to list, the cover is a banner inside the card, not the big frame beside the reply.
        assertEquals("banner", components.single { it["id"]!!.jsonPrimitive.content == "root" }["children"]!!.jsonArray.first().jsonPrimitive.content)
        assertEquals("https://img.example.com/xihu.jpg", components.single { it["id"]!!.jsonPrimitive.content == "item_0_picture" }["url"]!!.jsonPrimitive.content)
    }

    /** The surfaces the engine created; it must report no error for any message. */
    private fun accepted(messages: List<String>, carLook: Boolean = true): List<String> {
        val (surfaces, errors) = render(messages, carLook)
        assertTrue("errors=$errors", errors.isEmpty())
        return surfaces
    }

    private fun render(messages: List<String>, carLook: Boolean = true): Pair<List<String>, List<A2uiClientErrorMessage>> {
        val processor = a2uiProcessor(carLook)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val outbound = CopyOnWriteArrayList<A2uiClientToServerMessage>()
        scope.launch { processor.collectMessages() }
        scope.launch { processor.outboundEvents.collect { outbound += it } }
        processor.processJson(messages)
        // The engine works on Dispatchers.Default; under a full validate run it can be slow to start.
        val deadline = System.currentTimeMillis() + ENGINE_WAIT_MS
        while (processor.activeSurfaces.value.isEmpty() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(ENGINE_POLL_MS)
        }
        // Let the component and data messages after createSurface apply too.
        repeat(SETTLE_POLLS) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(ENGINE_POLL_MS)
        }
        // Read before cancelling: collectMessages clears every surface when it ends, and the cancel runs on another
        // thread, so a read after it found none whenever the cancel won (it did under the full validate run).
        val surfaces = processor.activeSurfaces.value.map { it.id }
        scope.cancel()
        return surfaces to outbound.filterIsInstance<A2uiClientErrorMessage>()
    }
}

private const val ENGINE_WAIT_MS = 10_000L
private const val ENGINE_POLL_MS = 20L
private const val SETTLE_POLLS = 10
