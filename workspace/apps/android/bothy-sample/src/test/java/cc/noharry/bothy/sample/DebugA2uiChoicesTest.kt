package cc.noharry.bothy.sample

import android.app.Application
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiClientToServerMessage
import androidx.a2ui.model.protocol.A2uiProtocolConstants
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

// A plain Application: the sample one starts servers and background work that would run while the looper is idled.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DebugA2uiChoicesTest {
    private val choices = listOf(
        DebugA2uiChoice("东广场停车场（推荐，离进站口近）", recommended = true),
        DebugA2uiChoice("西广场停车场", recommended = false),
    )

    @Test
    fun createSurfaceNamesTheRenderersCatalogAndProtocol() {
        val create = Json.parseToJsonElement(debugA2uiChoiceMessages("pick-1", "选哪个？", choices).first()).jsonObject

        assertEquals(A2uiProtocolConstants.PROTOCOL_VERSION, create["version"]!!.jsonPrimitive.content)
        assertEquals(debugA2uiCatalog().id, create["createSurface"]!!.jsonObject["catalogId"]!!.jsonPrimitive.content)
    }

    @Test
    fun hostCompiledChoicesAreAcceptedByTheAndroidxEngine() {
        val processor = debugA2uiProcessor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val outbound = CopyOnWriteArrayList<A2uiClientToServerMessage>()
        scope.launch { processor.collectMessages() }
        scope.launch { processor.outboundEvents.collect { outbound += it } }

        processor.processJson(debugA2uiChoiceMessages("pick-1", "选哪个停车场？", choices))
        // The engine may post to the main looper, which Robolectric keeps paused; drive it while polling.
        val deadline = System.currentTimeMillis() + ENGINE_WAIT_MS
        while (processor.activeSurfaces.value.isEmpty() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(ENGINE_POLL_MS)
        }
        // Read before cancelling: collectMessages clears every surface when it ends, on another thread.
        val surfaces = processor.activeSurfaces.value.map { it.id }
        scope.cancel()

        assertEquals("outbound=$outbound", listOf("pick-1"), surfaces)
    }

    @Test
    fun choicesBecomeButtonsWithOrdinalPickEvents() {
        val update = Json.parseToJsonElement(debugA2uiChoiceMessages("pick-1", "选哪个停车场？", choices).last()).jsonObject

        val components = update["updateComponents"]!!.jsonObject["components"]!!.jsonArray.map { it.jsonObject }
        val second = components.single { it["id"]!!.jsonPrimitive.content == "choice_2" }
        assertEquals("Button", second["component"]!!.jsonPrimitive.content)
        val context = second["action"]!!.jsonObject["event"]!!.jsonObject["context"]!!.jsonObject
        assertEquals(2, context["ordinal"]!!.jsonPrimitive.content.toInt())
        val first = components.single { it["id"]!!.jsonPrimitive.content == "choice_1" }
        assertEquals("primary", first["variant"]!!.jsonPrimitive.content)
    }

    @Test
    fun overlaySurfaceWithoutPromptHoldsOnlyTheChoices() {
        val update = Json.parseToJsonElement(debugA2uiChoiceMessages("pick-1", null, choices).last()).jsonObject
        val components = update["updateComponents"]!!.jsonObject["components"]!!.jsonArray.map { it.jsonObject }
        val root = components.single { it["id"]!!.jsonPrimitive.content == "root" }

        assertEquals(listOf("choice_1", "choice_2"), root["children"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(null, components.firstOrNull { it["id"]!!.jsonPrimitive.content == "prompt" })
    }

    @Test
    fun trailingFullWidthNoteBecomesTheDetailLineWithoutTheRecommendationWord() {
        assertEquals("东广场停车场" to "离进站口近", DebugA2uiChoice("东广场停车场（推荐，离进站口近）", true).titleAndDetail())
        assertEquals("周杰伦《晴天》" to "《叶惠美》原版", DebugA2uiChoice("周杰伦《晴天》（《叶惠美》原版，推荐）", true).titleAndDetail())
        assertEquals("西广场" to null, DebugA2uiChoice("西广场（推荐）", true).titleAndDetail())
        assertEquals("林俊杰《晴天 (Live)》" to null, DebugA2uiChoice("林俊杰《晴天 (Live)》", false).titleAndDetail())
        assertEquals("（推荐）" to null, DebugA2uiChoice("（推荐）", true).titleAndDetail())
    }

    @Test
    fun aDashOrColonSeparatesTheNameFromItsDetailsWhereverTheRecommendationSits() {
        assertEquals(
            "珠江城地下停车场" to "珠江西路15号，白天6元/30分钟",
            DebugA2uiChoice("珠江城地下停车场（推荐）——珠江西路15号，白天6元/30分钟", true).titleAndDetail(),
        )
        assertEquals(
            "海滨花园停车场" to "最便宜，临江大道403号",
            DebugA2uiChoice("海滨花园停车场（推荐，最便宜）：临江大道403号", true).titleAndDetail(),
        )
        assertEquals("晴天 (Live)" to "周杰伦", DebugA2uiChoice("晴天 (Live) — 周杰伦", false).titleAndDetail())
    }

    @Test
    fun timesAndBracketedColonsNeverSplitTheName() {
        assertEquals(
            "昆仑网电·天河区高普路充电站" to "19个快充，10:00-12:00 约1.18元/度",
            DebugA2uiChoice("昆仑网电·天河区高普路充电站（19个快充，10:00-12:00 约1.18元/度）", false).titleAndDetail(),
        )
        assertEquals(
            "南方电网充电站" to "8快充，营业10:00-22:00",
            DebugA2uiChoice("南方电网充电站：8快充，营业10:00-22:00", false).titleAndDetail(),
        )
    }

    @Test
    fun rowsStretchAndCarryATitleAndCaption() {
        val update = Json.parseToJsonElement(debugA2uiChoiceMessages("pick-1", null, choices).last()).jsonObject
        val components = update["updateComponents"]!!.jsonObject["components"]!!.jsonArray.map { it.jsonObject }
        fun byId(id: String) = components.single { it["id"]!!.jsonPrimitive.content == id }

        assertEquals("stretch", byId("root")["align"]!!.jsonPrimitive.content)
        assertEquals("choice_1_body", byId("choice_1")["child"]!!.jsonPrimitive.content)
        assertEquals("东广场停车场", byId("choice_1_title")["text"]!!.jsonPrimitive.content)
        assertEquals("h5", byId("choice_1_title")["variant"]!!.jsonPrimitive.content)
        assertEquals("离进站口近", byId("choice_1_detail")["text"]!!.jsonPrimitive.content)
        assertEquals("caption", byId("choice_1_detail")["variant"]!!.jsonPrimitive.content)
        assertEquals(null, components.firstOrNull { it["id"]!!.jsonPrimitive.content == "choice_2_detail" })
    }

    @Test
    fun aSecondColumnKeepsTheOrdinalsTheDriverWouldSay() {
        val update = Json.parseToJsonElement(debugA2uiChoiceMessages("pick-1-b", null, choices, firstOrdinal = 5).last()).jsonObject
        val components = update["updateComponents"]!!.jsonObject["components"]!!.jsonArray.map { it.jsonObject }
        val root = components.single { it["id"]!!.jsonPrimitive.content == "root" }
        val sixth = components.single { it["id"]!!.jsonPrimitive.content == "choice_6" }
        val context = sixth["action"]!!.jsonObject["event"]!!.jsonObject["context"]!!.jsonObject

        assertEquals(listOf("choice_5", "choice_6"), root["children"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(6, context["ordinal"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun tappedPickReportsItsOrdinal() {
        val event = A2uiClientEventMessage(DEBUG_A2UI_PICK_EVENT, "pick-1", "choice_2", 0L, mapOf("ordinal" to 2.0))

        assertEquals(2, event.pickedOrdinal())
        assertNull(A2uiClientEventMessage(DEBUG_A2UI_PICK_EVENT, "pick-1", "x", 0L, emptyMap()).pickedOrdinal())
    }
}

/** Up to 10 s for the surface: the engine works on Dispatchers.Default, slow to start under a full validate run. */
private const val ENGINE_WAIT_MS = 10_000L
private const val ENGINE_POLL_MS = 100L
