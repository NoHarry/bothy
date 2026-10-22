package cc.noharry.bothy.playground

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.toArgb
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewOptions
import com.amap.api.navi.enums.MapStyle
import kotlin.math.abs

/**
 * The map view, kept so the head unit can drive it: the whole route or the car, places found (numbered pins, the camera
 * moving to each), and the car's surroundings while no route is shown. What it frames, and the car while driving, stay
 * clear of the cards over the map's left part and the progress bar at its right ([cover]).
 */
internal class NaviMapHandle {
    var view: AMapNaviView? = null
        set(value) {
            field = value
            applyLogoLift()
        }
    private var logoLift = 0

    /** The map's bottom [pixels] lie under the head unit's bar: AMap's logo is raised above it, kept in view. */
    fun liftLogo(pixels: Int) {
        if (pixels == logoLift) return
        logoLift = pixels
        applyLogoLift()
    }

    private fun applyLogoLift() {
        runCatching { view?.map?.uiSettings?.setLogoBottomMargin(logoLift) }
    }
    private val pins = mutableListOf<Marker>()
    private var car: Marker? = null
    /** Numbered pins up now; the destination's pin has no number. */
    private var stops = 0
    private var centred = false

    /**
     * Whether AMap draws the route it holds. Off until a route is to be shown ([drawRoute]): AMap keeps the last route
     * even after its navigation stopped, draws it into a new map, and moves the map to a route the moment it is planned.
     */
    var drawing = false
        private set

    /** Pixels of the map's left and right edges under the head unit's cards and bars ([cover]). */
    var coveredLeft = 0
        private set
    private var coveredRight = 0

    /** Where the car was seen against where it was asked to sit ([keepCarCentred]). */
    private val drift = CarDrift()

    /**
     * Where the car sits while driving, as shares of the map: in the middle of the map left open beside the cards,
     * horizontally and vertically (owner 2026-10-21: 「车的位置没有居中」; a fixed spot low on the right left it in a corner).
     */
    private val anchor: Pair<Double, Double>
        get() {
            val view = view ?: return DEFAULT_ANCHOR
            val width = view.width.takeIf { it > 0 } ?: return DEFAULT_ANCHOR
            val share = carShare(width)
            val asked = if (width > view.height) landscapeLockX(share) else share
            return (asked + drift.nudge(width)).coerceIn(0.0, 1.0) to CAR_AT_HEIGHT
        }

    /** The middle of the map the cards leave open, as a share of its [width]. */
    private fun carShare(width: Int): Double {
        val open = (width - coveredLeft - coveredRight).coerceAtLeast(width / 3)
        return (coveredLeft + open / 2.0) / width
    }

    /** The screen's cards now cover [left] and [right] pixels of the map: driving, the car moves to the middle of the rest. */
    fun cover(left: Int, right: Int) {
        if (left == coveredLeft && right == coveredRight) return
        coveredLeft = left
        coveredRight = right
        relock()
    }

    /**
     * Driving, the map draws every frame so the car and the road glide; otherwise only when something changes, as a
     * map at rest needs no more (left continuous, an arrived map kept a core busy).
     */
    fun moving(on: Boolean) {
        runCatching { view?.map?.setRenderMode(if (on) RENDER_CONTINUOUSLY else RENDER_WHEN_DIRTY) }
    }

    /** The map changed width (the assistant's card opened or closed beside it): the car's place is a share of it. */
    fun resized() = relock()

    private fun relock() {
        val view = view ?: return
        if (drawing && !view.isRouteOverviewNow) view.viewOptions = naviOptions(drawRoute = true, anchor)
    }

    /**
     * Driving, locked on the car at [point]: where AMap really drew it, and its place moved by what it missed. AMap's
     * landscape map keeps room for its own (hidden) guidance panel by an amount that changes with the map's state, so
     * [landscapeLockX] is only a first guess (seen on the tablet: 0.508 asked once landed at 0.529, not 0.635).
     */
    fun keepCarCentred(point: GeoPoint) {
        val view = view ?: return
        if (!drawing || view.isRouteOverviewNow || view.width == 0) return
        val seen = view.map.projection.toScreenLocation(LatLng(point.lat, point.lng)) ?: return
        if (drift.seen(view.width, carShare(view.width) - seen.x.toDouble() / view.width)) relock()
    }

    internal fun options(): AMapNaviViewOptions = naviOptions(drawing, anchor)

    fun overview(on: Boolean) {
        val view = view ?: return
        if (on) {
            view.displayOverview()
        } else {
            // Driving, the car sits in the middle of the open map again ([anchor]); framing places had moved the centre.
            view.viewOptions = naviOptions(drawRoute = true, anchor)
            drawing = true
            hideCar()
            view.recoverLockMode()
        }
    }



    /** Framing what is found: the map's true centre, not the car's spot, so the frame lands where it is asked. */
    private fun centred(view: AMapNaviView) {
        if (view.width > 0) view.map.setPointToCenter(view.width / 2, view.height / 2)
    }

    /** AMap draws the route it planned from now on (it may move the map as it does). */
    fun drawRoute() {
        val view = view ?: return
        if (drawing) return
        view.viewOptions = naviOptions(drawRoute = true, anchor)
        drawing = true
        hideCar()
    }

    private fun hideCar() {
        car?.remove()
        car = null
    }

    /** The route planned, whole, framed clear of the cards ([line] its points); AMap's own overview without them. */
    fun showRoute(line: List<GeoPoint>, density: Float) {
        val view = view ?: return
        val map = view.map
        if (line.size < 2) return overview(true)
        centred(view)
        val bounds = LatLngBounds.Builder().apply { line.forEach { include(LatLng(it.lat, it.lng)) } }.build()
        val pad = (FIT_PADDING_DP * density).toInt()
        map.animateCamera(CameraUpdateFactory.newLatLngBoundsRect(bounds, coveredLeft + pad, pad, pad, pad), MOVE_MS, null)
    }

    /**
     * Pins [places] (after those already there when [adding]) and, with [frame], moves to them: one place close up,
     * several framed together.
     */
    fun showPlaces(places: List<Place>, density: Float, adding: Boolean = false, ending: Boolean = false, frame: Boolean = true) {
        if (!adding) clearPlaces()
        val view = view ?: return
        val map = view.map
        if (places.isEmpty()) return
        centred(view)
        places.forEach { place ->
            val pin = if (ending) numberPin("终", PlaygroundColors.ember.toArgb(), density) else numberPin("${stops + 1}", UnitColors.teal.toArgb(), density)
            if (!ending) stops++
            pins += map.addMarker(
                MarkerOptions().position(LatLng(place.point.lat, place.point.lng)).title(place.name)
                    .icon(BitmapDescriptorFactory.fromBitmap(pin)).anchor(0.5f, 1f).zIndex(PIN_Z),
            )
        }
        if (!frame) return
        val bounds = LatLngBounds.Builder().apply {
            places.forEach { include(LatLng(it.point.lat, it.point.lng)) }
            if (places.size == 1) {
                val p = places.single().point
                include(LatLng(p.lat + SINGLE_SPAN_DEG, p.lng + SINGLE_SPAN_DEG))
                include(LatLng(p.lat - SINGLE_SPAN_DEG, p.lng - SINGLE_SPAN_DEG))
            }
        }.build()
        val pad = (FIT_PADDING_DP * density).toInt()
        map.animateCamera(CameraUpdateFactory.newLatLngBoundsRect(bounds, coveredLeft + pad, pad, pad, pad), MOVE_MS, null)
    }

    /** The map made anew: AMap keeps a route drawn after its navigation stops, and has no way to take it off. */
    var generation by mutableIntStateOf(0)
        private set

    /** The navigation ended: the map is made anew without its route (drawing off), around the car again. */
    fun clearRoute() {
        pins.clear()
        car = null
        stops = 0
        centred = false
        drawing = false
        // A new map view: what the old one missed by says nothing of it.
        drift.reset()
        generation++
    }

    fun clearPlaces() {
        pins.forEach { it.remove() }
        pins.clear()
        stops = 0
    }

    /** The car's district, flat: the map at rest after it changed size (AMap zooms right in on its own then). */
    fun showCar(point: GeoPoint, density: Float) {
        val view = view ?: return
        // Not laid out yet, the map ignores the move: left to centreOnce, which tries until it holds.
        if (view.width == 0) return
        val map = view.map
        centred = true
        markCar(point, density)
        centred(view)
        map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition(LatLng(point.lat, point.lng), HOME_ZOOM, 0f, 0f)), MOVE_MS, null)
    }

    /** The car at rest, as an arrow (AMap draws its own only while it navigates). */
    fun markCar(point: GeoPoint, density: Float) {
        val map = view?.map ?: return
        if (drawing) return
        val at = LatLng(point.lat, point.lng)
        car?.let { it.position = at; return }
        car = map.addMarker(MarkerOptions().position(at).icon(BitmapDescriptorFactory.fromBitmap(carArrow(density))).anchor(0.5f, 0.5f).zIndex(PIN_Z))
    }

    /** Once, where the car is: before any route the map would otherwise open on Beijing. */
    fun centreOnce(point: GeoPoint) {
        val view = view ?: return
        val map = view.map
        // Not laid out yet, the map takes no centre and ignores the move: tried again until it holds.
        if (centred || view.width == 0) return
        centred(view)
        // Flat and north up, as the head unit's map at rest; driving tilts it again.
        map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition(LatLng(point.lat, point.lng), HOME_ZOOM, 0f, 0f)))
        val now = map.cameraPosition
        centred = now != null && GeoPoint(now.target.latitude, now.target.longitude).metersTo(point) < CENTRED_WITHIN_M &&
            abs(now.zoom - HOME_ZOOM) < 1f
    }
}

/** A round pin of [color] with [label] in it, [density] pixels per dp. */
private fun numberPin(label: String, color: Int, density: Float): Bitmap {
    val size = (PIN_DP * density).toInt()
    val bitmap = Bitmap.createBitmap(size, (size * 1.25f).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = size / 2f
    paint.color = color
    canvas.drawPath(Path().apply { moveTo(r * 0.55f, r * 1.6f); lineTo(r, size * 1.25f); lineTo(r * 1.45f, r * 1.6f); close() }, paint)
    canvas.drawCircle(r, r, r - 1f, paint)
    paint.color = android.graphics.Color.WHITE
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2f * density
    canvas.drawCircle(r, r, r - 2f * density, paint)
    paint.style = Paint.Style.FILL
    paint.textSize = r * 1.05f
    paint.textAlign = Paint.Align.CENTER
    paint.isFakeBoldText = true
    canvas.drawText(label, r, r - (paint.descent() + paint.ascent()) / 2f, paint)
    return bitmap
}

/** The car at rest: a teal arrow in a white ring, pointing up. */
private fun carArrow(density: Float): Bitmap {
    val size = (CAR_DP * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = size / 2f
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(r, r, r, paint)
    paint.color = UnitColors.teal.toArgb()
    canvas.drawCircle(r, r, r - 2.5f * density, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawPath(Path().apply { moveTo(r, r * 0.42f); lineTo(r * 1.42f, r * 1.5f); lineTo(r, r * 1.22f); lineTo(r * 0.58f, r * 1.5f); close() }, paint)
    return bitmap
}

private const val CAR_DP = 34
private const val PIN_DP = 30
private const val PIN_Z = 50f
private const val FIT_PADDING_DP = 90
/** Around a single place, about a kilometre each way: close enough to see the streets. */
private const val SINGLE_SPAN_DEG = 0.008
private const val MOVE_MS = 900L
private const val HOME_ZOOM = 14f
/** How close the camera must have come to the car for the map to count as centred on it. */
private const val CENTRED_WITHIN_M = 500.0

/**
 * AMap's navigation map at night, with the SDK's own widgets hidden: the head unit draws its own turn card, stops and
 * progress over it. The car sits low and to the right, clear of the cards on the left.
 */
@Composable
internal fun NaviMap(handle: NaviMapHandle, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    key(handle.generation) {
        var made by remember { mutableStateOf<AMapNaviView?>(null) }
        AndroidView(
            factory = { context ->
                AMapNaviView(context, handle.options()).also { view ->
                    view.onCreate(null)
                    // The head unit is always at night; the options alone leave the map in day colours.
                    view.viewOptions = handle.options()
                    // AMap's own bubbles on the map, as its stock screen shows them (owner 2026-10-22: 「红绿灯的倒计时没看见」):
                    // the light's countdown at the next junction and how long a jam ahead lasts.
                    view.setShowTrafficLightView(true)
                    view.setShowDriveCongestion(true)
                    // Smooth as AMap's stock screen (owner 2026-10-22: 「明显比公版卡顿」): drawn only when changed, driving drew
                    // the map at ~10 fps (a frame every 100 ms, the car stepping); driving it now draws every frame ([moving]).
                    view.setDriveGuideNaviAnimation(true)
                    runCatching { view.map.setRenderFps(MAP_FPS) }
                    // Drawn every frame, the map is kept light as AMap's stock screen keeps it: no 3D buildings.
                    runCatching { view.map.showBuildings(false) }
                    view.onResume()
                    view.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                        if (right - left != oldRight - oldLeft) handle.resized()
                    }
                    handle.view = view
                    made = view
                }
            },
            modifier = modifier,
        )
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> made?.onResume()
                    Lifecycle.Event.ON_PAUSE -> made?.onPause()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                made?.onDestroy()
                if (handle.view === made) handle.view = null
            }
        }
    }
}

/** Where the map would be, while AMap cannot run yet. */
@Composable
internal fun MapPlaceholder(reason: String, modifier: Modifier = Modifier) {
    Box(modifier.background(UnitColors.panel), contentAlignment = Alignment.Center) {
        Text(reason, color = UnitColors.muted, fontSize = 14.sp)
    }
}

/** [drawRoute]: AMap draws the route it holds ([NaviMapHandle.drawing]); driving, the car sits at [anchor] (shares of the map). */
private fun naviOptions(drawRoute: Boolean, anchor: Pair<Double, Double>): AMapNaviViewOptions = AMapNaviViewOptions().apply {
    setLayoutVisible(false)
    setMapStyle(MapStyle.NIGHT, null)
    setTrafficBarEnabled(false)
    setTrafficLayerEnabled(true)
    // Speed cameras ahead as bubbles on the route, with their limits.
    setCameraBubbleShow(true)
    setRouteListButtonShow(false)
    setSettingMenuEnabled(false)
    setCompassEnabled(false)
    // At rest the map stays where it was put (flat, the car's district); only a route locks it onto the car.
    setAutoLockCar(drawRoute)
    // Flatter than AMap's default, as its stock screen drives: more road ahead, less to draw.
    setTilt(DRIVING_TILT)
    setAutoDisplayOverview(false)
    // The lanes and the junction's picture come to the head unit, which draws them in its own cards (Guidance); AMap's
    // own lane strip and 3D junction model would draw over the map a second time.
    setRealCrossDisplayShow(true)
    setModeCrossDisplayShow(false)
    setLaneInfoShow(false)
    setEagleMapVisible(false)
    setAutoDrawRoute(drawRoute)
    setPointToCenter(anchor.first, anchor.second)
}

/**
 * What the car's place across the map is moved by, learnt from where it was seen: only once it is off the same way
 * twice running, by the two together, as one sighting can catch the car between AMap's updates or turning. Learnt for
 * each width of the map apart: what AMap misses by on the whole map says nothing of the map beside the assistant's card.
 */
internal class CarDrift {
    private val nudges = mutableMapOf<Int, Double>()
    private var last = 0.0
    private var lastWidth = 0

    /** Added to the share asked for, on a map [width] pixels wide. */
    fun nudge(width: Int): Double = nudges[width] ?: 0.0

    /** Seen [off] (the share of the map's width it should move right by); true when [nudge] changed. */
    fun seen(width: Int, off: Double): Boolean {
        if (width != lastWidth) {
            last = 0.0
            lastWidth = width
        }
        // Close enough, or far off because the map is not on the car (moved by hand, or framing places).
        if (abs(off) < CAR_OFF_SHARE || abs(off) > CAR_LOST_SHARE) {
            last = 0.0
            return false
        }
        if (last * off <= 0.0) {
            last = off
            return false
        }
        nudges[width] = (nudge(width) + (last + off) / 2).coerceIn(-MAX_NUDGE, MAX_NUDGE)
        last = 0.0
        return true
    }

    fun reset() {
        nudges.clear()
        last = 0.0
    }
}

/** Off by less than this share of the map's width, the car counts as in the middle. */
private const val CAR_OFF_SHARE = 0.025
/** Off by more, the map is not on the car. */
private const val CAR_LOST_SHARE = 0.3
private const val MAX_NUDGE = 0.4

/**
 * What to ask AMap for so the car sits at [share] of a landscape map's width: there it keeps the left part for its own
 * guidance panel (hidden here) and puts the car at [LANDSCAPE_LOCK_FROM] + [LANDSCAPE_LOCK_SPAN] × the share asked for,
 * as measured on the tablet (0.68 asked → 0.743, 0.637 → 0.716). Not always: [CarDrift] corrects it from the car seen.
 */
internal fun landscapeLockX(share: Double): Double = ((share - LANDSCAPE_LOCK_FROM) / LANDSCAPE_LOCK_SPAN).coerceIn(0.0, 1.0)

private const val LANDSCAPE_LOCK_FROM = 0.315
private const val LANDSCAPE_LOCK_SPAN = 0.63

/** Before the map is laid out: the middle, a little low, so more of the road ahead shows. */
private val DEFAULT_ANCHOR = 0.5 to 0.58
/** Driving, the car a little below the middle: the road ahead gets more of the screen than the road behind. */
private const val CAR_AT_HEIGHT = 0.58

/** The map's frame rate while it moves. */
private const val MAP_FPS = 30
/** GLSurfaceView's render modes, which AMap's follows: on change only, or every frame. */
private const val RENDER_WHEN_DIRTY = 0
private const val RENDER_CONTINUOUSLY = 1
/** The camera's tilt while driving, degrees. */
private const val DRIVING_TILT = 30
