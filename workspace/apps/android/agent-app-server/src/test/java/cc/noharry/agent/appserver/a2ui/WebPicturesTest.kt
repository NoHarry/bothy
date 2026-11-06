package cc.noharry.agent.appserver.a2ui
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class WebPicturesTest {
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
