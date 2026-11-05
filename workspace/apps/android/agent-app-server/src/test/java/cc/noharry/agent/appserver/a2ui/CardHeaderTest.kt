package cc.noharry.agent.appserver.a2ui
import cc.noharry.agent.appserver.protocol.view.CardItem
import cc.noharry.agent.appserver.protocol.view.CardLayout
import cc.noharry.agent.appserver.protocol.view.WidgetCard
import java.net.UnknownHostException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardHeaderTest {
    private val banner = "https://n.sinaimg.cn/front/w381h382/banner.jpg"
    private val camp = "https://www.sznews.com/pic/camp.jpg"
    private val pingu = "https://www.amazingshenzhen.com.cn/pic/pingu.jpg"
    private val sizes = mapOf(banner to (381 to 382), camp to (1080 to 720), pingu to (800 to 600))

    @Test
    fun theWidestRealPhotoLeadsInsteadOfTheBannerTheModelNamed() = runBlocking {
        val card = card(header = banner, banner, camp, pingu).withBestHeader { sizes[it] }

        assertEquals(camp, card.image)
        assertEquals(listOf(banner, camp, pingu), card.items.map { it.image })
    }

    @Test
    fun noWidePictureMeansNoHeaderAndAPhotoCardKeepsItsPhoto() = runBlocking {
        assertNull(card(header = banner, banner).withBestHeader { sizes[it] }.image)
        // One that does not load in time is not chosen either.
        assertNull(card(header = camp).withBestHeader { delay(10_000); sizes[it] }.image)
        val photoOnly = card(header = banner).copy(items = emptyList())
        assertEquals(banner, photoOnly.withBestHeader { sizes[it] }.image)
        // Nor does one that cannot be fetched (offline, a made-up host): the card still shows.
        assertEquals(pingu, card(header = banner, pingu, camp).withBestHeader { if (it == camp) throw UnknownHostException(it) else sizes[it] }.image)
        // A trip's photos lead its days instead.
        assertNull(card(header = camp, camp).copy(layout = CardLayout.TRIP).withBestHeader { sizes[it] }.image)
    }

    private fun card(header: String?, vararg pictures: String) = WidgetCard(
        "周末活动", null, null, null, emptyList(),
        items = pictures.map { CardItem(null, it.substringAfterLast('/'), null, null, image = it) }.ifEmpty { listOf(CardItem(null, "露营节", null, null)) },
        image = header,
    )
}
