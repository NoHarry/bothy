package cc.noharry.bothy.playground.nav
import cc.noharry.bothy.playground.Highlight
import cc.noharry.bothy.playground.PlaygroundStore
import cc.noharry.bothy.playground.ToolOutcome
import cc.noharry.bothy.playground.headunit.cabin
import cc.noharry.bothy.playground.headunit.on
import cc.noharry.bothy.playground.home.World
import cc.noharry.bothy.playground.home.line
import cc.noharry.bothy.playground.mcp.ToolRefusal
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Another App that drives a route in its own navigation (the 高德地图 App, [AmapApp]). While [on], routes planned here
 * go to it to drive instead of the head unit.
 */
interface AppNavigator {
    val name: String
    val on: Boolean

    /** Opens the App on the way to [end] through [stops]; says what it did, or refuses. */
    fun hand(end: Place, stops: List<Place>, preference: RoutePreference): String
}

/** How the stops of a new route are ordered. */
enum class StopOrder(val id: String) { AS_GIVEN("as_given"), NEAREST("nearest"), NEAREST_KEEP_FIRST("nearest_keep_first") }

/**
 * The car's navigation as the tools drive it: places are resolved by search (names) or taken as given (coordinates),
 * a route is planned and driven at once, and every change (a stop added, removed, replaced or moved, the preference)
 * plans the whole route again from where the car is and keeps driving. The state lands in [store]; the answers say what
 * the car now does, with distances and times, so the assistant can tell the user.
 */
class NavController(
    private val store: PlaygroundStore,
    private val engine: NavEngine,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Runs a task after a delay: the start of a planned route. Tests run it at once. */
    private val later: (Long, () -> Unit) -> Unit = ::runLater,
    /** What the map is to show, in order: each place as it is found, then the route planned ([MapCue]). */
    private val focus: (MapCue) -> Unit = {},
    /** Whether a map shows those one by one now, telling [placesShown] as it does; planning then waits for it. */
    private val mapWatching: () -> Boolean = { false },
    /** The App routes are handed to while it is [AppNavigator.on]; none, the head unit drives them all. */
    private val app: AppNavigator? = null,
) {
    private val nav: NavState get() = store.read().cabin.nav
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    /** Places sent to a map that shows them one by one ([mapWatching]) and not shown yet; planning waits for them. */
    private val unshown = java.util.concurrent.atomic.AtomicInteger()
    private val shownLock = Object()

    /** How long the route last planned is shown before driving: longer for a new trip than for a change. */
    @Volatile private var previewFor = PREVIEW_MS

    /** The route waiting in preview starts driving now (the 开始导航 button), unless a newer plan replaced it. */
    /**
     * The map now shows the planned route whole (it first moved to each place found): driving starts [previewFor] from
     * now. Without a map showing it, the route starts anyway a while later ([drive]).
     */
    fun routeShown() {
        val ticket = generation.get()
        if (!nav.previewing || nav.waitsForGo) return
        store.quietly { it.withNav(it.cabin.nav.copy(startsAt = clock() + previewFor)) }
        later(previewFor) { begin(ticket) }
    }

    /** The map has shown one more batch of places found ([MapCue.Found]), or, with [all], is done with them. */
    fun placesShown(all: Boolean = false) {
        if (all) unshown.set(0) else unshown.updateAndGet { (it - 1).coerceAtLeast(0) }
        synchronized(shownLock) { shownLock.notifyAll() }
    }

    private fun pin(places: List<Place>, adding: Boolean, ending: Boolean = false) {
        if (mapWatching()) unshown.incrementAndGet()
        focus(MapCue.Found(places, adding, ending))
    }

    /** A route drawn would pull the map away from the place it shows: planning waits until it showed them all (or a while). */
    private fun awaitPlacesShown() {
        val until = System.currentTimeMillis() + MAX_MAP_WAIT_MS
        synchronized(shownLock) {
            while (unshown.get() > 0) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) break
                shownLock.wait(left)
            }
        }
        unshown.set(0)
    }

    fun begin(expected: Int? = null) {
        if (expected != null && expected != generation.get()) return
        val planned = nav.takeIf { it.previewing } ?: return
        if (app?.on == true && runCatching { handOff(planned) }.isSuccess) return
        engine.startDriving(planned.simulated)
        store.quietly { it.withNav(it.cabin.nav.copy(status = NavStatus.NAVIGATING, startsAt = 0L, waitsForGo = false, guide = Guide())) }
        store.record("cabin", "navigation", "开始导航去「${planned.destination?.name}」", setOf(Highlight.NAV), fromAssistant = false)
    }

    /**
     * A new route through [waypoints] to [destination]. With [depart] it drives a moment after the map shows it; without
     * (owner 2026-10-22: the drive set off before the assistant had said the plan), it waits for [go].
     */
    fun start(
        destination: PlaceSpec,
        waypoints: List<PlaceSpec>,
        preference: RoutePreference?,
        order: StopOrder,
        start: PlaceSpec?,
        city: String?,
        depart: Boolean = true,
    ): ToolOutcome {
        ready()
        val area = city?.takeIf { it.isNotBlank() } ?: engine.currentCity()
        val from = start?.let { resolve(it, area, engine.currentLocation()) }
        val (end, ordered) = trip(destination, waypoints, order, area, from?.point ?: engine.currentLocation())
        val done = if (depart && app?.on != true) "开始导航去「${end.name}」" else "规划好了去「${end.name}」的路线"
        return drive(end, ordered, preference ?: nav.preference, from?.point, done, hold = !depart)
    }

    /**
     * Straight to the App's navigation (「用高德导航去…」, owner 2026-10-22): the places found as for [start], the head
     * unit's own route, if any, given up. The App plans from where the device is.
     */
    fun inApp(destination: PlaceSpec, waypoints: List<PlaceSpec>, preference: RoutePreference?, order: StopOrder, city: String?): ToolOutcome {
        ready()
        val navigator = app ?: throw ToolRefusal("这里不能交给其它导航 App")
        val area = city?.takeIf { it.isNotBlank() } ?: engine.currentCity()
        val (end, ordered) = trip(destination, waypoints, order, area, engine.currentLocation())
        val said = navigator.hand(end, ordered, preference ?: nav.preference)
        if (nav.active) stop()
        store.record("cabin", "navigation", said, setOf(Highlight.NAV), fromAssistant = false)
        return ToolOutcome(store.read(), "$said。${appDrives(navigator)}", setOf(Highlight.NAV))
    }

    /** The trip's places found together near [near], the stops in [order]: the destination and the stops. */
    private fun trip(destination: PlaceSpec, waypoints: List<PlaceSpec>, order: StopOrder, area: String, near: GeoPoint?): Pair<Place, List<Place>> {
        val resolved = resolveTogether(listOf(destination) + waypoints, area, near)
        val end = resolved.first()
        val stops = resolved.drop(1)
        return end to when (order) {
            StopOrder.AS_GIVEN -> stops
            StopOrder.NEAREST -> orderedByProximity(near ?: stops.firstOrNull()?.point ?: end.point, stops, end.point, keepFirst = false)
            StopOrder.NEAREST_KEEP_FIRST -> orderedByProximity(near ?: end.point, stops, end.point, keepFirst = true)
        }
    }

    /** The route planned goes to the App to drive ([app]); the head unit lets it go. What the App was told, or a refusal. */
    private fun handOff(planned: NavState): String {
        val said = app!!.hand(planned.destination!!, planned.waypoints, planned.preference)
        engine.stop()
        store.quietly { it.withNav(it.cabin.nav.copy(status = NavStatus.IDLE, startsAt = 0L, waitsForGo = false, speedKmh = 0, guide = Guide())) }
        store.record("cabin", "navigation", said, setOf(Highlight.NAV), fromAssistant = false)
        return said
    }

    /** The route waiting for the user's word ([start] without depart) drives now. */
    fun go(): ToolOutcome {
        ready()
        if (nav.driving) return ToolOutcome(store.read(), "已经在导航了。" + nav.describe(clock()))
        val planned = nav.takeIf { it.previewing } ?: throw ToolRefusal("现在没有等出发的路线，先用 start_navigation 规划")
        generation.incrementAndGet()
        if (app?.on == true) return ToolOutcome(store.read(), handOff(planned) + "。" + appDrives(app), setOf(Highlight.NAV))
        begin()
        return ToolOutcome(store.read(), "开始导航去「${planned.destination?.name}」。" + nav.describe(clock()), setOf(Highlight.NAV))
    }

    fun addWaypoint(spec: PlaceSpec, position: Int?): ToolOutcome {
        val now = active()
        val place = resolve(spec, engine.currentCity(), engine.currentLocation(), now.anchor())
        pin(listOf(place), adding = false)
        val stops = now.waypoints.withStopAt(place, position)
        return drive(now.destination!!, stops, now.preference, null, "加了途经点「${place.name}」（第 ${stops.indexOf(place) + 1} 站）")
    }

    fun removeWaypoints(indices: List<Int>, names: List<String>): ToolOutcome {
        val now = active()
        val gone = (indices.map { now.waypoints.stopIndex(it, null) } + names.map { now.waypoints.stopIndex(null, it) })
        if (gone.isEmpty() || gone.any { it == null }) throw ToolRefusal("没有这个途经点；现在的途经点：${now.waypoints.numbered()}")
        val dropped = gone.filterNotNull().toSet()
        val stops = now.waypoints.filterIndexed { i, _ -> i !in dropped }
        val names0 = dropped.sorted().joinToString("、") { "「${now.waypoints[it].name}」" }
        return drive(now.destination!!, stops, now.preference, null, "去掉了途经点$names0")
    }

    fun replaceWaypoint(index: Int?, name: String?, spec: PlaceSpec): ToolOutcome {
        val now = active()
        val at = now.waypoints.stopIndex(index, name) ?: throw ToolRefusal("没有这个途经点；现在的途经点：${now.waypoints.numbered()}")
        val place = resolve(spec, engine.currentCity(), engine.currentLocation(), now.anchor())
        pin(listOf(place), adding = false)
        val stops = now.waypoints.toMutableList().apply { set(at, place) }
        return drive(now.destination!!, stops, now.preference, null, "把第 ${at + 1} 站「${now.waypoints[at].name}」换成了「${place.name}」")
    }

    fun reorder(order: List<Int>): ToolOutcome {
        val now = active()
        if (order.sorted() != (1..now.waypoints.size).toList()) {
            throw ToolRefusal("order 要把 1 到 ${now.waypoints.size} 每个序号各写一次；现在的途经点：${now.waypoints.numbered()}")
        }
        return drive(now.destination!!, order.map { now.waypoints[it - 1] }, now.preference, null, "调整了途经点的顺序")
    }

    fun changeDestination(spec: PlaceSpec, keepWaypoints: Boolean): ToolOutcome {
        val now = active()
        val end = resolve(spec, engine.currentCity(), engine.currentLocation())
        pin(listOf(end), adding = false, ending = true)
        return drive(end, if (keepWaypoints) now.waypoints else emptyList(), now.preference, null, "终点改成了「${end.name}」")
    }

    fun setPreference(preference: RoutePreference): ToolOutcome {
        val now = active()
        return drive(now.destination!!, now.waypoints, preference, null, "路线改成${preference.label}")
    }

    /** The same route driven again, real or simulated, from where the car is. */
    fun setSimulated(simulated: Boolean): ToolOutcome {
        store.quietly { it.withNav(it.cabin.nav.copy(simulated = simulated)) }
        val now = nav.takeIf { it.active } ?: return ToolOutcome(store.read(), if (simulated) "下次导航模拟行驶" else "下次导航按真实定位")
        return drive(now.destination!!, now.waypoints, now.preference, null, if (simulated) "改成模拟行驶" else "改成按真实定位导航")
    }

    fun stop(): ToolOutcome {
        if (!nav.active) return ToolOutcome(store.read(), "现在没有在导航。")
        generation.incrementAndGet()
        engine.stop()
        store.quietly { it.withNav(it.cabin.nav.copy(status = NavStatus.IDLE, speedKmh = 0, guide = Guide())) }
        return ToolOutcome(store.read(), "导航已退出。", setOf(Highlight.NAV))
    }

    /**
     * Places called [keyword]: across [city], around [near] (another stop of the trip) within [radiusMeters], or, with
     * [aroundCar] (「附近的停车场」), around the car nearest first, within [radiusMeters] or [AROUND_CAR_M]. A search by
     * name across the city found 「附近」 places 25 km away once the car stood where the device really is (2026-10-22).
     */
    fun search(keyword: String, city: String?, limit: Int, near: PlaceSpec? = null, radiusMeters: Int? = null, aroundCar: Boolean = false): ToolOutcome {
        ready()
        val area = city?.takeIf { it.isNotBlank() } ?: engine.currentCity()
        val center = near?.let { resolve(it, area, engine.currentLocation()) }
        val car = engine.currentLocation().takeIf { aroundCar && center == null }
        val found = when {
            center != null -> engine.searchNearby(keyword, center.point, radiusMeters ?: AREA_RADIUS_M, limit)
            car != null -> engine.searchNearby(keyword, car, radiusMeters ?: AROUND_CAR_M, limit)
            else -> engine.search(keyword, area, engine.currentLocation(), limit)
        }
        val where = center?.let { "在「${it.name}」附近" } ?: car?.let { "在车附近" } ?: area.takeIf { it.isNotEmpty() }?.let { "在$it" }.orEmpty()
        if (found.isEmpty()) throw ToolRefusal("${where}没搜到「$keyword」，换个说法${if (center == null && car == null) "，或者用 near 在某个地点附近搜" else "或放大 radius"}")
        pin(found, adding = false)
        val listed = found.mapIndexed { i, p -> "${i + 1}. ${p.describe()}" }.joinToString("\n")
        return ToolOutcome(store.read(), "${where}搜到 ${found.size} 个「$keyword」${if (center != null) "（距离是到「${center.name}」的）" else ""}：\n$listed")
    }

    /** Places along the route now driven: a category through AMap's along-route search, anything else near points ahead. */
    fun along(category: AlongCategory?, keyword: String?, limit: Int): ToolOutcome {
        val now = active()
        val found = when {
            category != null -> engine.searchAlongRoute(category, limit)
            keyword != null -> nearRoute(keyword, limit)
            else -> throw ToolRefusal("给出 category 或 keyword")
        }
        val what = category?.label ?: keyword.orEmpty()
        if (found.isEmpty()) throw ToolRefusal("沿途没找到$what；可以换个说法，或者 search_places 搜终点「${now.destination?.name}」附近")
        pin(found, adding = false)
        val lines = found.mapIndexed { i, p -> "${i + 1}. ${p.describe()}" }.joinToString("\n")
        return ToolOutcome(store.read(), "沿途的$what（离路线由近到远）：\n$lines\n用 add_waypoint 把其中一家加进路线。")
    }

    fun route(): ToolOutcome = ToolOutcome(store.read(), nav.describe(clock()))

    /** Plans and shows the route; it drives after a count, or, with [hold] (a route still waiting for the word), waits on. */
    private fun drive(
        end: Place,
        stops: List<Place>,
        preference: RoutePreference,
        start: GeoPoint?,
        done: String,
        hold: Boolean = nav.previewing && nav.waitsForGo,
    ): ToolOutcome {
        if (stops.size > MAX_WAYPOINTS) throw ToolRefusal("途经点最多 $MAX_WAYPOINTS 个，现在是 ${stops.size} 个")
        val simulated = nav.simulated
        val fresh = !nav.active
        awaitPlacesShown()
        val result = engine.plan(start, end, stops, preference)
        result.problem?.let { throw ToolRefusal("算路失败：$it") }
        // Planned, the whole route shows on the map first; driving it starts a moment after the map shows it (sooner for a
        // change mid-drive), or, with no map to show it, a while later all the same.
        val wait = if (fresh) PREVIEW_MS else CHANGE_PREVIEW_MS
        previewFor = wait
        val ticket = generation.incrementAndGet()
        store.quietly { world ->
            world.withNav(
                NavState(
                    status = NavStatus.PLANNED, destination = end, waypoints = stops, preference = preference, simulated = simulated,
                    totalMeters = result.meters, totalSeconds = result.seconds, remainingMeters = result.meters, remainingSeconds = result.seconds,
                    startsAt = if (hold) 0L else clock() + wait + UNSHOWN_GRACE_MS, waitsForGo = hold,
                ),
            )
        }
        if (!hold) later(wait + UNSHOWN_GRACE_MS) { begin(ticket) }
        focus(MapCue.Route)
        val via = if (stops.isEmpty()) "" else "，途经 ${stops.numbered()}"
        val text = "$done$via。全程 ${result.meters.km()}，约 ${result.seconds.duration()}，预计 ${eta(result.seconds)} 到" +
            (if (result.tolls > 0) "，过路费约 ${result.tolls} 元" else "") + (if (simulated) "（模拟行驶）" else "") +
            when {
                hold -> "。路线已显示在屏幕上，还没出发：用户说出发再用 begin_navigation 开始导航" + (if (app?.on == true) "（会交给${app.name}）。" else "。")
                app?.on == true -> "。路线已显示在屏幕上，${wait / 1000} 秒后交给${app.name}导航。${appDrives(app)}"
                else -> "。路线已显示在屏幕上，${wait / 1000} 秒后开始导航。"
            }
        return ToolOutcome(store.read(), text, setOf(Highlight.NAV))
    }

    private fun resolve(spec: PlaceSpec, city: String, near: GeoPoint?, anchor: GeoPoint? = null): Place {
        val found = candidates(spec, city, near)
        return bestMatch(spec.name, found + nearAnchor(spec, found, anchor), anchor) ?: missing(spec, city)
    }

    /**
     * A name no result is called or starts with (an area such as 「科技园」) is looked for again around [anchor], the
     * trip's other stops: across the whole city the first results can all lie in another district.
     */
    private fun nearAnchor(spec: PlaceSpec, found: List<Place>, anchor: GeoPoint?): List<Place> {
        if (anchor == null || spec.point != null || !isVague(spec.name, found)) return emptyList()
        // Around the trip, among the kinds of place an area's name means (parks, place names, stations): a plain search
        // there matches loosely and lists offices with 「科技」 in their names, while the city's ranking around the car
        // finds only the car's district (2026-10-21: 同安科技园 in 宝安 won for want of a 科技园 in 南山).
        return engine.searchNearby(spec.name, anchor, AREA_RADIUS_M, AREA_CANDIDATES, byDistance = false, types = AREA_TYPES)
    }

    /**
     * The stops of one trip, named together: a name several places answer (「科技园」) becomes the one nearest the
     * trip's other stops, not the first the search lists (a 科技园 in another district).
     */
    private fun resolveTogether(specs: List<PlaceSpec>, city: String, near: GeoPoint?): List<Place> {
        val found = specs.map { candidates(it, city, near) }
        val first = specs.mapIndexed { i, spec -> bestMatch(spec.name, found[i], null) ?: missing(spec, city) }
        // Each stop goes on the map as it is settled, the map moving to it: the stops in turn, the destination last.
        val settled = arrayOfNulls<Place>(specs.size)
        (specs.indices.drop(1) + 0).forEachIndexed { turn, i ->
            val anchor = centroid(first.filterIndexed { j, _ -> j != i }.map { it.point })
            val place = bestMatch(specs[i].name, found[i] + nearAnchor(specs[i], found[i], anchor), anchor) ?: first[i]
            settled[i] = place
            pin(listOf(place), adding = turn > 0, ending = i == 0)
        }
        return settled.map { it!! }
    }

    private fun candidates(spec: PlaceSpec, city: String, near: GeoPoint?): List<Place> {
        spec.point?.let { return listOf(Place(spec.name.ifBlank { "指定地点" }, it, spec.poiId)) }
        if (spec.name.isBlank()) throw ToolRefusal("地点要给名字或坐标")
        return engine.search(spec.name, city, near, RESOLVE_CANDIDATES)
    }

    private fun missing(spec: PlaceSpec, city: String): Nothing =
        throw ToolRefusal("没找到「${spec.name}」${if (city.isNotEmpty()) "（在$city）" else ""}；换个更具体的名字，或先 search_places 再用坐标")

    private fun nearRoute(keyword: String, limit: Int): List<Place> {
        val line = engine.routeLine()
        val car = engine.currentLocation()
        if (line.isEmpty()) return car?.let { engine.searchNearby(keyword, it, NEARBY_RADIUS_M, limit) }.orEmpty()
        val samples = line.sampledEvery(SAMPLE_EVERY_M).take(MAX_SAMPLES)
        val seen = LinkedHashMap<String, Place>()
        samples.forEach { point ->
            engine.searchNearby(keyword, point, ROUTE_RADIUS_M, PER_SAMPLE).forEach { place ->
                val key = place.poiId.ifEmpty { place.name }
                if (key !in seen) seen[key] = place.copy(distanceMeters = line.offsetOf(place.point).roundToInt())
            }
        }
        return seen.values.sortedBy { it.distanceMeters }.take(limit)
    }

    private fun ready() {
        engine.unavailable()?.let { throw ToolRefusal(it) }
    }

    private fun active(): NavState {
        ready()
        return nav.takeIf { it.active && it.destination != null }
            ?: throw ToolRefusal(if (app?.on == true) "导航交给了${app.name}，途经点、终点要在${app.name}里改" else "现在没有在导航，先用 start_navigation 开始导航")
    }

    /** What the assistant is to know once [app] drives: the head unit no longer sees the trip. */
    private fun appDrives(app: AppNavigator): String = "之后的导航在${app.name}里：到达提醒、改途经点都在${app.name}里做，车机这边看不到进度"

    private fun eta(seconds: Int): String = clockText(clock() + seconds * 1000L, clock())

    companion object {
        const val MAX_WAYPOINTS = 16
        const val PREVIEW_MS = 5_000L
        const val CHANGE_PREVIEW_MS = 2_000L
        /** Extra wait for the map to get through the places found before it shows the route ([routeShown]). */
        const val UNSHOWN_GRACE_MS = 15_000L
        /** The longest planning waits for the map to show the places found. */
        private const val MAX_MAP_WAIT_MS = 12_000L
        /** Results weighed when a stop is given by name: the first is often a shop near it, not the place itself. */
        private const val RESOLVE_CANDIDATES = 6
        /** How far around the trip an area name is looked for, and how many places there are weighed. */
        const val AREA_RADIUS_M = 12_000
        /** How far 「附近」 reaches around the car unless asked otherwise. */
        const val AROUND_CAR_M = 5_000
        private const val AREA_CANDIDATES = 10
        /** AMap's POI types an area's name stands for: 产业园区, 地名地址信息, 地铁站. */
        private const val AREA_TYPES = "120100|190000|150500"
        private const val SAMPLE_EVERY_M = 3_000.0
        private const val MAX_SAMPLES = 8
        private const val PER_SAMPLE = 4
        private const val ROUTE_RADIUS_M = 1_500
        private const val NEARBY_RADIUS_M = 3_000
    }
}

/**
 * The result a name means: one called exactly that, else one whose name starts with it (「海上世界」 over a hotel
 * 「…海上世界品上酒店」), else a place with it in its own name (「南山区科技园中区」), else one with it anywhere (a company,
 * or a shop whose branch in brackets is in a 科技园, or a station, ticket office or exit of it), else the search's first. Among equally good names the search's
 * order stands, except for a name only contained ([isVague]): then the one nearest [anchor] (the rest of the trip).
 */
internal fun bestMatch(name: String, found: List<Place>, anchor: GeoPoint? = null): Place? {
    val best = found.minOfOrNull { matchTier(name, it) } ?: return null
    val tied = found.filter { matchTier(name, it) == best }
    return if (anchor == null || best <= STARTS_WITH) tied.first() else tied.minBy { it.point.metersTo(anchor) }
}

/** No result is called [name] or starts with it (「科技园」): any of several places may be meant. */
internal fun isVague(name: String, found: List<Place>): Boolean = found.none { matchTier(name, it) <= STARTS_WITH }

private fun matchTier(name: String, place: Place): Int {
    val said = name.trim()
    val own = place.name.substringBefore('(').substringBefore('（')
    val tier = when {
        place.name == said -> 0
        own.startsWith(said) -> STARTS_WITH
        own.contains(said) && "公司" !in own -> 2
        place.name.contains(said) -> 3
        else -> 4
    }
    // A way in or near (「世界之窗(地铁站)」, 「欢乐谷售票处」, 「…出口」) is not the place: after any that is, unless asked for.
    return if (tier > 0 && FACILITY.containsMatchIn(place.name) && !FACILITY.containsMatchIn(said)) maxOf(tier, 3) else tier
}

private val FACILITY = Regex("地铁站|公交站|停车场|售票处|出入口|入口|出口|[A-Z]口")

private const val STARTS_WITH = 1

internal fun centroid(points: List<GeoPoint>): GeoPoint? =
    if (points.isEmpty()) null else GeoPoint(points.map { it.lat }.average(), points.map { it.lng }.average())

/** Where the trip now driven is: the middle of its stops and destination. */
internal fun NavState.anchor(): GeoPoint? = centroid(waypoints.map { it.point } + listOfNotNull(destination?.point))

private val starter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "nav-start").apply { isDaemon = true } }

private fun runLater(delayMs: Long, task: () -> Unit) {
    starter.schedule({ runCatching(task) }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
}

internal fun World.withNav(nav: NavState): World = copy(cabin = cabin.copy(nav = nav))

internal fun List<Place>.numbered(): String = if (isEmpty()) "没有" else mapIndexed { i, p -> "${i + 1}.${p.name}" }.joinToString("、")

internal fun Place.describe(): String = buildString {
    append(name)
    if (address.isNotEmpty()) append("（$address）")
    if (distanceMeters >= 0) append("，距离 ${distanceMeters.km()}")
    if (rating.isNotEmpty()) append("，评分 $rating")
    if (openToday.isNotEmpty()) append("，今天 $openToday")
    append("，坐标 ${"%.6f".format(java.util.Locale.ROOT, point.lat)},${"%.6f".format(java.util.Locale.ROOT, point.lng)}")
    if (poiId.isNotEmpty()) append("，poi_id $poiId")
}

/** The route state in words: where to, what is left, each stop ahead with its distance and time. */
internal fun NavState.describe(now: Long): String = buildString {
    if (!active) {
        append(if (status == NavStatus.ARRIVED) "已经到达「${destination?.name}」。" else "现在没有在导航。")
        return@buildString
    }
    val heading = when {
        waitsForGo -> "路线已规划、还没出发（用户说出发再用 begin_navigation）：去"
        previewing -> "路线已规划、马上出发：去"
        else -> "正在导航去"
    }
    append("$heading「${destination?.name}」（${preference.label}${if (simulated) "，模拟行驶" else ""}）：")
    append("还有 ${remainingMeters.km()}，约 ${remainingSeconds.duration()}，预计 ${clockText(now + remainingSeconds * 1000L, now)} 到。")
    if (waypoints.isNotEmpty()) {
        append("途经点：")
        append(waypoints.mapIndexed { i, p ->
            val left = toWaypoints.getOrNull(i)?.let { "，还有 ${it.meters.km()}、约 ${it.seconds.duration()}" }.orEmpty()
            "${i + 1}.${p.name}$left"
        }.joinToString("；"))
        append("。")
    }
    if (reachedWaypoints.isNotEmpty()) append("已经过：${reachedWaypoints.joinToString("、")}。")
    if (currentRoad.isNotEmpty()) append("当前在$currentRoad，")
    append("车速 $speedKmh km/h。")
    if (driving && lightsLeft > 0) append("前方还有 $lightsLeft 个红绿灯。")
}

internal fun Int.km(): String = if (this < 1000) "$this 米" else "${(this / 100) / 10.0} 公里".replace(".0 ", " ")

internal fun Int.duration(): String {
    val minutes = (this + 59) / 60
    return if (minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时${if (minutes % 60 == 0) "" else " ${minutes % 60} 分"}"
}

/** 「21:34」 today, 「明天 07:05」 after midnight. */
internal fun clockText(at: Long, now: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR)
    val then = Calendar.getInstance().apply { timeInMillis = at }
    val text = "%02d:%02d".format(then.get(Calendar.HOUR_OF_DAY), then.get(Calendar.MINUTE))
    return if (then.get(Calendar.DAY_OF_YEAR) != day) "明天 $text" else text
}

/** Points along this line every [meters] (the first included). */
internal fun List<GeoPoint>.sampledEvery(meters: Double): List<GeoPoint> {
    if (isEmpty()) return emptyList()
    val out = mutableListOf(first())
    var since = 0.0
    zipWithNext().forEach { (a, b) ->
        since += a.metersTo(b)
        if (since >= meters) {
            out += b
            since = 0.0
        }
    }
    return out
}

/** How far [point] is from this line's nearest vertex, metres: a fair guide on a densely drawn route. */
internal fun List<GeoPoint>.offsetOf(point: GeoPoint): Double = minOfOrNull { it.metersTo(point) } ?: Double.MAX_VALUE
