package cc.noharry.bothy.playground

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.NaviSetting
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviPoi
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItemV2
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.poisearch.PoiSearchV2
import com.amap.api.services.routepoisearch.RoutePOISearch
import com.amap.api.services.routepoisearch.RoutePOISearchQuery
import com.amap.api.services.weather.LocalWeatherForecastResult
import com.amap.api.services.weather.LocalWeatherLiveResult
import com.amap.api.services.weather.WeatherSearch
import com.amap.api.services.weather.WeatherSearchQuery
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The car's navigation on AMap's navigation SDK: search (POI search 2.0 and the along-route search), route planning
 * with up to 16 stops, and driving it for real (GPS) or simulated (the SDK's emulator), with every reading and arrival
 * written to [store]. Nothing runs before the user agrees to AMap's privacy terms ([AMapPrivacy]) and a key is built in.
 */
class AMapNavEngine(context: Context, private val store: PlaygroundStore, private val hasKey: Boolean) : NavEngine {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var navi: AMapNavi? = null
    private var locator: AMapLocationClient? = null
    @Volatile private var deviceLocation: GeoPoint? = null
    @Volatile private var city: String = ""
    @Volatile private var carLocation: GeoPoint? = null
    /** Where a simulated drive ended: the car stays there, not back where the device (or the demo start) is. */
    @Volatile private var parked: GeoPoint? = null
    private val pending = AtomicReference<CountDownLatch?>()
    private val calculated = AtomicReference<AMapCalcRouteResult?>()
    private val rate = Any()
    private val driving = Any()
    @Volatile private var cachedWeather: WeatherReport? = null
    private var lastRequestAt = 0L
    private val searches = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<PoiItemV2>>>()

    override fun unavailable(): String? = when {
        !hasKey -> "Playground 没有配置高德 key：在 local.properties 写上 AMAP_ANDROID_KEY 后重新打包"
        !AMapPrivacy.agreed(app) -> "Playground 里还没同意高德地图的隐私协议：在 Playground 里点「同意」后再试"
        ensureStarted() == null -> "高德导航没能启动，看 Playground 的日志"
        else -> null
    }

    /** Starts the SDK once (after consent): the navigator with its listener, and the device's location. */
    fun ensureStarted(): AMapNavi? {
        navi?.let { return it }
        if (!hasKey || !AMapPrivacy.agreed(app)) return null
        return onMain {
            navi ?: runCatching {
                AMapPrivacy.applyTo(app)
                AMapNavi.getInstance(app).also { amap ->
                    amap.addAMapNaviListener(listener)
                    amap.setUseInnerVoice(true)
                    amap.addTTSPlayListener(GuidanceFocus(app, main))
                    navi = amap
                    startLocating()
                }
            }.getOrNull()
        }
    }

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Whether the car stands at [DEMO_START] rather than where the device is: a demo in the demo's city, a screenshot
     * that must not show where the device really is. Off by default.
     */
    var demoStart: Boolean
        get() = prefs.getBoolean(DEMO_START_KEY, false)
        set(value) {
            prefs.edit().putBoolean(DEMO_START_KEY, value).apply()
            cachedWeather = null
            parked = null
        }

    /** Real navigation ([HeadUnitModes]): the car is where the device is, whatever the demo start and a simulated drive say. */
    @Volatile var realNav: Boolean = false
        set(value) {
            field = value
            cachedWeather = null
        }

    override fun currentLocation(): GeoPoint? = carLocation.takeIf { store.read().cabin.nav.active }
        ?: if (realNav) deviceLocation else parked ?: if (demoStart) DEMO_START else deviceLocation

    override fun currentCity(): String = if (demoStart && !realNav) DEMO_CITY else city

    override fun search(keyword: String, city: String, near: GeoPoint?, limit: Int): List<Place> {
        // By name across the city: biased to the car, 「南山书城」 came back as the bookshops near it instead. The car only
        // measures how far each result is.
        // Just started, the city may not be known yet: asked of AMap from where the device is.
        val area = currentCity().ifEmpty { cityFromLocation() }
        val query = PoiSearchV2.Query(keyword, "", area).apply {
            pageSize = limit
            pageNum = 1
            cityLimit = area.isNotEmpty()
            showFields = PoiSearchV2.ShowFields(PoiSearchV2.ShowFields.BUSINESS)
        }
        return poiSearch(query, null).map { it.toPlace(near) }
    }

    override fun searchNearby(keyword: String, center: GeoPoint, radiusMeters: Int, limit: Int, byDistance: Boolean, types: String): List<Place> {
        val query = PoiSearchV2.Query(keyword, types, "").apply {
            pageSize = limit
            pageNum = 1
            isDistanceSort = byDistance
            showFields = PoiSearchV2.ShowFields(PoiSearchV2.ShowFields.BUSINESS)
        }
        return poiSearch(query, PoiSearchV2.SearchBound(LatLonPoint(center.lat, center.lng), radiusMeters, byDistance)).map { it.toPlace(center) }
    }

    override fun searchAlongRoute(category: AlongCategory, limit: Int): List<Place> {
        val line = routeLine().sampledEvery(ALONG_SAMPLE_M).take(ALONG_MAX_POINTS).map { LatLonPoint(it.lat, it.lng) }
        if (line.size < 2) return emptyList()
        val result = throttled { RoutePOISearch(app, RoutePOISearchQuery(line, category.amapType(), ALONG_RANGE_M)).searchRoutePOI() }
        return result.routePois.orEmpty().sortedBy { it.distance }.take(limit).map { item ->
            Place(item.title, GeoPoint(item.point.latitude, item.point.longitude), item.id.orEmpty(), distanceMeters = item.distance.toInt())
        }
    }

    override fun routeLine(): List<GeoPoint> =
        navi?.naviPath?.coordList.orEmpty().map { GeoPoint(it.latitude, it.longitude) }.takeIf { store.read().cabin.nav.active }.orEmpty()

    override fun plan(start: GeoPoint?, destination: Place, waypoints: List<Place>, preference: RoutePreference): RouteResult {
        val amap = ensureStarted() ?: return RouteResult(unavailable())
        val from = (start ?: currentLocation())?.let { NaviPoi("当前位置", LatLng(it.lat, it.lng), "") }
            ?: return RouteResult("还不知道车在哪：等定位好了再试，或者给出起点 start")
        // One route at a time: two plans in flight at once (a model that sends two changes together) crashed AMap's
        // native navigator, and so did planning the instant a simulated drive was stopped.
        synchronized(driving) {
            if (store.read().cabin.nav.driving) {
                onMain { amap.stopNavi() }
                Thread.sleep(STOP_SETTLE_MS)
            }
            return calculate(amap, from, destination, waypoints, preference)
        }
    }

    private fun calculate(amap: AMapNavi, from: NaviPoi, destination: Place, waypoints: List<Place>, preference: RoutePreference): RouteResult {
        val latch = CountDownLatch(1)
        pending.set(latch)
        calculated.set(null)
        val asked = onMain {
            amap.calculateDriveRoute(from, destination.toNaviPoi(), waypoints.map { it.toNaviPoi() }, preference.strategy(amap))
        }
        if (asked != true) return RouteResult("高德没有接受这次算路")
        if (!latch.await(ROUTE_WAIT_S, TimeUnit.SECONDS)) return RouteResult("算路超时（${ROUTE_WAIT_S} 秒）")
        val outcome = calculated.get() ?: return RouteResult("算路失败")
        if (outcome.errorCode != 0) return RouteResult("${outcome.errorDescription.orEmpty()}（${outcome.errorCode}）")
        onMain { outcome.routeid?.firstOrNull()?.let { amap.selectRouteId(it) } }
        val path = amap.naviPath ?: return RouteResult("算路成功但没有拿到路线")
        return RouteResult(meters = path.allLength, seconds = path.allTime, tolls = path.tollCost, lights = path.trafficLightCount)
    }

    override fun startDriving(simulated: Boolean) {
        val amap = ensureStarted() ?: return
        synchronized(driving) {
            // Driven for real, the car is where the device is again.
            if (!simulated) parked = null
            onMain {
                if (simulated) amap.setEmulatorNaviSpeed(SIMULATED_KMH)
                amap.startNavi(if (simulated) NaviType.EMULATOR else NaviType.GPS)
            }
        }
    }

    override fun stop() {
        onMain { navi?.stopNavi() }
    }

    /** The weather where the car is: now and the next days, from AMap; cached, null until the city is known. */
    fun weather(): WeatherReport? {
        val place = currentCity().ifEmpty { cityFromLocation() }.ifEmpty { return null }
        cachedWeather?.takeIf { it.city == place && System.currentTimeMillis() - it.at < WEATHER_CACHE_MS }?.let { return it }
        val live = weatherSearch(place, WeatherSearchQuery.WEATHER_TYPE_LIVE)?.first?.liveResult ?: return null
        val days = weatherSearch(place, WeatherSearchQuery.WEATHER_TYPE_FORECAST)?.second?.forecastResult?.weatherForecast.orEmpty()
        return WeatherReport(
            city = place,
            weather = live.weather.orEmpty(),
            temperature = live.temperature.orEmpty(),
            humidity = live.humidity.orEmpty(),
            wind = "${live.windDirection.orEmpty()}风 ${live.windPower.orEmpty()}级",
            days = days.map { DayWeather(it.week.orEmpty(), it.dayWeather.orEmpty(), it.dayTemp.orEmpty(), it.nightTemp.orEmpty()) },
        ).also { cachedWeather = it }
    }

    /** The city around the device, asked of AMap when the fix came without an address (a GPS or cached one does). */
    private fun cityFromLocation(): String {
        val at = deviceLocation ?: return ""
        val address = runCatching {
            throttled { GeocodeSearch(app).getFromLocation(RegeocodeQuery(LatLonPoint(at.lat, at.lng), REGEOCODE_RADIUS_M, GeocodeSearch.AMAP)) }
        }.getOrNull() ?: return ""
        return address.city.orEmpty().ifBlank { address.province.orEmpty() }.also { if (it.isNotEmpty()) city = it }
    }

    private fun weatherSearch(place: String, type: Int): Pair<LocalWeatherLiveResult?, LocalWeatherForecastResult?>? = throttled {
        val done = CountDownLatch(1)
        val result = AtomicReference<Pair<LocalWeatherLiveResult?, LocalWeatherForecastResult?>?>()
        val search = WeatherSearch(app)
        search.setOnWeatherSearchListener(object : WeatherSearch.OnWeatherSearchListener {
            override fun onWeatherLiveSearched(live: LocalWeatherLiveResult?, code: Int) {
                if (code == SEARCH_OK) result.set(live to null)
                done.countDown()
            }

            override fun onWeatherForecastSearched(forecast: LocalWeatherForecastResult?, code: Int) {
                if (code == SEARCH_OK) result.set(null to forecast)
                done.countDown()
            }
        })
        search.query = WeatherSearchQuery(place, type)
        search.searchWeatherAsyn()
        done.await(WEATHER_WAIT_S, TimeUnit.SECONDS)
        result.get()
    }

    /** Voice guidance on or off (the head unit's mute button). */
    fun setMuted(muted: Boolean) {
        onMain { navi?.let { if (muted) it.stopSpeak() else it.startSpeak() } }
    }

    private val listener = object : SimpleNaviListener() {
        override fun onCalculateRouteSuccess(result: AMapCalcRouteResult) = finished(result)

        override fun onCalculateRouteFailure(result: AMapCalcRouteResult) = finished(result)

        override fun onNaviInfoUpdate(info: NaviInfo) {
            store.quietly { it.withNav(it.cabin.nav.withProgress(info.toProgress())) }
        }

        override fun onLocationChange(location: AMapNaviLocation) {
            location.coord?.let { carLocation = GeoPoint(it.latitude, it.longitude) }
        }

        override fun onArrivedWayPoint(index: Int) {
            if (!store.read().cabin.nav.driving) return
            val reached = store.read().cabin.nav.waypoints.firstOrNull()?.name ?: return
            store.quietly { it.withNav(it.cabin.nav.afterWaypointReached()) }
            store.record("cabin", "navigation", "到达途经点「$reached」", setOf(Highlight.NAV), fromAssistant = false)
        }

        override fun onArriveDestination() = arrived()

        override fun onEndEmulatorNavi() = arrived()
    }

    private fun finished(result: AMapCalcRouteResult) {
        calculated.set(result)
        pending.getAndSet(null)?.countDown()
    }

    private fun arrived() {
        val nav = store.read().cabin.nav
        // Stopping a drive to plan anew ends the simulation too; only a drive under way arrives.
        if (!nav.driving) return
        if (nav.simulated) parked = carLocation ?: nav.destination?.point
        store.quietly { it.withNav(it.cabin.nav.afterArrival()) }
        store.record("cabin", "navigation", "已到达「${nav.destination?.name}」", setOf(Highlight.NAV), fromAssistant = false)
    }

    private fun startLocating() {
        val client = runCatching { AMapLocationClient(app) }.getOrNull() ?: return
        client.setLocationOption(AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            interval = LOCATION_EVERY_MS
        })
        client.setLocationListener { found ->
            if (found != null && found.errorCode == 0) {
                deviceLocation = GeoPoint(found.latitude, found.longitude)
                found.city?.takeIf { it.isNotBlank() }?.let { city = it }
            }
        }
        client.startLocation()
        locator = client
    }

    private fun poiSearch(query: PoiSearchV2.Query, bound: PoiSearchV2.SearchBound?): List<PoiItemV2> {
        val key = "${query.queryString}|${query.category}|${query.city}|${query.location}|${bound?.center}|${bound?.range}|${bound?.isDistanceSort}|${query.pageSize}"
        searches[key]?.takeIf { (at, _) -> System.currentTimeMillis() - at < SEARCH_CACHE_MS }?.let { return it.second }
        val found = throttled {
            val search = PoiSearchV2(app, query)
            bound?.let { search.bound = it }
            search.searchPOI()?.pois.orEmpty()
        }
        searches[key] = System.currentTimeMillis() to found
        return found
    }

    /**
     * [request] spaced from the last one and tried again when AMap answers that the key's per-second limit is used up
     * (a personal key allows a few a second; resolving five stops by name asks five times in a row).
     */
    private fun <T> throttled(request: () -> T): T = synchronized(rate) {
        var attempt = 0
        while (true) {
            val wait = lastRequestAt + MIN_REQUEST_GAP_MS - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastRequestAt = System.currentTimeMillis()
            try {
                return@synchronized request()
            } catch (busy: AMapException) {
                val limited = busy.errorMessage.orEmpty().contains("QPS") || busy.errorMessage.orEmpty().contains("LIMIT")
                if (!limited || ++attempt > QPS_RETRIES) throw IllegalStateException("高德搜索：${busy.errorMessage}", busy)
                Thread.sleep(QPS_BACKOFF_MS * attempt)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** [block] on the main thread, waited for: the SDK's navigator wants it there. */
    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<T?>()
        val done = CountDownLatch(1)
        main.post {
            try {
                result.set(block())
            } finally {
                done.countDown()
            }
        }
        done.await(MAIN_WAIT_S, TimeUnit.SECONDS)
        return result.get()
    }

    private companion object {
        const val ROUTE_WAIT_S = 20L
        const val MAIN_WAIT_S = 10L
        const val LOCATION_EVERY_MS = 5_000L
        /** The emulator's speed: brisk, so a demo reaches its stops in minutes rather than an hour. */
        const val SIMULATED_KMH = 120
        const val ALONG_SAMPLE_M = 500.0
        const val ALONG_MAX_POINTS = 100
        const val ALONG_RANGE_M = 500
        const val MIN_REQUEST_GAP_MS = 400L
        /** After stopping a drive, before planning the next: the native navigator winds down on its own threads. */
        const val STOP_SETTLE_MS = 700L
        const val WEATHER_CACHE_MS = 20 * 60 * 1000L
        const val WEATHER_WAIT_S = 10L
        const val REGEOCODE_RADIUS_M = 200f
        const val PREFS = "playground_nav"
        const val DEMO_START_KEY = "demo_start"
        /** 深圳市民中心, in 福田: the demo script's routes all start in the middle of 深圳. */
        val DEMO_START = GeoPoint(22.543_099, 114.057_868)
        const val DEMO_CITY = "深圳市"
        const val SEARCH_OK = 1000
        const val QPS_RETRIES = 3
        const val QPS_BACKOFF_MS = 1_000L
        const val SEARCH_CACHE_MS = 10 * 60 * 1000L
    }
}

/**
 * The user's consent to AMap's privacy terms, which the SDK requires before any call; kept per install.
 */
object AMapPrivacy {
    private const val PREFS = "amap_privacy"
    private const val AGREED = "agreed"
    const val POLICY_URL = "https://lbs.amap.com/pages/privacy/"

    fun agreed(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AGREED, false)

    fun agree(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AGREED, true).apply()
        applyTo(context)
    }

    /** Tells every part of the SDK that the terms were shown and agreed to. */
    internal fun applyTo(context: Context) {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        NaviSetting.updatePrivacyShow(context, true, true)
        NaviSetting.updatePrivacyAgree(context, true)
        ServiceSettings.updatePrivacyShow(context, true, true)
        ServiceSettings.updatePrivacyAgree(context, true)
        AMapLocationClient.updatePrivacyShow(context, true, true)
        AMapLocationClient.updatePrivacyAgree(context, true)
    }
}

private fun Place.toNaviPoi(): NaviPoi = NaviPoi(name, LatLng(point.lat, point.lng), poiId)

private fun PoiItemV2.toPlace(from: GeoPoint?): Place {
    val point = GeoPoint(latLonPoint.latitude, latLonPoint.longitude)
    val business = business
    return Place(
        name = title.orEmpty(),
        point = point,
        poiId = poiId.orEmpty(),
        address = listOfNotNull(adName?.takeIf { it.isNotBlank() }, snippet?.takeIf { it.isNotBlank() }).joinToString(""),
        rating = business?.getmRating().orEmpty(),
        openToday = business?.opentimeToday.orEmpty(),
        tel = business?.tel.orEmpty(),
        distanceMeters = from?.metersTo(point)?.toInt() ?: -1,
    )
}

private fun NaviInfo.toProgress(): NavProgress = NavProgress(
    remainingMeters = pathRetainDistance,
    remainingSeconds = pathRetainTime,
    toWaypoints = toViaInfo.orEmpty().map { ViaProgress(it.distance, it.time) },
    currentRoad = currentRoadName.orEmpty(),
    nextRoad = nextRoadName.orEmpty(),
    stepMeters = curStepRetainDistance,
    turnIcon = iconType,
    speedKmh = currentSpeed,
    lightsLeft = routeRemainLightCount,
)

private fun RoutePreference.strategy(amap: AMapNavi): Int = when (this) {
    RoutePreference.RECOMMENDED -> amap.strategyConvert(false, false, false, false, false)
    RoutePreference.AVOID_CONGESTION -> amap.strategyConvert(true, false, false, false, false)
    RoutePreference.NO_HIGHWAY -> amap.strategyConvert(false, true, false, false, false)
    RoutePreference.SAVE_MONEY -> amap.strategyConvert(false, false, true, false, false)
    RoutePreference.HIGHWAY_FIRST -> amap.strategyConvert(false, false, false, true, false)
}

private fun AlongCategory.amapType(): RoutePOISearch.RoutePOISearchType = when (this) {
    AlongCategory.GAS -> RoutePOISearch.RoutePOISearchType.TypeGasStation
    AlongCategory.CHARGING -> RoutePOISearch.RoutePOISearchType.TypeChargeStation
    AlongCategory.FOOD -> RoutePOISearch.RoutePOISearchType.TypeFood
    AlongCategory.HOTEL -> RoutePOISearch.RoutePOISearchType.TypeHotel
    AlongCategory.TOILET -> RoutePOISearch.RoutePOISearchType.TypeToilet
    AlongCategory.SERVICE_AREA -> RoutePOISearch.RoutePOISearchType.TypeServiceArea
    AlongCategory.ATM -> RoutePOISearch.RoutePOISearchType.TypeATM
    AlongCategory.REPAIR -> RoutePOISearch.RoutePOISearchType.TypeMaintenanceStation
}

/** No navigation: for tests and for a build without an AMap key. */
internal class UnavailableNavEngine(private val why: String) : NavEngine {
    override fun unavailable(): String = why
    override fun currentLocation(): GeoPoint? = null
    override fun currentCity(): String = ""
    override fun search(keyword: String, city: String, near: GeoPoint?, limit: Int): List<Place> = emptyList()
    override fun searchNearby(keyword: String, center: GeoPoint, radiusMeters: Int, limit: Int, byDistance: Boolean, types: String): List<Place> = emptyList()
    override fun searchAlongRoute(category: AlongCategory, limit: Int): List<Place> = emptyList()
    override fun routeLine(): List<GeoPoint> = emptyList()
    override fun plan(start: GeoPoint?, destination: Place, waypoints: List<Place>, preference: RoutePreference) = RouteResult(why)
    override fun startDriving(simulated: Boolean) = Unit
    override fun stop() = Unit
}
