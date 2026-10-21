@file:OptIn(cc.noharry.bothy.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.sample

import android.app.Application
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiClientToServerMessage
import cc.noharry.bothy.kernel.api.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

// A plain Application: the sample one starts servers and background work that would run while the looper is idled.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DebugUiWidgetsTest {
    private val tools = DebugOfferToolProvider(DebugBindableTools(listOf(ScreenProvider()))) { NOW }

    @After
    fun clearStore() = DebugOfferStore.clear()

    @Test
    fun aSliderTakesItsRangeFromTheSchemaAndActsOnTheValueSaid() = runBlocking {
        val result = call(OFFER_SLIDER_TOOL, """{"label":"中控屏亮度","tool":"brightness_set","param":"targetPercent","arguments":{"screen":"center"},"value":40,"step":5,"unit":"%"}""")
        assertTrue(result.resultJson, result.success)
        val slider = DebugOfferStore.latest(SESSION)!!.sliders().single()
        assertEquals(0.0, slider.min, 0.0)
        assertEquals(100.0, slider.max, 0.0)
        assertEquals("中控屏亮度设为35%", slider.replyText(33.2))

        val previous = DebugPreviousExchange(SESSION, TURN, "亮度调一下", "可以拖动下面的滑块设置亮度。", "COMPLETED", "SUCCEEDED")
        val pick = debugOfferPick("设为35%", previous, DebugOfferStore.latest(SESSION), NOW)!!
        assertEquals("""{"screen":"center","targetPercent":35}""", pick.calls.single().argumentsJson)
        assertTrue(pick.input.contains("mcp__screen__brightness_set"))
        assertEquals(80.0, DebugOfferStore.latest(SESSION)!!.sliders().single().valueSaid("调到 80")!!, 0.0)
        assertNull(debugOfferPick("调到120", previous, DebugOfferStore.latest(SESSION), NOW))
    }

    @Test
    fun severalSlidersAreSetTogetherByTheConfirmOrByVoice() = runBlocking {
        call(OFFER_SLIDER_TOOL, """{"label":"媒体音量","tool":"volume_set","param":"volume","arguments":{"stream":"media"},"value":17,"unit":"档"}""")
        call(OFFER_SLIDER_TOOL, """{"label":"中控屏亮度","tool":"brightness_set","param":"targetPercent","arguments":{"screen":"center"},"unit":"%"}""")
        val offer = DebugOfferStore.latest(SESSION)!!
        assertEquals(listOf("媒体音量", "中控屏亮度"), offer.controls.map { it.label })
        val previous = DebugPreviousExchange(SESSION, TURN, "我想调下音量和亮度", "拖动下面的滑块，调好点确定。", "COMPLETED", "SUCCEEDED")

        val confirmed = debugPanelReply(offer.controls, listOf(22.0, 60.0))
        assertEquals("媒体音量设为22档，中控屏亮度设为60%", confirmed)
        val pick = debugOfferPick(confirmed, previous, offer, NOW)!!
        assertEquals(listOf("""{"stream":"media","volume":22}""", """{"screen":"center","targetPercent":60}"""), pick.calls.map { it.argumentsJson })

        val spoken = debugOfferPick("音量调到20和亮度调到80", previous, offer, NOW)!!
        assertEquals(listOf("volume_set", "brightness_set"), spoken.calls.map { it.toolName })
        assertEquals(1, debugOfferPick("亮度调到50", previous, offer, NOW)!!.calls.size)
        assertNull(debugOfferPick("调到50", previous, offer, NOW))
    }

    @Test
    fun aDegreeUnitStillBindsTheConfirmAndAMissingValueStartsMidway() = runBlocking {
        call(OFFER_SLIDER_TOOL, """{"label":"空调温度","tool":"brightness_set","param":"targetPercent","arguments":{"screen":"center"},"min":16,"max":32,"unit":"℃"}""")
        val offer = DebugOfferStore.latest(SESSION)!!
        assertEquals(24.0, offer.sliders().single().value, 0.0)
        val previous = DebugPreviousExchange(SESSION, TURN, "空调温度调一下", "拖动下面的滑块，调好点确定。", "COMPLETED", "SUCCEEDED")

        val confirmed = debugPanelReply(offer.controls, listOf(26.0))
        assertEquals("空调温度设为26℃", confirmed)
        assertEquals("""{"screen":"center","targetPercent":26}""", debugOfferPick(confirmed, previous, offer, NOW)!!.calls.single().argumentsJson)
        assertEquals(22.0, offer.sliders().single().valueSaid("调到22°C")!!, 0.0)
    }

    @Test
    fun aSliderMustVaryANumberInsideTheSchemasBounds() = runBlocking {
        assertEquals("WIDGET_INVALID", call(OFFER_SLIDER_TOOL, """{"label":"x","tool":"brightness_set","param":"screen"}""").code())
        assertEquals("WIDGET_INVALID", call(OFFER_SLIDER_TOOL, """{"label":"x","tool":"brightness_set","param":"targetPercent","max":120}""").code())
        assertEquals("WIDGET_INVALID", call(OFFER_SLIDER_TOOL, """{"label":"x","tool":"brightness_set","param":"targetPercent","arguments":{"screen":"rear"}}""").code())
        assertNull(DebugOfferStore.latest(SESSION))
    }

    @Test
    fun aCardNeedsContentAndKnownIcons() = runBlocking {
        assertEquals("WIDGET_INVALID", call(SHOW_CARD_TOOL, """{"title":"花都区"}""").code())
        assertEquals("WIDGET_INVALID", call(SHOW_CARD_TOOL, """{"title":"花都区","icon":"sun","value":"31°"}""").code())

        val ok = call(
            SHOW_CARD_TOOL,
            """{"title":"花都区 · 今天","icon":"sunny","value":"31°","subtitle":"晴 · 32/21°","fields":[{"label":"湿度","value":"60%"}],""" +
                """"items":[{"icon":"cloudy","title":"明天","detail":"多云 30/22°"}]}""",
        )
        assertTrue(ok.resultJson, ok.success)
        val card = DebugOfferStore.latest(SESSION)!!.cards.last()
        assertEquals("31°", card.value)
        assertEquals("cloudy", card.items.single().icon)
        assertTrue(call(SHOW_CARD_TOOL, """{"title":"欢乐海岸美食","icon":"food","items":[{"icon":"coffee","title":"咖啡"}]}""").success)
    }

    @Test
    fun aTurnsCardsShowTogetherAndTheSameTitleReplacesItsCard() = runBlocking {
        call(SHOW_CARD_TOOL, """{"title":"深圳 · 下周末活动","value":"8 场"}""")
        val second = call(SHOW_CARD_TOOL, """{"title":"广州 · 下周末活动","value":"9 场"}""")
        assertTrue(second.resultJson, second.resultJson.contains("共 2 张"))
        call(SHOW_CARD_TOOL, """{"title":"深圳 · 下周末活动","value":"7 场"}""")

        val cards = DebugOfferStore.latest(SESSION)!!.cards
        assertEquals(listOf("深圳 · 下周末活动" to "7 场", "广州 · 下周末活动" to "9 场"), cards.map { it.title to it.value })
        listOf("佛山", "东莞").forEach { call(SHOW_CARD_TOOL, """{"title":"$it","value":"1 场"}""") }
        assertEquals(listOf("广州 · 下周末活动", "佛山", "东莞"), DebugOfferStore.latest(SESSION)!!.cards.map { it.title })
    }

    @Test
    fun theClosingOfferNamesTheCloudAssistantOnlyWhileItIsConnected() = runBlocking {
        val card = """{"title":"潮州两日游","items":[{"title":"广济桥"}]}"""
        val connected = DebugOfferToolProvider(DebugBindableTools(listOf(ScreenProvider())), cloudAssistant = true) { NOW }
            .callTool(SHOW_CARD_TOOL, card, ToolCallContext(SESSION, TURN, TURN))
        val alone = call(SHOW_CARD_TOOL, card)

        assertTrue(connected.resultJson, connected.resultJson.contains("发给云端助手"))
        assertTrue(alone.resultJson.contains("提醒出发") && !alone.resultJson.contains("云端助手"))
    }

    @Test
    fun aCheckedListDrawnAgainReplacesItsCard() = runBlocking {
        call(SHOW_CARD_TOOL, """{"title":"周六10/24 深圳亲子演出","items":[{"title":"木偶剧《摇篮之歌》"},{"title":"儿童剧《白雪公主》"},{"title":"哈利波特魔法之旅音乐会"}]}""")
        call(SHOW_CARD_TOOL, """{"title":"周六 10/24 深圳亲子演出","subtitle":"已核对","items":[{"title":"木偶剧《摇篮之歌》（中国首秀）"}]}""")
        call(SHOW_CARD_TOOL, """{"title":"亲子演出 · 核对后","items":[{"title":"儿童剧《白雪公主》"},{"title":"木偶剧《摇篮之歌》"},{"title":"东方歌舞团专场"}]}""")

        assertEquals(listOf("亲子演出 · 核对后"), DebugOfferStore.latest(SESSION)!!.cards.map { it.title })
        call(SHOW_CARD_TOOL, """{"title":"广州","items":[{"title":"粤剧《帝女花》"},{"title":"儿童剧《白雪公主》"},{"title":"杂技"}]}""")
        assertEquals(2, DebugOfferStore.latest(SESSION)!!.cards.size)
        // Two cities' weather share 「周六」「周日」, which every such card has: still two cards.
        call(SHOW_CARD_TOOL, """{"title":"深圳天气","items":[{"title":"周六","value":"28°"},{"title":"周日","value":"27°"}]}""")
        call(SHOW_CARD_TOOL, """{"title":"广州天气","items":[{"title":"周六","value":"30°"},{"title":"周日","value":"29°"}]}""")
        assertTrue(DebugOfferStore.latest(SESSION)!!.cards.map { it.title }.containsAll(listOf("深圳天气", "广州天气")))
    }

    @Test
    fun aCardShowsSavedPicturesAndPublicHttpsPhotosOnly() = runBlocking {
        val saved = call(SHOW_CARD_TOOL, """{"title":"香香川菜","value":"人均 90","image":"generated-images/delegation-abc/1.jpg"}""")
        assertTrue(saved.resultJson, saved.success)
        assertEquals("generated-images/delegation-abc/1.jpg", DebugOfferStore.latest(SESSION)!!.cards.last().framedPicture)
        val web = call(SHOW_CARD_TOOL, """{"title":"西湖","value":"5A","image":"https://img.example.com/xihu.jpg?w=600"}""")
        assertTrue(web.resultJson, web.success)
        listOf(
            "http://img.example.com/a.jpg",
            "https://127.0.0.1/a.jpg",
            "https://192.168.1.5/a.jpg",
            "https://[::1]/a.jpg",
            "https://router.local/a.jpg",
            "https://localhost/a.jpg",
            "https://user:pw@img.example.com/a.jpg",
            "generated-images/../secret.jpg",
            "file:///sdcard/a.jpg",
        ).forEach { bad ->
            assertEquals(bad, "WIDGET_INVALID", call(SHOW_CARD_TOOL, """{"title":"x","value":"1","image":"$bad"}""").code())
        }
    }

    @Test
    fun onlyPublicAddressesCountAsTheWeb() {
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
    fun oneTurnGathersItsWidgetsAndTheNextTurnDropsThem() = runBlocking {
        call(SHOW_CARD_TOOL, """{"title":"花都区","value":"31°"}""")
        call(OFFER_OPTIONS_TOOL, """{"options":[{"label":"充电站","icon":"charging"},{"label":"停车场","icon":"parking"}]}""")
        val offer = DebugOfferStore.latest(SESSION)!!

        assertEquals(1, offer.cards.size)
        assertEquals(mapOf("充电站" to "charging", "停车场" to "parking"), offer.icons)
        assertEquals("charging", DebugDisplayChoice(1, "充电站（推荐，最近）", true).iconFrom(offer.icons))
        DebugOfferStore.endTurn(SESSION)
        assertNull(DebugOfferStore.latest(SESSION))
    }

    @Test
    fun theEngineAcceptsTheSliderCardAndIconRows() {
        val slider = DebugSliderOffer("亮度", DebugBoundCall("screen", "brightness_set", "mcp__screen__brightness_set", "{}"), "targetPercent", 0.0, 100.0, 5.0, 40.0, "%", true)
        val card = DebugCard("花都区", "sunny", "31°", "晴", listOf("湿度" to "60%"), listOf(DebugCardItem("rain", "明天", "小雨")))
        val choices = debugA2uiChoiceMessages("icons", null, listOf(DebugA2uiChoice("充电站", true, "charging"), DebugA2uiChoice("设置", false, "settings")))

        assertEquals(listOf("slider"), accepted(debugA2uiPanelMessages("slider", listOf(slider, slider.copy(label = "音量")))))
        assertEquals(listOf("card"), accepted(debugA2uiCardMessages("card", card)))
        assertEquals(listOf("icons"), accepted(choices))
    }

    @Test
    fun aSwitchBindsItsOnAndOffCallsAndTheConfirmSendsOnlyWhatChanged() = runBlocking {
        assertTrue(call(OFFER_SWITCH_TOOL, SEAT_SWITCH).success)
        assertTrue(call(OFFER_SWITCH_TOOL, """{"label":"方向盘加热","on":{"tool":"wheel_heat_switch","arguments":{"state":"on"}},""" +
            """"off":{"tool":"wheel_heat_switch","arguments":{"state":"off"}},"value":true}""").success)
        val offer = DebugOfferStore.latest(SESSION)!!
        val previous = DebugPreviousExchange(SESSION, TURN, "加热都看看", "拨好开关点确定。", "COMPLETED", "SUCCEEDED")

        val confirmed = debugPanelReply(offer.controls, listOf(true, true))
        assertEquals("主驾座椅加热打开", confirmed)
        assertEquals("""{"seat":"driver","level":2}""", debugOfferPick(confirmed, previous, offer, NOW)!!.calls.single().argumentsJson)
        assertEquals("""{"state":"off"}""", debugOfferPick("关掉方向盘加热", previous, offer, NOW)!!.calls.single().argumentsJson)
        assertEquals(listOf("seat_heat_set", "wheel_heat_switch"), debugOfferPick("座椅加热打开，方向盘加热关闭", previous, offer, NOW)!!.calls.map { it.toolName })
        // Two switches: a bare "打开" names neither.
        assertNull(debugOfferPick("打开", previous, offer, NOW))
        assertEquals("WIDGET_INVALID", call(OFFER_SWITCH_TOOL, SEAT_SWITCH.replace("\"level\":0", "\"level\":5")).code())
    }

    @Test
    fun aChoiceSetsOneValueOrRunsTheChosenActionsTogether() = runBlocking {
        assertTrue(call(OFFER_CHOICE_TOOL, AC_MODE).success)
        val mode = DebugOfferStore.latest(SESSION)!!.controls.single() as DebugChoiceOffer
        assertEquals(listOf(0), mode.value)
        val previous = DebugPreviousExchange(SESSION, TURN, "空调模式", "选好点确定。", "COMPLETED", "SUCCEEDED")
        val offer = DebugOfferStore.latest(SESSION)!!

        assertEquals("空调模式设为制冷", debugPanelReply(offer.controls, listOf(listOf(1))))
        assertEquals("""{"zone":"all","mode":"cool"}""", debugOfferPick("空调模式设为制冷", previous, offer, NOW)!!.calls.single().argumentsJson)
        assertEquals("""{"zone":"all","mode":"heat"}""", debugOfferPick("模式改成制热", previous, offer, NOW)!!.calls.single().argumentsJson)
        assertEquals("""{"zone":"all","mode":"cool"}""", debugOfferPick("制冷", previous, offer, NOW)!!.calls.single().argumentsJson)
        assertEquals("""{"zone":"all","mode":"cool"}""", debugOfferPick("制冷模式吧", previous, offer, NOW)!!.calls.single().argumentsJson)
        // Only a whole option counts: a new request that merely contains one is not a pick.
        assertNull(debugOfferPick("帮我找台自动售货机", previous, offer, NOW))
        assertEquals("WIDGET_INVALID", call(OFFER_CHOICE_TOOL, AC_MODE.replace("\"heat\"", "\"dry\"")).code())

        DebugOfferStore.endTurn(SESSION)
        assertTrue(call(OFFER_CHOICE_TOOL, SEATS).success)
        val seats = DebugOfferStore.latest(SESSION)!!
        val both = debugPanelReply(seats.controls, listOf(listOf(1, 0)))
        assertEquals("座椅加热选主驾、副驾", both)
        assertEquals(
            listOf("""{"seat":"driver","level":2}""", """{"seat":"passenger","level":2}"""),
            debugOfferPick(both, previous, seats, NOW)!!.calls.map { it.argumentsJson },
        )
        assertEquals(1, debugOfferPick("副驾", previous, seats, NOW)!!.calls.size)
        // Nothing chosen: the confirm says nothing, so nothing is sent.
        assertEquals("", debugPanelReply(seats.controls, listOf(emptyList<Int>())))
    }

    @Test
    fun aPanelMixesControlsAndABareConfirmSetsWhatIsShown() = runBlocking {
        call(OFFER_SLIDER_TOOL, """{"label":"空调温度","tool":"brightness_set","param":"targetPercent","arguments":{"screen":"center"},"min":16,"max":32,"value":24,"unit":"℃"}""")
        call(OFFER_CHOICE_TOOL, AC_MODE)
        call(OFFER_SWITCH_TOOL, SEAT_SWITCH)
        val offer = DebugOfferStore.latest(SESSION)!!
        val previous = DebugPreviousExchange(SESSION, TURN, "调一下空调", "调好点确定。", "COMPLETED", "SUCCEEDED")
        val unchanged = listOf(24.0, listOf(0), false)

        val bare = debugPanelReply(offer.controls, unchanged)
        assertEquals("空调温度设为24℃，空调模式设为自动，主驾座椅加热关闭", bare)
        val pick = debugOfferPick(bare, previous, offer, NOW)!!
        assertEquals(listOf("brightness_set", "ac_mode_set", "seat_heat_set"), pick.calls.map { it.toolName })
        assertTrue(pick.input, pick.input.contains("「空调模式」自动"))
        assertEquals("空调模式设为制热，主驾座椅加热打开", debugPanelReply(offer.controls, listOf(24.0, listOf(2), true)))
        assertEquals("空调温度设为26℃，空调模式设为制冷", debugPanelReply(offer.controls, listOf(26.0, listOf(1), false)))
        assertEquals(2, debugOfferPick("温度调到26，模式改成制冷", previous, offer, NOW)!!.calls.size)
    }

    @Test
    fun aPanelKeepsTheNewestFiveControlsAndThreeSliders() = runBlocking {
        listOf("音量", "亮度", "温度", "风量").forEach { label ->
            call(OFFER_SLIDER_TOOL, """{"label":"$label","tool":"brightness_set","param":"targetPercent","arguments":{"screen":"center"},"value":40}""")
        }
        assertEquals(listOf("亮度", "温度", "风量"), DebugOfferStore.latest(SESSION)!!.controls.map { it.label })
        call(OFFER_SWITCH_TOOL, SEAT_SWITCH)
        call(OFFER_CHOICE_TOOL, AC_MODE)
        call(OFFER_CHOICE_TOOL, SEATS)
        assertEquals(listOf("温度", "风量", "主驾座椅加热", "空调模式", "座椅加热"), DebugOfferStore.latest(SESSION)!!.controls.map { it.label })
    }

    @Test
    fun theConfirmEventCarriesEachControlsValueInItsType() {
        val call = DebugBoundCall("screen", "x", "mcp__screen__x", "{}")
        val controls = listOf(
            DebugSliderOffer("亮度", call, "targetPercent", 0.0, 100.0, 1.0, 40.0, "%", true),
            DebugSwitchOffer("加热", call, call, false),
            DebugChoiceOffer("模式", listOf(DebugChoiceOption("自动", call), DebugChoiceOption("制冷", call)), listOf(0), multiple = false),
        )
        val event = A2uiClientEventMessage(DEBUG_A2UI_SET_EVENT, "panel", "confirm", 0L, mapOf("v0" to 55.0, "v1" to true, "v2" to listOf("1")))

        assertEquals(listOf(55.0, true, listOf(1)), event.controlValues(controls))
        assertNull(A2uiClientEventMessage(DEBUG_A2UI_SET_EVENT, "panel", "confirm", 0L, mapOf("v0" to 55.0)).controlValues(controls))
    }

    @Test
    fun cardsLayItemsOutAsAGridAComparisonOrAStripAndShowProgress() = runBlocking {
        val tyres = call(SHOW_CARD_TOOL, """{"title":"胎压","layout":"grid","items":[{"title":"左前","value":"2.4 bar"},{"title":"右前","value":"2.4 bar"},""" +
            """{"title":"左后","value":"2.3 bar","detail":"偏低"},{"title":"右后","value":"2.4 bar"}],"progress":[{"label":"电量","value":78,"text":"78% · 续航 412 km"}]}""")
        assertTrue(tyres.resultJson, tyres.success)
        val card = DebugOfferStore.latest(SESSION)!!.cards.last()
        assertEquals(DebugCardLayout.GRID, card.layout)
        assertEquals(100.0, card.progress.single().max, 0.0)

        assertTrue(call(SHOW_CARD_TOOL, COMPARE).success)
        assertEquals(listOf("距离" to "1.2 km", "空闲" to "6/10"), DebugOfferStore.latest(SESSION)!!.cards.last().items.first().fields)
        assertEquals("WIDGET_INVALID", call(SHOW_CARD_TOOL, """{"title":"x","layout":"compare","items":[{"title":"a"}]}""").code())
        assertEquals("WIDGET_INVALID", call(SHOW_CARD_TOOL, """{"title":"x","layout":"table","items":[{"title":"a"},{"title":"b"}]}""").code())
        assertEquals("WIDGET_INVALID", call(SHOW_CARD_TOOL, """{"title":"x","progress":[{"label":"电量","value":120}]}""").code())
        assertEquals("WIDGET_INVALID", call(SHOW_CARD_TOOL, """{"title":"x","items":[{"title":"a","image":"http://img.example.com/a.jpg"}]}""").code())
        val pictured = call(SHOW_CARD_TOOL, """{"title":"餐厅","items":[{"title":"香香川菜","value":"人均 90","image":"generated-images/delegation-abc/2.jpg"}]}""")
        assertTrue(pictured.resultJson, pictured.success)
    }

    @Test
    fun aTripTooTightIsSentBackOnceAndItsRevisionShows() = runBlocking {
        val tight = """{"title":"上海⇄杭州 · 2 日行程","layout":"trip","items":[
            {"kind":"transport","title":"去程 · G205 高铁","value":"07:00 → 07:45"},
            {"kind":"transport","title":"回程 · G7364 高铁","value":"16:00 → 16:50"},
            {"group":"D2","title":"河坊街","value":"14:00–15:30"}]}"""

        assertEquals("WIDGET_INVALID", call(SHOW_CARD_TOOL, tight).code())
        assertTrue(call(SHOW_CARD_TOOL, tight).success)
        assertEquals(DebugCardLayout.TRIP, DebugOfferStore.latest(SESSION)!!.cards.last().layout)
    }

    @Test
    fun aTripCardShowsTicketsADayPerColumnAndHotelPictures() {
        val card = parseCard(parseDebugJsonObject("""{"title":"上海⇄成都 · 3 日行程","value":"3 天","layout":"trip","items":[
            {"kind":"transport","title":"去程 · 3U6992 川航","value":"11:55 → 14:50","detail":"浦东T2 → 天府T2"},
            {"kind":"weather","group":"D1 10/23 周五","icon":"rain","title":"小雨","value":"16–22°","detail":"降雨 60%"},
            {"kind":"place","group":"D1 10/23 周五","title":"熊猫基地","value":"16:00–18:00","image":"https://img.example.com/panda.jpg"},
            {"kind":"place","group":"D1 10/23 周五","title":"宽窄巷子","value":"晚上"},
            {"kind":"place","group":"D2 10/24 周六","title":"武侯祠","value":"上午"},
            {"kind":"transport","title":"回程 · 9C6102 春秋","value":"08:30 → 11:05"},
            {"kind":"stay","title":"亚朵S酒店","value":"约 800 元/晚","image":"https://img.example.com/hotel.jpg"}]}""")!!)
        val messages = debugA2uiCardMessages("card", card)

        assertEquals(listOf("card"), accepted(messages))
        val components = messages.joinToString()
        // A ticket per direction, then a column per day (its name, its first photo, a Stop per place), then the hotel.
        assertTrue(components.contains("trip_ticket_1") && !components.contains("trip_ticket_2") && components.contains("\"leg\":\"回程\""))
        assertTrue(components.contains("\"mode\":\"flight\""))
        assertTrue(components.contains("trip_day_1") && !components.contains("trip_day_2") && components.contains("D2 10/24 周六"))
        assertTrue(components.contains("trip_day_0_photo") && components.contains("trip_day_0_weather") && components.contains("小雨 · 16–22° · 降雨 60%"))
        assertTrue(components.contains("\"component\":\"Stay\"") && components.contains("\"image\":\"https://img.example.com/hotel.jpg\""))
        assertEquals(3, Regex("\"component\":\"Stop\"").findAll(components).count())
        assertTrue(runCatching { parseCard(parseDebugJsonObject("""{"title":"x","layout":"trip","items":[{"title":"a","kind":"flight"}]}""")!!) }.isFailure)
    }

    @Test
    fun theEngineAcceptsThePanelAndEveryCardLayout() {
        val call = DebugBoundCall("screen", "x", "mcp__screen__x", "{}")
        val panel = listOf(
            DebugSliderOffer("温度", call, "t", 16.0, 32.0, 0.5, 24.0, "℃", false),
            DebugSwitchOffer("座椅加热", call, call, true),
            DebugChoiceOffer("模式", listOf(DebugChoiceOption("自动", call), DebugChoiceOption("制冷", call)), listOf(0), multiple = false),
            DebugChoiceOffer("座位", listOf(DebugChoiceOption("主驾", call), DebugChoiceOption("后排左侧座椅", call)), emptyList(), multiple = true),
        )
        val items = listOf(
            DebugCardItem("sunny", "明天", "晴", "30/22°", fields = listOf("距离" to "1 km")),
            DebugCardItem("rain", "后天", "小雨", "27/21°", image = "generated-images/delegation-abc/1.jpg", fields = listOf("价格" to "1.2 元")),
        )
        val base = DebugCard("天气", "sunny", "31°", "晴", listOf("湿度" to "60%"), items, progress = listOf(DebugCardProgress("电量", 78.0, 100.0, null)))
        val trip = base.copy(
            items = listOf(
                DebugCardItem(null, "西湖", "断桥到苏堤", "09:00", image = "https://img.example.com/xihu.jpg", group = "第1天"),
                DebugCardItem(null, "灵隐寺", null, "14:00", image = "https://img.example.com/lingyin.jpg", group = "第1天"),
                DebugCardItem("music", "宋城", "千古情", "19:00", group = "第2天"),
            ),
        )
        assertEquals(listOf("card"), accepted(debugA2uiCardMessages("card", trip)))

        assertEquals(listOf("panel"), accepted(debugA2uiPanelMessages("panel", panel)))
        DebugCardLayout.entries.forEach { layout ->
            assertEquals(layout.wire, listOf("card"), accepted(debugA2uiCardMessages("card", base.copy(layout = layout))))
            assertEquals(layout.wire, listOf("card"), accepted(debugA2uiCardMessages("card", base.copy(layout = layout)), carLook = false))
        }
    }


    @Test
    fun aTripListsItsDaysAsSectionsWithPhotos() = runBlocking {
        val trip = call(
            SHOW_CARD_TOOL,
            """{"title":"杭州两日游","image":"https://img.example.com/hero.jpg","items":[""" +
                """{"group":"第1天","title":"西湖","value":"09:00","image":"https://img.example.com/xihu.jpg"},""" +
                """{"group":"第1天","title":"灵隐寺","value":"14:00"},{"group":"第2天","title":"西溪湿地","value":"09:30"}]}""",
        )
        assertTrue(trip.resultJson, trip.success)
        val card = DebugOfferStore.latest(SESSION)!!.cards.last()
        val components = Json.parseToJsonElement(debugA2uiCardMessages("trip", card).last()).jsonObject["updateComponents"]!!
            .jsonObject["components"]!!.jsonArray.map { it.jsonObject }
        val rows = components.single { it["id"]!!.jsonPrimitive.content == "items" }["children"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("group_0", "item_0", "item_1", "group_2", "item_2"), rows)
        // With stops to list, the cover is a banner inside the card, not the big frame beside the reply.
        assertNull(card.framedPicture)
        assertEquals("banner", components.single { it["id"]!!.jsonPrimitive.content == "root" }["children"]!!.jsonArray.first().jsonPrimitive.content)
        assertEquals("https://img.example.com/xihu.jpg", components.single { it["id"]!!.jsonPrimitive.content == "item_0_picture" }["url"]!!.jsonPrimitive.content)
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

    /** The surfaces the engine created; it must report no error for any message. */
    private fun accepted(messages: List<String>, carLook: Boolean = true): List<String> {
        val (surfaces, errors) = render(messages, carLook)
        assertTrue("errors=$errors", errors.isEmpty())
        return surfaces
    }

    private fun render(messages: List<String>, carLook: Boolean = true): Pair<List<String>, List<A2uiClientErrorMessage>> {
        val processor = debugA2uiProcessor(carLook)
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

    private suspend fun call(tool: String, args: String): ToolCallResult = tools.callTool(tool, args, ToolCallContext(SESSION, TURN, TURN))

    private fun ToolCallResult.code(): String? = Json.parseToJsonElement(resultJson).jsonObject["code"]?.jsonPrimitive?.content

    private fun DebugOffer.sliders(): List<DebugSliderOffer> = controls.filterIsInstance<DebugSliderOffer>()
}

private class ScreenProvider : ToolProvider {
    override val providerId = "screen"
    override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
    override suspend fun listTools() = listOf(
        ToolDescriptor(
            "volume_set", "音量",
            """{"type":"object","required":["stream","volume"],"properties":{"stream":{"type":"string","enum":["media","navi"]},""" +
                """"volume":{"type":"integer","minimum":0,"maximum":40}}}""",
        ),
        ToolDescriptor(
            "brightness_set", "屏幕亮度",
            """{"type":"object","required":["screen","targetPercent"],"properties":{"screen":{"type":"string","enum":["center","meter"]},""" +
                """"targetPercent":{"type":"integer","minimum":0,"maximum":100}}}""",
        ),
        ToolDescriptor(
            "seat_heat_set", "座椅加热",
            """{"type":"object","required":["seat","level"],"properties":{"seat":{"type":"string","enum":["driver","passenger"]},""" +
                """"level":{"type":"integer","minimum":0,"maximum":3}}}""",
        ),
        ToolDescriptor(
            "wheel_heat_switch", "方向盘加热",
            """{"type":"object","required":["state"],"properties":{"state":{"type":"string","enum":["on","off"]}}}""",
        ),
        ToolDescriptor(
            "ac_mode_set", "空调模式",
            """{"type":"object","required":["zone","mode"],"properties":{"zone":{"type":"string","enum":["all","driver"]},""" +
                """"mode":{"type":"string","enum":["auto","cool","heat"]}}}""",
        ),
    )
    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext) = error("not executed")
}

private const val SESSION = "s"
private const val TURN = "turn-1"
private const val NOW = 1_000_000L
private const val ENGINE_WAIT_MS = 10_000L
private const val ENGINE_POLL_MS = 20L
private const val SETTLE_POLLS = 10
private const val SEAT_SWITCH = """{"label":"主驾座椅加热","on":{"tool":"seat_heat_set","arguments":{"seat":"driver","level":2}},""" +
    """"off":{"tool":"seat_heat_set","arguments":{"seat":"driver","level":0}},"value":false}"""
private const val AC_MODE = """{"label":"空调模式","tool":"ac_mode_set","param":"mode","arguments":{"zone":"all"},""" +
    """"options":[{"label":"自动","value":"auto"},{"label":"制冷","value":"cool"},{"label":"制热","value":"heat"}],"current":"auto"}"""
private const val SEATS = """{"label":"座椅加热","multiple":true,"options":[""" +
    """{"label":"主驾","tool":"seat_heat_set","arguments":{"seat":"driver","level":2}},""" +
    """{"label":"副驾","tool":"seat_heat_set","arguments":{"seat":"passenger","level":2}}]}"""
private const val COMPARE = """{"title":"附近充电站","layout":"compare","items":[""" +
    """{"title":"万达","value":"1.2 元/度","fields":[{"label":"距离","value":"1.2 km"},{"label":"空闲","value":"6/10"}]},""" +
    """{"title":"南网","value":"1.0 元/度","fields":[{"label":"距离","value":"3.5 km"},{"label":"空闲","value":"2/8"}]},""" +
    """{"title":"特来电","value":"1.4 元/度","fields":[{"label":"距离","value":"0.8 km"}]}]}"""
