@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.a2ui
import android.app.Application
import cc.noharry.agent.appserver.protocol.view.CardItem
import cc.noharry.agent.appserver.protocol.view.CardItemKind
import cc.noharry.agent.appserver.protocol.view.CardLayout
import cc.noharry.agent.appserver.protocol.view.ChoiceOffer
import cc.noharry.agent.appserver.protocol.view.Offer
import cc.noharry.agent.appserver.protocol.view.SliderOffer
import cc.noharry.agent.appserver.protocol.view.WidgetCard
import cc.noharry.agent.appserver.protocol.view.panelReply
import cc.noharry.agent.appserver.protocol.view.parseJsonObject
import cc.noharry.agent.appserver.runtime.PreviousExchange
import cc.noharry.agent.kernel.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A plain Application: the sample one starts servers and background work that would run while the looper is idled.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UiWidgetsTest {
    private val tools = OfferToolProvider(BindableTools(listOf(ScreenProvider()))) { NOW }

    @After
    fun clearStore() = OfferStore.clear()

    @Test
    fun aSliderTakesItsRangeFromTheSchemaAndActsOnTheValueSaid() = runBlocking {
        val result = call(OFFER_SLIDER_TOOL, """{"label":"中控屏亮度","tool":"brightness_set","param":"targetPercent","arguments":{"screen":"center"},"value":40,"step":5,"unit":"%"}""")
        assertTrue(result.resultJson, result.success)
        val slider = OfferStore.latest(SESSION)!!.sliders().single()
        assertEquals(0.0, slider.min, 0.0)
        assertEquals(100.0, slider.max, 0.0)
        assertEquals("中控屏亮度设为35%", slider.replyText(33.2))

        val previous = PreviousExchange(SESSION, TURN, "亮度调一下", "可以拖动下面的滑块设置亮度。", "COMPLETED", "SUCCEEDED")
        val pick = offerPick("设为35%", previous, OfferStore.latest(SESSION), NOW)!!
        assertEquals("""{"screen":"center","targetPercent":35}""", pick.calls.single().argumentsJson)
        assertTrue(pick.input.contains("mcp__screen__brightness_set"))
        assertEquals(80.0, OfferStore.latest(SESSION)!!.sliders().single().valueSaid("调到 80")!!, 0.0)
        assertNull(offerPick("调到120", previous, OfferStore.latest(SESSION), NOW))
    }

    @Test
    fun severalSlidersAreSetTogetherByTheConfirmOrByVoice() = runBlocking {
        call(OFFER_SLIDER_TOOL, """{"label":"媒体音量","tool":"volume_set","param":"volume","arguments":{"stream":"media"},"value":17,"unit":"档"}""")
        call(OFFER_SLIDER_TOOL, """{"label":"中控屏亮度","tool":"brightness_set","param":"targetPercent","arguments":{"screen":"center"},"unit":"%"}""")
        val offer = OfferStore.latest(SESSION)!!
        assertEquals(listOf("媒体音量", "中控屏亮度"), offer.controls.map { it.label })
        val previous = PreviousExchange(SESSION, TURN, "我想调下音量和亮度", "拖动下面的滑块，调好点确定。", "COMPLETED", "SUCCEEDED")

        val confirmed = panelReply(offer.controls, listOf(22.0, 60.0))
        assertEquals("媒体音量设为22档，中控屏亮度设为60%", confirmed)
        val pick = offerPick(confirmed, previous, offer, NOW)!!
        assertEquals(listOf("""{"stream":"media","volume":22}""", """{"screen":"center","targetPercent":60}"""), pick.calls.map { it.argumentsJson })

        val spoken = offerPick("音量调到20和亮度调到80", previous, offer, NOW)!!
        assertEquals(listOf("volume_set", "brightness_set"), spoken.calls.map { it.toolName })
        assertEquals(1, offerPick("亮度调到50", previous, offer, NOW)!!.calls.size)
        assertNull(offerPick("调到50", previous, offer, NOW))
    }

    @Test
    fun aDegreeUnitStillBindsTheConfirmAndAMissingValueStartsMidway() = runBlocking {
        call(OFFER_SLIDER_TOOL, """{"label":"空调温度","tool":"brightness_set","param":"targetPercent","arguments":{"screen":"center"},"min":16,"max":32,"unit":"℃"}""")
        val offer = OfferStore.latest(SESSION)!!
        assertEquals(24.0, offer.sliders().single().value, 0.0)
        val previous = PreviousExchange(SESSION, TURN, "空调温度调一下", "拖动下面的滑块，调好点确定。", "COMPLETED", "SUCCEEDED")

        val confirmed = panelReply(offer.controls, listOf(26.0))
        assertEquals("空调温度设为26℃", confirmed)
        assertEquals("""{"screen":"center","targetPercent":26}""", offerPick(confirmed, previous, offer, NOW)!!.calls.single().argumentsJson)
        assertEquals(22.0, offer.sliders().single().valueSaid("调到22°C")!!, 0.0)
    }

    @Test
    fun aSliderMustVaryANumberInsideTheSchemasBounds() = runBlocking {
        assertEquals("WIDGET_INVALID", call(OFFER_SLIDER_TOOL, """{"label":"x","tool":"brightness_set","param":"screen"}""").code())
        assertEquals("WIDGET_INVALID", call(OFFER_SLIDER_TOOL, """{"label":"x","tool":"brightness_set","param":"targetPercent","max":120}""").code())
        assertEquals("WIDGET_INVALID", call(OFFER_SLIDER_TOOL, """{"label":"x","tool":"brightness_set","param":"targetPercent","arguments":{"screen":"rear"}}""").code())
        assertNull(OfferStore.latest(SESSION))
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
        val card = OfferStore.latest(SESSION)!!.cards.last()
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

        val cards = OfferStore.latest(SESSION)!!.cards
        assertEquals(listOf("深圳 · 下周末活动" to "7 场", "广州 · 下周末活动" to "9 场"), cards.map { it.title to it.value })
        listOf("佛山", "东莞").forEach { call(SHOW_CARD_TOOL, """{"title":"$it","value":"1 场"}""") }
        assertEquals(listOf("广州 · 下周末活动", "佛山", "东莞"), OfferStore.latest(SESSION)!!.cards.map { it.title })
    }

    @Test
    fun theClosingOfferNamesTheCloudAssistantOnlyWhileItIsConnected() = runBlocking {
        val card = """{"title":"潮州两日游","items":[{"title":"广济桥"}]}"""
        val connected = OfferToolProvider(BindableTools(listOf(ScreenProvider())), cloudAssistant = true) { NOW }
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

        assertEquals(listOf("亲子演出 · 核对后"), OfferStore.latest(SESSION)!!.cards.map { it.title })
        call(SHOW_CARD_TOOL, """{"title":"广州","items":[{"title":"粤剧《帝女花》"},{"title":"儿童剧《白雪公主》"},{"title":"杂技"}]}""")
        assertEquals(2, OfferStore.latest(SESSION)!!.cards.size)
        // Two cities' weather share 「周六」「周日」, which every such card has: still two cards.
        call(SHOW_CARD_TOOL, """{"title":"深圳天气","items":[{"title":"周六","value":"28°"},{"title":"周日","value":"27°"}]}""")
        call(SHOW_CARD_TOOL, """{"title":"广州天气","items":[{"title":"周六","value":"30°"},{"title":"周日","value":"29°"}]}""")
        assertTrue(OfferStore.latest(SESSION)!!.cards.map { it.title }.containsAll(listOf("深圳天气", "广州天气")))
    }

    @Test
    fun aCardShowsSavedPicturesAndPublicHttpsPhotosOnly() = runBlocking {
        val saved = call(SHOW_CARD_TOOL, """{"title":"香香川菜","value":"人均 90","image":"generated-images/delegation-abc/1.jpg"}""")
        assertTrue(saved.resultJson, saved.success)
        assertEquals("generated-images/delegation-abc/1.jpg", OfferStore.latest(SESSION)!!.cards.last().framedPicture)
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
    fun oneTurnGathersItsWidgetsAndTheNextTurnDropsThem() = runBlocking {
        call(SHOW_CARD_TOOL, """{"title":"花都区","value":"31°"}""")
        call(OFFER_OPTIONS_TOOL, """{"options":[{"label":"充电站","icon":"charging"},{"label":"停车场","icon":"parking"}]}""")
        val offer = OfferStore.latest(SESSION)!!

        assertEquals(1, offer.cards.size)
        assertEquals(mapOf("充电站" to "charging", "停车场" to "parking"), offer.icons)
        OfferStore.endTurn(SESSION)
        assertNull(OfferStore.latest(SESSION))
    }

    @Test
    fun aSwitchBindsItsOnAndOffCallsAndTheConfirmSendsOnlyWhatChanged() = runBlocking {
        assertTrue(call(OFFER_SWITCH_TOOL, SEAT_SWITCH).success)
        assertTrue(call(OFFER_SWITCH_TOOL, """{"label":"方向盘加热","on":{"tool":"wheel_heat_switch","arguments":{"state":"on"}},""" +
            """"off":{"tool":"wheel_heat_switch","arguments":{"state":"off"}},"value":true}""").success)
        val offer = OfferStore.latest(SESSION)!!
        val previous = PreviousExchange(SESSION, TURN, "加热都看看", "拨好开关点确定。", "COMPLETED", "SUCCEEDED")

        val confirmed = panelReply(offer.controls, listOf(true, true))
        assertEquals("主驾座椅加热打开", confirmed)
        assertEquals("""{"seat":"driver","level":2}""", offerPick(confirmed, previous, offer, NOW)!!.calls.single().argumentsJson)
        assertEquals("""{"state":"off"}""", offerPick("关掉方向盘加热", previous, offer, NOW)!!.calls.single().argumentsJson)
        assertEquals(listOf("seat_heat_set", "wheel_heat_switch"), offerPick("座椅加热打开，方向盘加热关闭", previous, offer, NOW)!!.calls.map { it.toolName })
        // Two switches: a bare "打开" names neither.
        assertNull(offerPick("打开", previous, offer, NOW))
        assertEquals("WIDGET_INVALID", call(OFFER_SWITCH_TOOL, SEAT_SWITCH.replace("\"level\":0", "\"level\":5")).code())
    }

    @Test
    fun aChoiceSetsOneValueOrRunsTheChosenActionsTogether() = runBlocking {
        assertTrue(call(OFFER_CHOICE_TOOL, AC_MODE).success)
        val mode = OfferStore.latest(SESSION)!!.controls.single() as ChoiceOffer
        assertEquals(listOf(0), mode.value)
        val previous = PreviousExchange(SESSION, TURN, "空调模式", "选好点确定。", "COMPLETED", "SUCCEEDED")
        val offer = OfferStore.latest(SESSION)!!

        assertEquals("空调模式设为制冷", panelReply(offer.controls, listOf(listOf(1))))
        assertEquals("""{"zone":"all","mode":"cool"}""", offerPick("空调模式设为制冷", previous, offer, NOW)!!.calls.single().argumentsJson)
        assertEquals("""{"zone":"all","mode":"heat"}""", offerPick("模式改成制热", previous, offer, NOW)!!.calls.single().argumentsJson)
        assertEquals("""{"zone":"all","mode":"cool"}""", offerPick("制冷", previous, offer, NOW)!!.calls.single().argumentsJson)
        assertEquals("""{"zone":"all","mode":"cool"}""", offerPick("制冷模式吧", previous, offer, NOW)!!.calls.single().argumentsJson)
        // Only a whole option counts: a new request that merely contains one is not a pick.
        assertNull(offerPick("帮我找台自动售货机", previous, offer, NOW))
        assertEquals("WIDGET_INVALID", call(OFFER_CHOICE_TOOL, AC_MODE.replace("\"heat\"", "\"dry\"")).code())

        OfferStore.endTurn(SESSION)
        assertTrue(call(OFFER_CHOICE_TOOL, SEATS).success)
        val seats = OfferStore.latest(SESSION)!!
        val both = panelReply(seats.controls, listOf(listOf(1, 0)))
        assertEquals("座椅加热选主驾、副驾", both)
        assertEquals(
            listOf("""{"seat":"driver","level":2}""", """{"seat":"passenger","level":2}"""),
            offerPick(both, previous, seats, NOW)!!.calls.map { it.argumentsJson },
        )
        assertEquals(1, offerPick("副驾", previous, seats, NOW)!!.calls.size)
        // Nothing chosen: the confirm says nothing, so nothing is sent.
        assertEquals("", panelReply(seats.controls, listOf(emptyList<Int>())))
    }

    @Test
    fun aPanelMixesControlsAndABareConfirmSetsWhatIsShown() = runBlocking {
        call(OFFER_SLIDER_TOOL, """{"label":"空调温度","tool":"brightness_set","param":"targetPercent","arguments":{"screen":"center"},"min":16,"max":32,"value":24,"unit":"℃"}""")
        call(OFFER_CHOICE_TOOL, AC_MODE)
        call(OFFER_SWITCH_TOOL, SEAT_SWITCH)
        val offer = OfferStore.latest(SESSION)!!
        val previous = PreviousExchange(SESSION, TURN, "调一下空调", "调好点确定。", "COMPLETED", "SUCCEEDED")
        val unchanged = listOf(24.0, listOf(0), false)

        val bare = panelReply(offer.controls, unchanged)
        assertEquals("空调温度设为24℃，空调模式设为自动，主驾座椅加热关闭", bare)
        val pick = offerPick(bare, previous, offer, NOW)!!
        assertEquals(listOf("brightness_set", "ac_mode_set", "seat_heat_set"), pick.calls.map { it.toolName })
        assertTrue(pick.input, pick.input.contains("「空调模式」自动"))
        assertEquals("空调模式设为制热，主驾座椅加热打开", panelReply(offer.controls, listOf(24.0, listOf(2), true)))
        assertEquals("空调温度设为26℃，空调模式设为制冷", panelReply(offer.controls, listOf(26.0, listOf(1), false)))
        assertEquals(2, offerPick("温度调到26，模式改成制冷", previous, offer, NOW)!!.calls.size)
    }

    @Test
    fun aPanelKeepsTheNewestFiveControlsAndThreeSliders() = runBlocking {
        listOf("音量", "亮度", "温度", "风量").forEach { label ->
            call(OFFER_SLIDER_TOOL, """{"label":"$label","tool":"brightness_set","param":"targetPercent","arguments":{"screen":"center"},"value":40}""")
        }
        assertEquals(listOf("亮度", "温度", "风量"), OfferStore.latest(SESSION)!!.controls.map { it.label })
        call(OFFER_SWITCH_TOOL, SEAT_SWITCH)
        call(OFFER_CHOICE_TOOL, AC_MODE)
        call(OFFER_CHOICE_TOOL, SEATS)
        assertEquals(listOf("温度", "风量", "主驾座椅加热", "空调模式", "座椅加热"), OfferStore.latest(SESSION)!!.controls.map { it.label })
    }

    @Test
    fun cardsLayItemsOutAsAGridAComparisonOrAStripAndShowProgress() = runBlocking {
        val tyres = call(SHOW_CARD_TOOL, """{"title":"胎压","layout":"grid","items":[{"title":"左前","value":"2.4 bar"},{"title":"右前","value":"2.4 bar"},""" +
            """{"title":"左后","value":"2.3 bar","detail":"偏低"},{"title":"右后","value":"2.4 bar"}],"progress":[{"label":"电量","value":78,"text":"78% · 续航 412 km"}]}""")
        assertTrue(tyres.resultJson, tyres.success)
        val card = OfferStore.latest(SESSION)!!.cards.last()
        assertEquals(CardLayout.GRID, card.layout)
        assertEquals(100.0, card.progress.single().max, 0.0)

        assertTrue(call(SHOW_CARD_TOOL, COMPARE).success)
        assertEquals(listOf("距离" to "1.2 km", "空闲" to "6/10"), OfferStore.latest(SESSION)!!.cards.last().items.first().fields)
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
        assertEquals(CardLayout.TRIP, OfferStore.latest(SESSION)!!.cards.last().layout)
    }

    @Test
    fun aTripCardKeepsItsTicketsDaysAndHotelPictures() {
        val card = parseCard(parseJsonObject("""{"title":"上海⇄成都 · 3 日行程","value":"3 天","layout":"trip","items":[
            {"kind":"transport","title":"去程 · 3U6992 川航","value":"11:55 → 14:50","detail":"浦东T2 → 天府T2"},
            {"kind":"weather","group":"D1 10/23 周五","icon":"rain","title":"小雨","value":"16–22°","detail":"降雨 60%"},
            {"kind":"place","group":"D1 10/23 周五","title":"熊猫基地","value":"16:00–18:00","image":"https://img.example.com/panda.jpg"},
            {"kind":"place","group":"D1 10/23 周五","title":"宽窄巷子","value":"晚上"},
            {"kind":"place","group":"D2 10/24 周六","title":"武侯祠","value":"上午"},
            {"kind":"transport","title":"回程 · 9C6102 春秋","value":"08:30 → 11:05"},
            {"kind":"stay","title":"亚朵S酒店","value":"约 800 元/晚","image":"https://img.example.com/hotel.jpg"}]}""")!!)
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

        // UiWidgetsUiTest draws this card.
        assertEquals(WidgetCard("上海⇄成都 · 3 日行程", null, "3 天", null, emptyList(), items, layout = CardLayout.TRIP), card)
        assertTrue(runCatching { parseCard(parseJsonObject("""{"title":"x","layout":"trip","items":[{"title":"a","kind":"flight"}]}""")!!) }.isFailure)
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
        val card = OfferStore.latest(SESSION)!!.cards.last()
        val items = listOf(
            CardItem(null, "西湖", null, "09:00", image = "https://img.example.com/xihu.jpg", group = "第1天"),
            CardItem(null, "灵隐寺", null, "14:00", group = "第1天"),
            CardItem(null, "西溪湿地", null, "09:30", group = "第2天"),
        )

        // UiWidgetsUiTest draws this card: a section per day, the cover as a banner.
        assertEquals(WidgetCard("杭州两日游", null, null, null, emptyList(), items, image = "https://img.example.com/hero.jpg"), card)
        // With stops to list, the cover is a banner inside the card, not the big frame beside the reply.
        assertNull(card.framedPicture)
    }

    private suspend fun call(tool: String, args: String): ToolCallResult = tools.callTool(tool, args, ToolCallContext(SESSION, TURN, TURN))

    private fun ToolCallResult.code(): String? = Json.parseToJsonElement(resultJson).jsonObject["code"]?.jsonPrimitive?.content

    private fun Offer.sliders(): List<SliderOffer> = controls.filterIsInstance<SliderOffer>()
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
