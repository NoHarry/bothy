package cc.noharry.bothy.app.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cc.noharry.agent.appserver.protocol.PageReadParams
import cc.noharry.agent.appserver.protocol.PageRequests
import cc.noharry.agent.appserver.protocol.view.ShownPage
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.ui.IconFullscreen
import cc.noharry.bothy.app.console.ui.dropWebView
import cc.noharry.bothy.app.frontend.LocalServer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A page the agent drew (`show_html`), in the conversation as the page itself, running, under its title: as tall as the
 * page up to [MAX_INLINE_HEIGHT], and full screen from its corner. It runs offline (scripts on; network, files,
 * navigation, new windows and dialogs off; see [pageDocument] for its content policy) in the screen's colours. [onSend]
 * is the page's one way back, `assistant.send(text)`: it says the text for the person, and only right after they tapped
 * the page. [onWhole] shows it whole elsewhere where a dialog cannot open (a window over other apps has no activity).
 */
@Composable
internal fun DrawnPageCard(page: ShownPage, onSend: (String) -> Unit, modifier: Modifier = Modifier, onWhole: (() -> Unit)? = null) {
    val colors = ConsoleColors
    val html = rememberPageHtml(page.reference)
    var fullScreen by remember(page.reference) { mutableStateOf(false) }
    val whole = onWhole ?: { fullScreen = true }
    Surface(
        shape = RoundedCornerShape(CARD_RADIUS),
        color = colors.elevated,
        border = BorderStroke(1.dp, colors.border),
        modifier = modifier.widthIn(max = MAX_CARD_WIDTH).fillMaxWidth(),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    page.title,
                    style = ConsoleType.body,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!html.isNullOrEmpty()) {
                    IconButton(onClick = whole) {
                        Icon(IconFullscreen, stringResource(R.string.console_open_fullscreen_preview), tint = colors.muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderSubtle))
            when {
                html == null -> PageNote(stringResource(R.string.page_loading))
                html.isEmpty() -> PageNote(stringResource(R.string.page_gone))
                else -> InlinePage(page.reference, html, onSend, onFullScreen = whole)
            }
        }
    }
    if (fullScreen && !html.isNullOrEmpty()) {
        PageFullScreen(page.reference, page.title, html, onSend = { fullScreen = false; onSend(it) }, onClose = { fullScreen = false })
    }
}

@Composable
private fun PageNote(text: String) {
    Text(text, style = ConsoleType.meta, color = ConsoleColors.muted, modifier = Modifier.padding(16.dp))
}

/**
 * The page in the card, as tall as it reports itself once it has, so it never scrolls inside the conversation. One
 * taller than [MAX_INLINE_HEIGHT] (or growing with its own height, as a page sized by the screen's does) stops there,
 * with the way to see it whole. A page that broke its renderer says so.
 */
@Composable
private fun InlinePage(reference: String, html: String, onSend: (String) -> Unit, onFullScreen: () -> Unit) {
    val colors = PageColors.of(ConsoleColors)
    val document = remember(html, colors) { pageDocument(html, colors, inline = true) }
    // As tall as it was last time, so a card scrolled back into view does not start small and grow.
    var heightPx by remember(reference) { mutableIntStateOf(PageHeights.get(reference) ?: 0) }
    var broken by remember(reference) { mutableStateOf(false) }
    if (broken) return PageNote(stringResource(R.string.page_broken))
    val height = with(LocalDensity.current) { heightPx.toDp() }.takeIf { heightPx > 0 } ?: FIRST_HEIGHT
    PageView(
        document = document,
        onSend = onSend,
        onHeight = {
            heightPx = it
            PageHeights.put(reference, it)
        },
        onBroken = { broken = true },
        reference = reference,
        inline = true,
        modifier = Modifier.fillMaxWidth().height(height.coerceAtMost(MAX_INLINE_HEIGHT)),
    )
    if (height > MAX_INLINE_HEIGHT) {
        Text(
            stringResource(R.string.page_see_whole),
            style = ConsoleType.meta,
            color = ConsoleColors.accent,
            modifier = Modifier.clickable(onClick = onFullScreen).padding(16.dp),
        )
    }
}

@Composable
private fun PageFullScreen(reference: String, title: String, html: String, onSend: (String) -> Unit, onClose: () -> Unit) {
    val colors = ConsoleColors
    val pageColors = PageColors.of(colors)
    val document = remember(html, pageColors) { pageDocument(html, pageColors) }
    var broken by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = colors.elevated, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, stringResource(R.string.console_close_preview), tint = colors.muted)
                    }
                    Text(title, style = ConsoleType.body, fontWeight = FontWeight.SemiBold, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderSubtle))
                if (broken) {
                    PageNote(stringResource(R.string.page_broken))
                } else {
                    PageView(document, onSend, onHeight = {}, onBroken = { broken = true }, reference, inline = false, modifier = Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

/** The WebView a page runs in, loaded again only when its document changes (a new page, or the colours). */
@Composable
private fun PageView(
    document: String,
    onSend: (String) -> Unit,
    onHeight: (Int) -> Unit,
    onBroken: () -> Unit,
    reference: String,
    inline: Boolean,
    modifier: Modifier,
) {
    val send by rememberUpdatedState(onSend)
    val height by rememberUpdatedState(onHeight)
    val broken by rememberUpdatedState(onBroken)
    val touch = LocalPageTouch.current
    AndroidView(
        modifier = modifier,
        factory = { context ->
            pageWebView(context, PageBridge(onSend = { send(it) }, onHeight = { height(it) }), onBroken = { broken() }, onTouch = { touch?.value = it }).apply {
                // In the conversation the card is as tall as the page: nothing to scroll, no bars, no glow at the ends.
                if (inline) {
                    isVerticalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                }
            }
        },
        update = { view ->
            if (view.tag != document) {
                view.tag = document
                view.loadDataWithBaseURL(pageOrigin(reference), document, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun pageWebView(context: Context, bridge: PageBridge, onBroken: () -> Unit, onTouch: (Boolean) -> Unit): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.blockNetworkLoads = true
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(true)
    settings.setGeolocationEnabled(false)
    settings.textZoom = FULL_TEXT_ZOOM
    webViewClient = PageClient(onBroken)
    webChromeClient = PageChrome
    // A page stuck in a loop would hold up every page in the app: its renderer is ended, and the card says it broke.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setWebViewRenderProcessClient(EndUnresponsive)
    setBackgroundColor(AndroidColor.TRANSPARENT)
    // The page measures itself in CSS pixels, which are the platform's dp whatever density the screen draws at.
    bridge.density = context.resources.displayMetrics.density
    addJavascriptInterface(bridge, BRIDGE_NAME)
    // A tap lets the page speak once ([PageBridge.send]), a swipe does not; a touch is said while it lasts
    // ([LocalPageTouch]). The touch goes on to the page.
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    var down: MotionEvent? = null
    setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down?.recycle()
                down = MotionEvent.obtain(event)
                onTouch(true)
            }
            MotionEvent.ACTION_UP -> {
                down?.let { start -> if (isTap(start, event, slop)) bridge.touched() }
                onTouch(false)
            }
            MotionEvent.ACTION_CANCEL -> onTouch(false)
        }
        false
    }
}

private fun isTap(down: MotionEvent, up: MotionEvent, slop: Int): Boolean =
    up.eventTime - down.eventTime < TAP_MS && abs(up.x - down.x) < slop && abs(up.y - down.y) < slop

/** Each page its own origin, so one page's storage is not another's. */
private fun pageOrigin(reference: String): String = "https://p${reference.hashCode().toUInt().toString(RADIX)}.page.bothy.invalid/"

/**
 * What a page may ask of the app. [send] says its text for the person only within [TOUCH_WINDOW_MS] of their tapping
 * the page, once per tap: a page cannot speak for them on its own, or again and again. [height] is the page's height in
 * CSS pixels, given back in screen pixels: kept within bounds, a repeat ignored, and at most one waiting for the main
 * thread however often the page reports. Calls come on the WebView's bridge thread.
 */
private class PageBridge(private val onSend: (String) -> Unit, private val onHeight: (Int) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var touchedAt = 0L
    @Volatile private var sentAt = 0L
    @Volatile var density = 1f

    fun touched() {
        touchedAt = SystemClock.uptimeMillis()
    }

    @JavascriptInterface
    fun send(text: String?) {
        val words = text?.trim()?.take(MAX_SENT_CHARS).orEmpty()
        val now = SystemClock.uptimeMillis()
        if (words.isEmpty() || now - touchedAt > TOUCH_WINDOW_MS || sentAt >= touchedAt) return
        sentAt = now
        main.post { onSend(words) }
    }

    @Volatile private var latest = -1
    private val posted = AtomicBoolean(false)

    @JavascriptInterface
    fun height(cssPixels: Int) {
        val bounded = cssPixels.coerceIn(0, MAX_CSS_HEIGHT)
        if (bounded == latest) return
        latest = bounded
        if (!posted.compareAndSet(false, true)) return
        main.post {
            posted.set(false)
            onHeight((latest * density).roundToInt())
        }
    }
}

/** A page stays where it is: links and form posts go nowhere; a renderer that died is reported, not the app's crash. */
private class PageClient(private val onBroken: () -> Unit) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onBroken()
        return dropWebView(view)
    }
}

/** No new windows, and no alert, confirm, prompt or leave-page dialogs: a page cannot hold the screen with them. */
private object PageChrome : WebChromeClient() {
    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false

    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean = true.also { result.cancel() }

    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean = true.also { result.cancel() }

    override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
        true.also { result.cancel() }

    override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean = true.also { result.cancel() }
}

@RequiresApi(Build.VERSION_CODES.Q)
private object EndUnresponsive : WebViewRenderProcessClient() {
    override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) {
        renderer?.terminate()
    }

    override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) = Unit
}

/**
 * Whether a finger is on a drawn page, for a screen whose own sideways gesture would take the page's drags (the phone's
 * drawer opens with one); null where nothing needs to know.
 */
internal val LocalPageTouch = staticCompositionLocalOf<MutableState<Boolean>?> { null }

/** The page's HTML: null while it is read, empty when the server no longer has it. Kept for the last few pages. */
@Composable
private fun rememberPageHtml(reference: String): String? {
    val server = LocalServer.current
    var html by remember(reference) { mutableStateOf(PageCache.get(reference)) }
    LaunchedEffect(reference) {
        if (html != null) return@LaunchedEffect
        server.send(PageRequests.READ, PageReadParams(reference), onRefused = { html = "" }) { read ->
            html = read.html.orEmpty().also { if (it.isNotEmpty()) PageCache.put(reference, it) }
        }
    }
    return html
}

private object PageCache : LruCache<String, String>(CACHED_PAGES)

/** Each page's last height, by reference. */
private object PageHeights : LruCache<String, Int>(CACHED_HEIGHTS)

private const val BRIDGE_NAME = "BothyHost"
private const val FULL_TEXT_ZOOM = 100
private const val TOUCH_WINDOW_MS = 1_500L
private const val MAX_SENT_CHARS = 500
private const val CACHED_PAGES = 8
private const val CACHED_HEIGHTS = 64
private const val TAP_MS = 500L
private const val RADIX = 36
/** Taller than any card shows ([MAX_INLINE_HEIGHT]); a page reporting more is held here. */
private const val MAX_CSS_HEIGHT = 20_000
private val CARD_RADIUS = 16.dp
private val MAX_CARD_WIDTH = 720.dp
private val FIRST_HEIGHT = 160.dp
private val MAX_INLINE_HEIGHT = 2400.dp
