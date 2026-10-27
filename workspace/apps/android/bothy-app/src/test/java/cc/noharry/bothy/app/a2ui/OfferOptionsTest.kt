@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.bothy.app.a2ui
import cc.noharry.agent.kernel.api.*
import cc.noharry.bothy.app.console.status
import cc.noharry.bothy.app.runtime.PreviousExchange
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class OfferOptionsTest {
    private val lamp = LampProvider()
    private val offers = OfferToolProvider(BindableTools(listOf(lamp))) { NOW }

    @After
    fun clearStore() = OfferStore.clear()

    @Test
    fun ordinalsAreReadFromTapsAndSpeech() {
        assertEquals(2, pickOrdinal("第2个".offerKey()))
        assertEquals(2, pickOrdinal("第二个吧。".offerKey()))
        assertEquals(3, pickOrdinal("选第三项".offerKey()))
        assertEquals(1, pickOrdinal("1"))
        assertNull(pickOrdinal("两个".offerKey()))
        assertNull(pickOrdinal("第九个".offerKey()))
        assertNull(pickOrdinal("蓝色"))
    }

    @Test
    fun aPickCarriesTheCheckedCallOfTheItemAsShown() = runBlocking {
        offerColors()
        val previous = previous("想换成什么颜色？\n\n1. 红色\n2. 蓝色（推荐，夜里柔和）\n3. 不用了")

        // The model registered 蓝色 first but listed it second: "第2个" means what the user saw.
        val pick = offerPick("第2个", previous, OfferStore.latest(SESSION), NOW)!!

        assertEquals("蓝色", pick.label)
        assertTrue(pick.input.contains("mcp__lamp__ambient_color_set"))
        assertTrue(pick.input.contains("""{"color":"blue"}"""))
        assertEquals("红色", offerPick("红色！", previous, OfferStore.latest(SESSION), NOW)!!.label)
    }

    @Test
    fun anOfferedListNeedsNoRecommendationOrQuestion() = runBlocking {
        offerColors()
        val plain = "颜色有三种，想换成哪种跟我说一声。\n\n1. 蓝色\n2. 红色\n3. 不用了"

        assertTrue(plain.displayChoiceReply().choices.isEmpty())
        assertEquals(3, plain.displayChoiceReply(OfferStore.offeredLabels(SESSION)).choices.size)
        assertTrue(plain.displayChoiceReply(listOf("绿色", "紫色")).choices.isEmpty())
        assertEquals("红色", offerPick("第2个", previous(plain), OfferStore.latest(SESSION), NOW)!!.label)
    }

    @Test
    fun aSpokenLabelSkipsTheBracketedNote() = runBlocking {
        val result = offer(
            """[{"label":"0 档（关）","tool":"ambient_level_set","arguments":{"level":0}},""" +
                """{"label":"1 档（最弱）","tool":"ambient_level_set","arguments":{"level":1}}]""",
        )
        assertTrue(result.resultJson, result.success)
        val previous = previous("想换哪档？\n\n1. 0 档（关）\n2. 1 档（最弱）")

        assertEquals("""{"level":1}""", offerPick("1档", previous, OfferStore.latest(SESSION), NOW)!!.calls.single().argumentsJson)
    }

    @Test
    fun noBindingWithoutACallOrForAnotherTurn() = runBlocking {
        offerColors()
        val offer = OfferStore.latest(SESSION)
        val shown = "1. 红色\n2. 蓝色（推荐）\n3. 不用了"

        assertNull(offerPick("第3个", previous(shown), offer, NOW))
        assertNull(offerPick("第1个", previous(shown, turnId = "later"), offer, NOW))
        assertNull(offerPick("第1个", previous(shown, status = "FAILED"), offer, NOW))
        assertNull(offerPick("第1个", previous(shown), offer, NOW + 31 * 60_000L))
        assertNull(offerPick("帮我导航回家", previous(shown), offer, NOW))
    }

    @Test
    fun anOptionMustFitItsToolsSchema() = runBlocking {
        assertEquals("OPTION_INVALID", offer("""[{"label":"紫色","tool":"ambient_color_set","arguments":{"color":"violet"}},{"label":"不用了"}]""").code())
        assertEquals("OPTION_INVALID", offer("""[{"label":"6档","tool":"ambient_level_set","arguments":{"level":6}},{"label":"不用了"}]""").code())
        assertEquals("OPTION_INVALID", offer("""[{"label":"红色","tool":"ambient_color_set","arguments":{"color":"red","x":1}},{"label":"不用了"}]""").code())
        assertEquals("OPTION_INVALID", offer("""[{"label":"红色","tool":"mcp__lamp__nope"},{"label":"不用了"}]""").code())
        assertEquals("ARGUMENT_INVALID", offer("""[{"label":"红色"}]""").code())
        assertNull(OfferStore.latest(SESSION))

        val ok = offer("""[{"label":"5档","tool":"mcp__lamp__ambient_level_set","arguments":{"level":5}},{"label":"不用了"}]""")
        assertTrue(ok.success)
        assertEquals("ambient_level_set", OfferStore.latest(SESSION)!!.options[0].call!!.toolName)
    }

    @Test
    fun thePickedToolIsOfferedFromTheFirstRound() = runBlocking {
        offerColors()
        val pick = offerPick("第1个", previous("1. 蓝色（推荐）\n2. 红色"), OfferStore.latest(SESSION), NOW)!!
        OfferStore.startPick(SESSION, pick)
        val hints = BoundCallHints(lamp)
        val tools = lamp.listTools()

        assertEquals(listOf("ambient_color_set"), hints.localToolNames(ToolPrefetchRequest(pick.input, tools, ToolCallContext(SESSION, "r", "q"), 24)))
        assertEquals(emptyList<String>(), hints.localToolNames(ToolPrefetchRequest("别的话", tools, ToolCallContext(SESSION, "r", "q"), 24)))
        assertEquals(listOf(OFFER_OPTIONS_TOOL, OFFER_SLIDER_TOOL, OFFER_SWITCH_TOOL, OFFER_CHOICE_TOOL, SHOW_CARD_TOOL), offers.localToolNames(ToolPrefetchRequest("任意", emptyList(), ToolCallContext(SESSION, "r", "q"), 24)))
    }

    private suspend fun offerColors() {
        val result = offer(
            """[{"label":"蓝色","tool":"mcp__lamp__ambient_color_set","arguments":{"color":"blue"}},""" +
                """{"label":"红色","tool":"ambient_color_set","arguments":{"color":"red"}},{"label":"不用了"}]""",
        )
        assertTrue(result.resultJson, result.success)
    }

    private suspend fun offer(options: String): ToolCallResult =
        offers.callTool(OFFER_OPTIONS_TOOL, """{"options":$options}""", ToolCallContext(SESSION, TURN, TURN))

    private fun previous(text: String, turnId: String = TURN, status: String = "COMPLETED") =
        PreviousExchange(SESSION, turnId, "氛围灯换个颜色", text, status, "SUCCEEDED")

    private fun ToolCallResult.code(): String? = Json.parseToJsonElement(resultJson).jsonObject["code"]?.jsonPrimitive?.content
}

private class LampProvider : ToolProvider {
    override val providerId = "lamp"
    override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
    override suspend fun listTools() = listOf(
        ToolDescriptor(
            "ambient_color_set", "氛围灯颜色",
            """{"type":"object","required":["color"],"properties":{"color":{"type":"string","enum":["red","blue"]}}}""",
        ),
        ToolDescriptor(
            "ambient_level_set", "氛围灯亮度",
            """{"type":"object","required":["level"],"properties":{"level":{"type":"integer","minimum":0,"maximum":5}}}""",
        ),
    )
    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext) = error("not executed")
}

private const val SESSION = "s"
private const val TURN = "turn-1"
private const val NOW = 1_000_000L
