package cc.noharry.bothy.playground

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The car's navigation without a map: names resolved, stops ordered, every change re-planned from the car. */
class NavControllerTest {
    private val engine = FakeNavEngine()
    private val store = PlaygroundStore(clock = { 0L })
    private val nav = NavController(store, engine, clock = { 0L }, later = { _, run -> run() })
    private val cabin = playgroundEndpoints(store, nav).getValue("/cabin/mcp")

    private fun call(tool: String, arguments: String): JsonObject =
        cabin.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""").jsonObject)!!
            .getValue("result").jsonObject

    private fun JsonObject.text() = getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content

    private fun JsonObject.isError() = getValue("isError").jsonPrimitive.content == "true"

    private val now get() = store.read().cabin.nav

    @Test
    fun aRouteOfNamedStopsIsResolvedOrderedAndDriven() {
        val result = call("start_navigation", """{"destination":{"name":"大梅沙"},"waypoints":[{"name":"莲花山"},{"name":"东门"},{"name":"世界之窗"}],"order":"nearest_keep_first"}""")
        assertFalse(result.text(), result.isError())
        // 莲花山 stays first; from there 世界之窗 (west) and 东门 (east, toward 大梅沙) in the order that ends near the destination.
        assertEquals(listOf("莲花山", "世界之窗", "东门"), engine.drives.last().waypoints.map { it.name })
        assertEquals(NavStatus.NAVIGATING, now.status)
        assertEquals("大梅沙", now.destination?.name)
        assertTrue(result.text(), result.text().startsWith("开始导航去「大梅沙」，途经 1.莲花山、2.世界之窗、3.东门。全程 40 公里"))
        assertEquals("start_navigation", store.calls.value.first().tool)
    }

    @Test
    fun eachPlaceGoesOnTheMapThenTheRouteShowsWholeAndOnlyTheLatestPlanStartsDriving() {
        val waiting = mutableListOf<() -> Unit>()
        val cues = mutableListOf<MapCue>()
        val nav = NavController(store, engine, clock = { 0L }, later = { _, run -> waiting += run }, focus = { cues += it })
        val cabin = playgroundEndpoints(store, nav).getValue("/cabin/mcp")
        fun call(tool: String, arguments: String) =
            cabin.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""").jsonObject)!!
        call("start_navigation", """{"destination":{"name":"海上世界"},"waypoints":[{"name":"南山书城"},{"name":"香蜜湖"}]}""")
        assertEquals(
            listOf("南山书城 false", "香蜜湖 true", "海上世界 true", "route"),
            cues.map { cue -> if (cue is MapCue.Found) "${cue.places.joinToString { it.name }} ${cue.adding}" else "route" },
        )
        assertEquals(NavStatus.PLANNED, now.status)
        assertEquals("unshown, it starts a while later all the same", NavController.PREVIEW_MS + NavController.UNSHOWN_GRACE_MS, now.startsAt)
        nav.routeShown()
        assertEquals(NavController.PREVIEW_MS, now.startsAt)
        call("add_waypoint", """{"place":{"name":"蛋糕店"}}""")
        waiting.dropLast(1).forEach { it() }
        assertEquals("a replaced plan does not start", NavStatus.PLANNED, now.status)
        waiting.last()()
        assertEquals(NavStatus.NAVIGATING, now.status)
        assertEquals(listOf("南山书城", "香蜜湖", "蛋糕店"), now.waypoints.map { it.name })
    }

    @Test
    fun nearbyMeansAroundTheCarNearestFirst() {
        val result = call("search_places", """{"keyword":"蛋糕店","around_car":true}""")
        assertFalse(result.text(), result.isError())
        assertTrue(result.text(), result.text().startsWith("在车附近搜到"))
        assertEquals(listOf("蛋糕店"), engine.nearbyAsked)
        // By name, across the city as before.
        call("search_places", """{"keyword":"南山书城"}""")
        assertEquals(listOf("蛋糕店"), engine.nearbyAsked)
    }

    @Test
    fun aRouteOnlyPlannedWaitsForTheWordToGoThroughChanges() {
        val waiting = mutableListOf<() -> Unit>()
        val nav = NavController(store, engine, clock = { 0L }, later = { _, run -> waiting += run })
        val cabin = playgroundEndpoints(store, nav).getValue("/cabin/mcp")
        fun call(tool: String, arguments: String) =
            cabin.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""").jsonObject)!!
                .getValue("result").jsonObject
        val planned = call("start_navigation", """{"destination":{"name":"海上世界"},"waypoints":[{"name":"南山书城"}],"order":"as_given","depart":false}""")
        assertTrue(planned.text(), planned.text().startsWith("规划好了去「海上世界」的路线") && "begin_navigation" in planned.text())
        assertTrue(now.previewing && now.waitsForGo)
        assertEquals("no count", 0L, now.startsAt)
        nav.routeShown()
        call("add_waypoint", """{"place":{"name":"蛋糕店"}}""")
        assertTrue("nothing scheduled to drive", waiting.isEmpty())
        assertTrue(now.waitsForGo && now.waypoints.map { it.name } == listOf("南山书城", "蛋糕店"))
        assertTrue(call("get_route", "{}").text().startsWith("路线已规划、还没出发"))
        val went = call("begin_navigation", "{}")
        assertTrue(went.text(), went.text().startsWith("开始导航去「海上世界」"))
        assertEquals(NavStatus.NAVIGATING, now.status)
        assertFalse(now.waitsForGo)
        assertTrue(call("begin_navigation", "{}").text().startsWith("已经在导航了"))
    }

    @Test
    fun withAMapShowingThePlacesPlanningWaitsUntilItShowedThem() {
        val shownBeforePlanning = mutableListOf<Int>()
        lateinit var nav: NavController
        val acknowledged = java.util.concurrent.atomic.AtomicInteger()
        nav = NavController(store, object : NavEngine by engine {
            override fun plan(start: GeoPoint?, destination: Place, waypoints: List<Place>, preference: RoutePreference): RouteResult {
                shownBeforePlanning += acknowledged.get()
                return engine.plan(start, destination, waypoints, preference)
            }
        }, clock = { 0L }, later = { _, _ -> }, focus = { cue ->
            // The map shows each place a moment later, on its own thread.
            if (cue is MapCue.Found) Thread { Thread.sleep(50); acknowledged.incrementAndGet(); nav.placesShown() }.start()
        }, mapWatching = { true })
        val cabin = playgroundEndpoints(store, nav).getValue("/cabin/mcp")
        cabin.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"start_navigation","arguments":{"destination":{"name":"海上世界"},"waypoints":[{"name":"南山书城"}]}}}""").jsonObject)
        assertEquals(listOf(2), shownBeforePlanning)
    }

    @Test
    fun aNameMeansThePlaceCalledThatNotACompanyOrABranchNamedAfterIt() {
        val park = GeoPoint(22.540, 113.950)
        fun at(name: String, lat: Double) = Place(name, GeoPoint(lat, 113.950))
        val found = listOf(
            at("深圳市掌阅科技有限公司(金地威新软件科技园南区店)", 22.540),
            at("某某科技有限公司科技园分部", 22.540),
            at("南山区科技园中区", 22.520),
            at("同安科技园", 22.700),
        )
        assertEquals("南山区科技园中区", bestMatch("科技园", found, park)?.name)
        assertEquals("海上世界", bestMatch("海上世界", listOf(at("蛇口海上世界品上酒店", 22.48), at("海上世界", 22.48)))?.name)
        // A way into a place is not the place: the park over its metro station and its ticket office.
        assertEquals("深圳世界之窗", bestMatch("世界之窗", listOf(at("世界之窗(地铁站)", 22.53), at("深圳世界之窗", 22.53)))?.name)
        assertEquals("深圳欢乐谷", bestMatch("欢乐谷", listOf(at("欢乐谷售票处", 22.54), at("深圳欢乐谷", 22.54)))?.name)
        assertEquals("南山书城(地铁站)", bestMatch("南山书城地铁站", listOf(at("南山书城(地铁站)", 22.52), at("南山书城", 22.52)))?.name)
    }

    @Test
    fun coordinatesAreTakenAsGivenAndAPlaceNobodyKnowsIsRefused() {
        call("start_navigation", """{"destination":{"name":"终点","lat":22.5,"lng":114.0},"waypoints":["深圳北站"]}""")
        assertEquals(GeoPoint(22.5, 114.0), engine.drives.last().destination.point)
        assertEquals("深圳北站", engine.drives.last().waypoints.single().name)
        val missing = call("start_navigation", """{"destination":{"name":"不存在的地方"}}""")
        assertTrue(missing.isError() && missing.text().contains("没找到「不存在的地方」"))
    }

    @Test
    fun theStopsChangeWhileDrivingAndEachChangeReplansFromTheCar() {
        call("start_navigation", """{"destination":{"name":"海上世界"},"waypoints":[{"name":"南山书城"},{"name":"科技园"},{"name":"香蜜湖"},{"name":"万象城"}]}""")
        call("remove_waypoint", """{"name":["科技园"]}""")
        assertEquals(listOf("南山书城", "香蜜湖", "万象城"), now.waypoints.map { it.name })
        call("remove_waypoint", """{"index":[3]}""")
        call("add_waypoint", """{"place":{"name":"蛋糕店"}}""")
        assertEquals(listOf("南山书城", "香蜜湖", "蛋糕店"), now.waypoints.map { it.name })
        call("replace_waypoint", """{"index":1,"place":{"name":"世界之窗"}}""")
        call("reorder_waypoints", """{"order":[2,1,3]}""")
        assertEquals(listOf("香蜜湖", "世界之窗", "蛋糕店"), now.waypoints.map { it.name })
        call("set_route_preference", """{"preference":"不走高速"}""")
        assertEquals(RoutePreference.NO_HIGHWAY, engine.drives.last().preference)
        // Every re-plan starts where the car is, not from the first start.
        assertTrue(engine.drives.drop(1).all { it.start == null })
        assertEquals(7, engine.drives.size)
        assertTrue(call("remove_waypoint", """{"index":[9]}""").isError())
    }

    @Test
    fun aReachedStopLeavesTheListAndArrivalIsTheStateAPlanWatches() {
        call("start_navigation", """{"destination":{"name":"海上世界"},"waypoints":[{"name":"南山书城"},{"name":"香蜜湖"}]}""")
        store.quietly { it.withNav(it.cabin.nav.withProgress(NavProgress(9_000, 1_200, listOf(ViaProgress(2_000, 300), ViaProgress(5_000, 700)), "滨海大道", "沙河西路", 300, 2, 66))) }
        assertTrue(call("get_route", "{}").text().contains("1.南山书城，还有 2 公里、约 5 分钟"))
        store.quietly { it.withNav(it.cabin.nav.afterWaypointReached()) }
        assertEquals(listOf("香蜜湖"), now.waypoints.map { it.name })
        assertEquals(listOf("南山书城"), now.reachedWaypoints)
        store.quietly { it.withNav(it.cabin.nav.afterArrival()) }
        val state = store.read().cabin.toJson(userHere = true).getValue("navigation").jsonObject
        assertEquals("true", state.getValue("arrived").jsonPrimitive.content)
        assertTrue(call("add_waypoint", """{"place":{"name":"蛋糕店"}}""").text().contains("现在没有在导航"))
    }

    @Test
    fun alongTheRouteACategoryGoesToTheAlongSearchAndAnythingElseNearPointsAhead() {
        call("start_navigation", """{"destination":{"name":"海上世界"}}""")
        val food = call("search_along_route", """{"keyword":"找个吃饭的地方"}""")
        assertEquals(AlongCategory.FOOD, engine.alongAsked.single())
        assertTrue(food.text(), food.text().contains("沿途的吃饭的地方"))
        val cake = call("search_along_route", """{"keyword":"蛋糕店"}""")
        assertTrue(cake.text(), cake.text().contains("1.") || cake.text().contains("1. 蛋糕店"))
        assertTrue(engine.nearbyAsked.isNotEmpty())
    }

    @Test
    fun withoutAMapTheToolsSayWhy() {
        val cabin = playgroundEndpoints(store).getValue("/cabin/mcp")
        val result = cabin.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"start_navigation","arguments":{"destination":{"name":"海上世界"}}}}""").jsonObject)!!
            .getValue("result").jsonObject
        assertTrue(result.isError())
        assertEquals("这个 Playground 没有导航", result.text())
    }
}

/** A map with a dozen Shenzhen places, straight-line routes and canned searches. */
private class FakeNavEngine : NavEngine {
    data class Drive(val start: GeoPoint?, val destination: Place, val waypoints: List<Place>, val preference: RoutePreference)

    val drives = mutableListOf<Drive>()
    val alongAsked = mutableListOf<AlongCategory>()
    val nearbyAsked = mutableListOf<String>()
    private val places = listOf(
        "大梅沙" to GeoPoint(22.596, 114.305), "莲花山" to GeoPoint(22.556, 114.058), "东门" to GeoPoint(22.547, 114.120),
        "世界之窗" to GeoPoint(22.535, 113.973), "深圳北站" to GeoPoint(22.610, 114.029), "海上世界" to GeoPoint(22.484, 113.913),
        "南山书城" to GeoPoint(22.518, 113.930), "科技园" to GeoPoint(22.540, 113.950), "香蜜湖" to GeoPoint(22.548, 114.020),
        "万象城" to GeoPoint(22.517, 113.950), "蛋糕店" to GeoPoint(22.500, 113.920),
    ).map { (name, point) -> Place(name, point, poiId = "id-$name") }

    override fun unavailable(): String? = null
    override fun currentLocation(): GeoPoint = GeoPoint(22.540, 114.000)
    override fun currentCity(): String = "深圳"
    override fun search(keyword: String, city: String, near: GeoPoint?, limit: Int): List<Place> = places.filter { keyword.contains(it.name) || it.name.contains(keyword) }.take(limit)
    override fun searchNearby(keyword: String, center: GeoPoint, radiusMeters: Int, limit: Int, byDistance: Boolean, types: String): List<Place> {
        nearbyAsked += keyword
        return places.filter { it.name.contains(keyword.removeSuffix("店")) }.take(limit)
    }
    override fun searchAlongRoute(category: AlongCategory, limit: Int): List<Place> {
        alongAsked += category
        return listOf(Place("顺路小馆", GeoPoint(22.5, 113.95), distanceMeters = 120))
    }
    override fun routeLine(): List<GeoPoint> = listOf(GeoPoint(22.540, 114.000), GeoPoint(22.520, 113.960), GeoPoint(22.500, 113.930))
    override fun plan(start: GeoPoint?, destination: Place, waypoints: List<Place>, preference: RoutePreference): RouteResult {
        drives += Drive(start, destination, waypoints, preference)
        return RouteResult(meters = 10_000 * (waypoints.size + 1), seconds = 900 * (waypoints.size + 1))
    }
    override fun startDriving(simulated: Boolean) = Unit
    override fun stop() = Unit
}
