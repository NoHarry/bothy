package cc.noharry.bothy.playground

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The car's real navigation (AMap, through [NavController]): search, start with stops, change the stops while driving,
 * ask how far, search along the route, stop. These wait on the map, so they run outside the store's lock ([McpTool.direct]).
 */
internal fun navTools(nav: NavController): List<McpTool> = listOf(
    searchPlacesTool(nav), startNavigationTool(nav), addWaypointTool(nav), removeWaypointTool(nav), replaceWaypointTool(nav),
    reorderWaypointsTool(nav), changeDestinationTool(nav), routePreferenceTool(nav), getRouteTool(nav), searchAlongTool(nav),
    stopNavigationTool(nav),
)

/** A place: its name, plus the coordinates and poi_id from search_places when known (then they are used as given). */
private fun placeProperty(description: String): JsonObject = buildJsonObject {
    put("type", "object")
    put("description", description)
    put("properties", buildJsonObject {
        put("name", property("string", "名字，比如「深圳北站」「福田香格里拉酒店」「科技园」"))
        put("lat", property("number", "纬度（search_places 给的）"))
        put("lng", property("number", "经度（search_places 给的）"))
        put("poi_id", property("string", "search_places 给的 poi_id"))
    })
    put("required", JsonArray(listOf(JsonPrimitive("name"))))
}

private fun searchPlacesTool(nav: NavController) = McpTool(
    name = "search_places",
    title = "搜地点",
    description = "按名字或类别搜地点（高德），返回名称、地址、距离、评分、今天营业时间和坐标。默认在车所在的城市里搜，城市大时片区名" +
        "（「科技园」「香蜜湖」）和连锁店可能搜到别的区：这时用 near 在同一趟的另一个地点附近搜。只是规划路线的话不用先搜，" +
        "直接把名字交给 start_navigation，它会按同一趟其他地点附近找。",
    inputSchema = objectSchema(
        "keyword" to property("string", "要搜的：名字或类别，比如「素食餐厅」「宝安机场T3」「蛋糕店」"),
        "near" to placeProperty("在这个地点附近搜，比如同一趟里的「南山书城」"),
        "radius" to property("integer", "near 附近多大范围，米", min = 500, max = 50_000),
        "city" to property("string", "城市，比如「深圳」；不给就用车所在的城市"),
        "limit" to property("integer", "最多几个", min = 1, max = 10),
        required = listOf("keyword"),
    ),
    readOnly = true,
) { _, args ->
    nav.search(
        args.string("keyword") ?: throw ToolRefusal("给出 keyword"),
        args.string("city"),
        args.int("limit", 1..10) ?: 5,
        args.place("near"),
        args.int("radius", 500..50_000) ?: NavController.AREA_RADIUS_M,
    )
}

private fun startNavigationTool(nav: NavController) = McpTool(
    name = "start_navigation",
    title = "开始导航",
    description = "规划一条从车现在的位置出发、经过若干途经点到终点的路线并马上开始导航（最多 16 个途经点）。地点直接给名字就行：会在车所在的" +
        "城市里找，片区名（「科技园」「香蜜湖」）按同一趟其他地点附近找；只有要选具体某一家时才先 search_places 再传坐标。order 必填：as_given 按给的顺序、nearest 全部按顺路重排、" +
        "nearest_keep_first 第一个途经点不动其余顺路重排。用户定了先后（先接老大再接老四）用 as_given；没定先后的几个地方（打卡几个景点，" +
        "只说了先去哪、最后去哪）用 nearest_keep_first 或 nearest，导航按实际位置算出最短的顺序，不要自己凭印象排了再用 as_given。用户点名要去的地方都放进这条路线，不要自己删掉：哪处今天来不及、关门早，在回答里说明，问用户要不要去掉（remove_waypoint）。destination 是这一趟最后到的地方：接完人再去吃饭、过生日、住酒店，那家店才是终点，" +
        "接人、取东西的地方都放进 waypoints。返回全程距离、时间和预计到达时间。",
    inputSchema = objectSchema(
        "destination" to placeProperty("终点：名字，或 {name, lat, lng, poi_id}"),
        "waypoints" to buildJsonObject {
            put("type", "array")
            put("description", "途经点，按经过的顺序")
            put("items", placeProperty("途经点"))
        },
        "preference" to property("string", "路线偏好", RoutePreference.entries.map { it.id }),
        "order" to property("string", "途经点顺序：用户定了先后用 as_given，没定先后用 nearest_keep_first（首站不动）或 nearest", StopOrder.entries.map { it.id }),
        "start" to placeProperty("起点，不给就是车现在的位置"),
        "city" to property("string", "在哪个城市找这些地点，不给就是车所在的城市"),
        // Asked for every time: left out, a model ran an eight-sight tour in the order it guessed (2026-10-21, 97 km
        // where the shortest was 60).
        required = listOf("destination", "order"),
    ),
    direct = true,
) { _, args ->
    nav.start(
        args.place("destination") ?: throw ToolRefusal("给出 destination"),
        args.places("waypoints"),
        args.choice("preference", RoutePreference.entries) { listOf(it.id, it.label) + preferenceAliases(it) },
        args.choice("order", StopOrder.entries) { listOf(it.id) } ?: StopOrder.AS_GIVEN,
        args.place("start"),
        args.string("city"),
    )
}

private fun addWaypointTool(nav: NavController) = McpTool(
    name = "add_waypoint",
    title = "加途经点",
    description = "导航中加一个途经点，整条路线重新规划后继续导航。position 是放在第几站（1 是下一站），不给就放在终点前。" +
        "给终点用的东西（生日蛋糕、鲜花、礼物）最后取：不给 position，放在终点前，取到就送到。",
    inputSchema = objectSchema(
        "place" to placeProperty("地点：名字，或 {name, lat, lng, poi_id}"),
        "position" to property("integer", "第几站", min = 1, max = NavController.MAX_WAYPOINTS),
        required = listOf("place"),
    ),
    direct = true,
) { _, args -> nav.addWaypoint(args.place("place") ?: throw ToolRefusal("给出 place"), args.int("position", 1..NavController.MAX_WAYPOINTS)) }

private fun removeWaypointTool(nav: NavController) = McpTool(
    name = "remove_waypoint",
    title = "删途经点",
    description = "导航中去掉一个或几个途经点（按序号，1 是下一站；或按名字），重新规划后继续导航。",
    inputSchema = objectSchema(
        "index" to buildJsonObject {
            put("type", "array")
            put("description", "要去掉的序号（1 是下一站）")
            put("items", buildJsonObject { put("type", "integer") })
        },
        "name" to buildJsonObject {
            put("type", "array")
            put("description", "要去掉的途经点名字")
            put("items", buildJsonObject { put("type", "string") })
        },
    ),
    direct = true,
) { _, args -> nav.removeWaypoints(args.ints("index"), args.strings("name")) }

private fun replaceWaypointTool(nav: NavController) = McpTool(
    name = "replace_waypoint",
    title = "换途经点",
    description = "把某个途经点（按序号或名字）换成另一个地点，重新规划后继续导航。",
    inputSchema = objectSchema(
        "index" to property("integer", "要换的是第几站", min = 1, max = NavController.MAX_WAYPOINTS),
        "name" to property("string", "要换的途经点名字"),
        "place" to placeProperty("换成的地点：名字，或 {name, lat, lng, poi_id}"),
        required = listOf("place"),
    ),
    direct = true,
) { _, args -> nav.replaceWaypoint(args.int("index", 1..NavController.MAX_WAYPOINTS), args.string("name"), args.place("place") ?: throw ToolRefusal("给出 place")) }

private fun reorderWaypointsTool(nav: NavController) = McpTool(
    name = "reorder_waypoints",
    title = "调整途经点顺序",
    description = "按新的顺序经过现在这些途经点。order 是现在的序号排成的新顺序，比如 [2,1,3] 先去现在的第 2 站。",
    inputSchema = objectSchema(
        "order" to buildJsonObject {
            put("type", "array")
            put("description", "现在的序号排成的新顺序，每个序号各一次")
            put("items", buildJsonObject { put("type", "integer") })
        },
        required = listOf("order"),
    ),
    direct = true,
) { _, args -> nav.reorder(args.ints("order")) }

private fun changeDestinationTool(nav: NavController) = McpTool(
    name = "change_destination",
    title = "改终点",
    description = "导航中把终点换成另一个地点；默认保留途经点。",
    inputSchema = objectSchema(
        "place" to placeProperty("新的终点：名字，或 {name, lat, lng, poi_id}"),
        "keep_waypoints" to property("boolean", "是否保留现在的途经点，默认 true"),
        required = listOf("place"),
    ),
    direct = true,
) { _, args -> nav.changeDestination(args.place("place") ?: throw ToolRefusal("给出 place"), args.bool("keep_waypoints") ?: true) }

private fun routePreferenceTool(nav: NavController) = McpTool(
    name = "set_route_preference",
    title = "路线偏好",
    description = "导航中换路线偏好（推荐、躲避拥堵、不走高速、少收费、高速优先），重新规划后继续导航。",
    inputSchema = objectSchema(
        "preference" to property("string", "路线偏好", RoutePreference.entries.map { it.id }),
        required = listOf("preference"),
    ),
    direct = true,
) { _, args ->
    nav.setPreference(args.choice("preference", RoutePreference.entries) { listOf(it.id, it.label) + preferenceAliases(it) } ?: throw ToolRefusal("给出 preference"))
}

private fun getRouteTool(nav: NavController) = McpTool(
    name = "get_route",
    title = "查看路线",
    description = "当前导航：终点、还有多远多久、预计几点到，每个途经点还有多远多久，已经过了哪些。" +
        "要在到达时提醒或办事，计划监听资源 playground://cabin/state 的 navigation.arrived（一到就通知），不要定时调用本工具。",
    inputSchema = objectSchema(),
    readOnly = true,
) { _, _ -> nav.route() }

private fun searchAlongTool(nav: NavController) = McpTool(
    name = "search_along_route",
    title = "沿途搜",
    description = "沿当前导航路线找地方，离路线由近到远。category 用高德的沿途搜（${AlongCategory.entries.joinToString("、") { "${it.id} ${it.label}" }}）；" +
        "别的东西（蛋糕店、咖啡、超市）用 keyword，在路线前方分段搜。找到后用 add_waypoint 加进去。",
    inputSchema = objectSchema(
        "category" to property("string", "沿途搜的类别", AlongCategory.entries.map { it.id }),
        "keyword" to property("string", "别的要找的东西，比如「蛋糕店」"),
        "limit" to property("integer", "最多几个", min = 1, max = 10),
    ),
    readOnly = true,
) { _, args ->
    val category = args.choice("category", AlongCategory.entries) { listOf(it.id, it.label) + it.aliases }
    val keyword = args.string("keyword")
    // A keyword that names a category goes to the along-route search.
    val asCategory = category ?: keyword?.let { k -> AlongCategory.entries.firstOrNull { c -> (c.aliases + c.label).any { k.contains(it) } } }
    nav.along(asCategory, keyword, args.int("limit", 1..10) ?: 5)
}

private fun stopNavigationTool(nav: NavController) = McpTool(
    name = "stop_navigation",
    title = "退出导航",
    description = "退出当前导航。",
    inputSchema = objectSchema(),
    direct = true,
) { _, _ -> nav.stop() }

private fun preferenceAliases(preference: RoutePreference): List<String> = when (preference) {
    RoutePreference.RECOMMENDED -> listOf("默认", "智能推荐", "最快", "时间最短")
    RoutePreference.AVOID_CONGESTION -> listOf("避开拥堵", "不堵", "躲开拥堵")
    RoutePreference.NO_HIGHWAY -> listOf("不走高速", "避开高速", "走国道")
    RoutePreference.SAVE_MONEY -> listOf("少收费", "不收费", "省钱", "避免收费")
    RoutePreference.HIGHWAY_FIRST -> listOf("高速优先", "走高速")
}

/** A place argument: a name, or an object with name, lat, lng and poi_id (also latitude/longitude or "lat,lng"). */
internal fun ToolArgs.place(name: String): PlaceSpec? = raw(name)?.let(::placeSpec)

internal fun ToolArgs.places(name: String): List<PlaceSpec> = when (val value = raw(name)) {
    null -> emptyList()
    is JsonArray -> value.mapNotNull(::placeSpec)
    else -> listOfNotNull(placeSpec(value))
}

internal fun placeSpec(value: JsonElement): PlaceSpec? = when (value) {
    is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { PlaceSpec(it) }
    is JsonObject -> {
        fun text(vararg keys: String) = keys.firstNotNullOfOrNull { (value[it] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        fun number(vararg keys: String) = keys.firstNotNullOfOrNull { (value[it] as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() } }
        val pair = text("location", "coord", "coordinate")?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 2 }
        val lat = number("lat", "latitude") ?: pair?.get(0)
        val lng = number("lng", "lon", "longitude") ?: pair?.get(1)
        val point = if (lat != null && lng != null) GeoPoint(lat, lng) else null
        val label = text("name", "title", "keyword").orEmpty()
        if (label.isEmpty() && point == null) null else PlaceSpec(label, point, text("poi_id", "poiId", "id").orEmpty())
    }
    else -> null
}

private fun ToolArgs.ints(name: String): List<Int> = when (val value = raw(name)) {
    null -> emptyList()
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.let { p -> p.intOrNull ?: p.contentOrNull?.trim()?.toIntOrNull() } }
    is JsonPrimitive -> listOfNotNull(value.intOrNull ?: value.contentOrNull?.trim()?.toIntOrNull())
    else -> emptyList()
}

private fun ToolArgs.strings(name: String): List<String> = when (val value = raw(name)) {
    null -> emptyList()
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
    is JsonPrimitive -> listOfNotNull(value.contentOrNull?.trim()?.takeIf(String::isNotEmpty))
    else -> emptyList()
}
