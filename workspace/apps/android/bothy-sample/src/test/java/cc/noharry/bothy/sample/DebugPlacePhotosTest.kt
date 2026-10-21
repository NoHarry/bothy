package cc.noharry.bothy.sample

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Collections

class DebugPlacePhotosTest {
    @Test
    fun eachDayWithoutAPhotoAndEachHotelGetsOneSearchedInTheTripsCity() = runBlocking {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val card = DebugCard(
            "上海⇄成都 · 3 日行程", null, "3 天", null, emptyList(), layout = DebugCardLayout.TRIP,
            items = listOf(
                DebugCardItem(null, "去程 · 3U6992 川航", null, "11:55 → 14:50", kind = DebugCardItemKind.TRANSPORT),
                DebugCardItem(null, "成都大熊猫繁育研究基地（含花花）", null, group = "D1"),
                DebugCardItem(null, "宽窄巷子", null, group = "D1"),
                DebugCardItem(null, "武侯祠", null, group = "D2", image = "https://img.example.com/wuhou.jpg"),
                DebugCardItem(null, "锦里", null, group = "D2"),
                DebugCardItem(null, "机场 → 市中心酒店", null, group = "D3"),
                DebugCardItem(null, "人民公园 / 宽窄巷子买伴手礼", null, group = "D3"),
                DebugCardItem(null, "成都春熙路亚朵酒店", null, "约 800 元", kind = DebugCardItemKind.STAY),
            ),
        )

        val pictured = card.withPlacePhotos { queries -> asked += queries.joinToString(" | "); "https://img.example.com/${asked.size}.jpg" }

        // D2 has its photo already; the first sight of D1 and D3 (also as a sight; the way from the airport is none, a
        // joined title is its first place) and the hotel are looked up, the city added where missing.
        assertEquals(setOf("成都大熊猫繁育研究基地 景点 | 成都大熊猫繁育研究基地", "人民公园 成都 景点 | 人民公园 成都", "成都春熙路亚朵酒店"), asked.toSet())
        assertEquals(listOf(false, true, false, true, false, false, true, true), pictured.items.map { it.image != null })
        assertEquals("https://img.example.com/wuhou.jpg", pictured.items[3].image)
        // Only trip cards are pictured by the device.
        assertEquals(card.copy(layout = DebugCardLayout.LIST), card.copy(layout = DebugCardLayout.LIST).withPlacePhotos { "x" })
    }

    @Test
    fun theFirstResultPagesPhotoThatLoadsAsALandscapeWins() = runBlocking {
        val pictures = mapOf(
            "https://a.example.com" to listOf("https://img.example.com/tiny.png", "https://img.example.com/tall.jpg"),
            "https://b.example.com" to emptyList(),
            "https://c.example.com" to listOf("https://img.example.com/panda.jpg"),
        )
        val sizes = mapOf(
            "https://img.example.com/tiny.png" to (64 to 64),
            "https://img.example.com/tall.jpg" to (600 to 1200),
            "https://img.example.com/panda.jpg" to (1080 to 720),
        )

        // A query at a time: the next is searched (and billed) only when the one before found nothing.
        val searched = mutableListOf<String>()
        val search: suspend (String) -> List<String> = { query ->
            searched += query
            if ("景点" in query) listOf("https://c.example.com", "https://a.example.com") else listOf("https://a.example.com", "https://b.example.com")
        }

        assertEquals("https://img.example.com/panda.jpg", findPlacePhoto(listOf("熊猫基地 成都", "熊猫基地 成都 景点"), search, { pictures.getValue(it) }, { sizes[it] }))
        assertEquals(listOf("熊猫基地 成都", "熊猫基地 成都 景点"), searched)
        searched.clear()
        assertEquals("https://img.example.com/panda.jpg", findPlacePhoto(listOf("熊猫基地 成都 景点", "熊猫基地 成都"), search, { pictures.getValue(it) }, { sizes[it] }))
        assertEquals(listOf("熊猫基地 成都 景点"), searched)
        assertNull(findPlacePhoto(listOf("熊猫基地 成都"), search, { pictures.getValue(it) }, { sizes[it] }))
        assertNull(findPlacePhoto(listOf("没有"), { emptyList() }, { emptyList() }, { null }))
    }

    @Test
    fun aPagesCoverComesFirstThenThePhotosInItsText() {
        val page = "https://you.example.com/sight/panda".toHttpUrl()
        val html = """<head><meta content="/p/panda.jpg?a=1&amp;b=2" property="og:image"></head><body>
            <img src="/static/logo.png"><img data-original="https://cdn.example.com/hall.jpg" src="/loading.gif">
            <img src="http://cdn.example.com/plain.jpg"><img src="https://cdn.example.com/saleslist/sales_legacy.png"><img src="https://res.example.com/images/w_1200,l_Site_water_br_trans/tianchi.jpg"><img src="https://cdn.example.com/qrcode.jpg"><img src="//cdn.example.com/garden.jpg"></body>"""

        assertEquals(
            listOf("https://you.example.com/p/panda.jpg?a=1&b=2", "https://cdn.example.com/hall.jpg", "https://cdn.example.com/garden.jpg"),
            pagePictures(html, page),
        )
    }
}
