package cc.noharry.bothy.app.model

import android.app.Application
import android.content.res.Resources
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.ServiceView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where to get a key, under the key field, in the app's language: the service's key page for the region chosen, with a
 * word that keys stay in their region where its regions are separate sites; the service's own sentence where it gives
 * no page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class KeyHintTest {
    private val resources: Resources get() = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun inEnglishTheHintNamesThePageOfTheRegionChosen() {
        assertEquals("Create an API key at https://platform.deepseek.com/api_keys.", resources.keyHintText(DEEPSEEK, ""))
        assertEquals(
            "Create an API key at https://platform.kimi.ai/console/api-keys. Each region has its own site, and a key works only in the region it was made in.",
            resources.keyHintText(MOONSHOT, "global"),
        )
    }

    @Test
    @Config(qualifiers = "zh")
    fun inChineseTheHintIsChinese() {
        assertEquals("在 https://platform.deepseek.com/api_keys 创建 API key。", resources.keyHintText(DEEPSEEK, ""))
        assertEquals(
            "在 https://platform.kimi.com/console/api-keys 创建 API key。每个地区有自己的站点，key 只能在创建它的地区使用。",
            resources.keyHintText(MOONSHOT, "cn"),
        )
        assertEquals(
            "在 https://platform.kimi.ai/console/api-keys 创建 API key。每个地区有自己的站点，key 只能在创建它的地区使用。",
            resources.keyHintText(MOONSHOT, "global"),
        )
    }

    @Test
    fun oneSiteForEveryRegionSaysNothingOfRegions() {
        val xai = ServiceView(
            id = "xai", label = "xAI", keyHint = "Create an API key at https://console.x.ai/team/default/api-keys",
            keyPages = mapOf("" to "https://console.x.ai/team/default/api-keys"),
        )
        assertEquals("Create an API key at https://console.x.ai/team/default/api-keys.", resources.keyHintText(xai, "us"))
    }

    @Test
    fun aServiceWithoutKeyPagesKeepsItsOwnSentence() {
        val own = ServiceView(id = "own", label = "Own", keyHint = "Ask the gateway's admin for a key")
        assertEquals("Ask the gateway's admin for a key", resources.keyHintText(own, ""))
        assertNull(resources.keyHintText(ServiceView(id = "bare", label = "Bare"), ""))
    }

    @Test
    @Config(qualifiers = "zh")
    fun theChinesePagesAddressIsTheLink() {
        val hint = linkedText(resources.keyHintText(MOONSHOT, "cn")!!, SpanStyle())
        val links = hint.getLinkAnnotations(0, hint.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("https://platform.kimi.com/console/api-keys"), links)
    }
}
