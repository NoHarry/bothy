package cc.noharry.bothy.app.page

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

class PageDocumentTest {
    private val colors = PageColors(
        bg = Color(0xFFFFFFFF),
        surface = Color(0xFFF5F4EE),
        text = Color(0xFF1B1C19),
        muted = Color(0xFF56615B),
        accent = Color(0xFF005238),
        onAccent = Color.White,
        border = Color(0xFFE2DFD8),
        warn = Color(0xFF904E00),
        danger = Color(0xFFBA1A1A),
        dark = false,
    )

    @Test
    fun theHostsHeadGoesFirstSoThePagesOwnStylesWin() {
        val document = pageDocument("<html lang=\"zh\"><head><style>body{color:red}</style></head><body>x</body></html>", colors)
        assertTrue(document, document.startsWith("<!doctype html><html><head><meta charset"))
        assertTrue(document.indexOf("--page-accent:#005238") < document.indexOf("body{color:red}"))
        assertTrue(document.contains("default-src 'none'") && document.contains("form-action 'none'"))
        assertTrue(document.contains("window.assistant={send:"))
        assertTrue(document.contains(".b-card{") && document.contains("--page-warn:#904E00"))
    }

    @Test
    fun nothingThePageWritesComesBeforeTheHostsHead() {
        val document = pageDocument("<!DOCTYPE html><!-- <head> --><script>steal()</script><html><body>头</body></html>", colors)
        assertTrue(document, document.startsWith("<!doctype html><html><head><meta charset"))
        assertTrue(document.indexOf("Content-Security-Policy") < document.indexOf("<!-- <head> -->"))
        assertTrue(document.indexOf("RTCPeerConnection") < document.indexOf("steal()"))
        assertTrue(!document.contains("<!DOCTYPE html>"))
    }

    @Test
    fun aFragmentFollowsTheHostsHead() {
        val fragment = pageDocument("<p>三款对比</p>", colors.copy(dark = true))
        assertTrue(fragment, fragment.endsWith("</head><p>三款对比</p>"))
        assertTrue(fragment.contains("color-scheme:dark"))
    }
}
