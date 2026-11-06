package cc.noharry.agent.appserver.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which tools are a service's: not the server's cards, plans or screen, nor a hand-off to the user's agent. */
class ServiceToolsTest {
    @Test
    fun aServicesToolIsAnyMcpToolButTheServersOwnAndHandOffs() {
        assertTrue(isServiceTool("mcp__amap__route_plan"))
        assertTrue(isServiceTool("mcp__memory__user_memory"))
        assertTrue(isServiceTool("mcp__tools_json__get_weather"))
        assertFalse(isServiceTool("mcp__app_ui__show_card"))
        assertFalse(isServiceTool("mcp__plans__plan_manage"))
        assertFalse(isServiceTool("mcp__android_use__android_action"))
        assertFalse(isServiceTool("mcp__experimental_a2a_grok__send"))
        assertFalse(isServiceTool("web_search"))
    }
}
