package cc.noharry.bothy.playground

/** What the car's navigation can search along its route, as AMap's along-route search knows them. */
enum class AlongCategory(val id: String, val label: String, val aliases: List<String>) {
    GAS("gas", "加油站", listOf("加油", "油站")),
    CHARGING("charging", "充电站", listOf("充电", "充电桩", "快充")),
    FOOD("food", "吃饭的地方", listOf("吃饭", "美食", "餐厅", "饭店", "饭馆", "晚饭", "午饭")),
    HOTEL("hotel", "酒店", listOf("住宿", "宾馆", "旅馆")),
    TOILET("toilet", "厕所", listOf("洗手间", "卫生间")),
    SERVICE_AREA("service_area", "服务区", listOf("高速服务区")),
    ATM("atm", "ATM", listOf("取钱", "取款机")),
    REPAIR("repair", "维修站", listOf("修车", "汽修", "维修")),
}

/** A calculated route, or why there is none. */
data class RouteResult(val problem: String? = null, val meters: Int = 0, val seconds: Int = 0, val tolls: Int = 0, val lights: Int = 0)

/**
 * The map behind the car's navigation: search, plan, drive (for real or simulated). AMap in the app
 * ([AMapNavEngine]); a fake in tests. Calls may block on the network or the planner, so they are made off the main
 * thread; live readings and arrivals go to the store as the engine gets them ([NavState.withProgress] and friends).
 */
interface NavEngine {
    /** Null when navigation can be used; otherwise why not, in words for whoever asked. */
    fun unavailable(): String?

    /** The car: where the navigation has it (the simulated position while simulating), else the device's location. */
    fun currentLocation(): GeoPoint?

    /** The city the car is in, as search wants it; empty when unknown. */
    fun currentCity(): String

    fun search(keyword: String, city: String, near: GeoPoint?, limit: Int): List<Place>

    /**
     * Places called [keyword] within [radiusMeters] of [center]: nearest first, or best matching first unless
     * [byDistance]; only of AMap's POI [types] (codes joined by |) when given.
     */
    fun searchNearby(keyword: String, center: GeoPoint, radiusMeters: Int, limit: Int, byDistance: Boolean = true, types: String = ""): List<Place>

    /** Places of [category] along the route now driven, nearest to it first. */
    fun searchAlongRoute(category: AlongCategory, limit: Int): List<Place>

    /** The route now driven, as points from the car onward; empty without one. */
    fun routeLine(): List<GeoPoint>

    /** Plans [destination] through [waypoints] from [start] (the car when null), stopping any drive; shown, not driven yet. */
    fun plan(start: GeoPoint?, destination: Place, waypoints: List<Place>, preference: RoutePreference): RouteResult

    /** Starts driving the route last planned, for real or [simulated]. */
    fun startDriving(simulated: Boolean)

    fun stop()
}

/** The live readings one navigation update brings. */
data class NavProgress(
    val remainingMeters: Int,
    val remainingSeconds: Int,
    val toWaypoints: List<ViaProgress>,
    val currentRoad: String,
    val nextRoad: String,
    val stepMeters: Int,
    val turnIcon: Int,
    val speedKmh: Int,
    /** Traffic lights still ahead on the route. */
    val lightsLeft: Int = 0,
)

/** A reading counts only while driving: one arriving late, after a stop for a new plan, must not undo it. */
internal fun NavState.withProgress(progress: NavProgress): NavState = if (!driving) this else copy(
    remainingMeters = progress.remainingMeters,
    remainingSeconds = progress.remainingSeconds,
    toWaypoints = progress.toWaypoints,
    currentRoad = progress.currentRoad,
    nextRoad = progress.nextRoad,
    stepMeters = progress.stepMeters,
    turnIcon = progress.turnIcon,
    speedKmh = progress.speedKmh,
    lightsLeft = progress.lightsLeft,
)

/** The first stop ahead reached: it leaves the list, so 「第 1 个途经点」 is always the next one. */
internal fun NavState.afterWaypointReached(): NavState =
    if (waypoints.isEmpty() || !driving) this else copy(waypoints = waypoints.drop(1), toWaypoints = toWaypoints.drop(1), reachedWaypoints = reachedWaypoints + waypoints.first().name)

internal fun NavState.afterArrival(): NavState =
    copy(status = NavStatus.ARRIVED, remainingMeters = 0, remainingSeconds = 0, toWaypoints = emptyList(), speedKmh = 0, stepMeters = 0)
