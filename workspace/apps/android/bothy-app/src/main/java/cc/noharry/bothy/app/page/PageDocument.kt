package cc.noharry.bothy.app.page

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import cc.noharry.bothy.app.console.ConsoleThemeColors

/** The screen's colours as a drawn page gets them, as CSS variables (the tool's description names them). */
internal data class PageColors(
    val bg: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val border: Color,
    val warn: Color,
    val danger: Color,
    val dark: Boolean,
) {
    companion object {
        /** A page sits on a card: its ground is the card's, its blocks the panel's. */
        fun of(colors: ConsoleThemeColors): PageColors = PageColors(
            bg = colors.elevated,
            surface = colors.panel,
            text = colors.text,
            muted = colors.muted,
            accent = colors.accent,
            onAccent = if (colors.isDark) colors.bg else Color.White,
            border = colors.border,
            warn = colors.activity,
            danger = colors.danger,
            dark = colors.isDark,
        )
    }
}

/**
 * [html] as the screen loads it: in standards mode, with the host's head before any of the page, so nothing the page
 * writes (a comment, a script, a head of its own) comes ahead of it. The page's own head and styles still land in the
 * head after it, as the parser takes them, so they win. The host's head holds a content policy that lets nothing load
 * from anywhere (scripts and styles only inline, pictures only as data, no forms posted, no other base), the colours
 * ([PageColors]) with a plain base style and a small kit, and the bridge: `assistant.send(text)` and the page's height,
 * reported as it changes so the card can be as tall as the page. [inline], in the conversation, the page itself does not
 * scroll up and down: the card is as tall as the page, and a swipe there scrolls the conversation.
 */
internal fun pageDocument(html: String, colors: PageColors, inline: Boolean = false): String =
    "<!doctype html><html><head>${hostHead(colors, inline)}</head>" + html.replaceFirst(LEADING_DOCTYPE, "")

private fun hostHead(colors: PageColors, inline: Boolean): String = buildString {
    append("<meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<meta http-equiv=\"Content-Security-Policy\" content=\"$CONTENT_POLICY\">")
    append("<style>:root{")
    append("--page-bg:${colors.bg.css()};--page-surface:${colors.surface.css()};--page-text:${colors.text.css()};")
    append("--page-muted:${colors.muted.css()};--page-accent:${colors.accent.css()};--page-on-accent:${colors.onAccent.css()};")
    append("--page-border:${colors.border.css()};--page-warn:${colors.warn.css()};--page-danger:${colors.danger.css()};")
    append("color-scheme:${if (colors.dark) "dark" else "light"}}")
    append(BASE_STYLE)
    append(KIT_STYLE)
    if (inline) append(INLINE_STYLE)
    append("</style><script>")
    append(BRIDGE_SCRIPT)
    append("</script>")
}

private fun Color.css(): String = "#%06X".format(toArgb() and RGB_MASK)

/** Nothing from the network or the device: scripts and styles written into the page, pictures as data or blobs. */
private const val CONTENT_POLICY =
    "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; " +
        "media-src data: blob:; frame-src 'none'; form-action 'none'; base-uri 'none'"

private const val BASE_STYLE =
    "*,*::before,*::after{box-sizing:border-box}html{-webkit-text-size-adjust:100%}" +
        "body{margin:0;padding:14px 16px;display:flow-root;background:var(--page-bg);color:var(--page-text);" +
        "font:15px/1.5 system-ui,sans-serif;overflow-wrap:anywhere}" +
        "button,input,select,textarea{font:inherit}"

/**
 * The host's page kit, named in the tool's description, so the model writes structure rather than a stylesheet: fewer
 * tokens, sooner on screen, and pages that look like the app.
 */
private const val KIT_STYLE =
    ".b-stack{display:flex;flex-direction:column;gap:12px}.b-row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}" +
        ".b-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px}" +
        ".b-card{background:var(--page-surface);border:1px solid var(--page-border);border-radius:14px;padding:14px}" +
        ".b-h{font-size:16px;font-weight:600;margin:4px 0 8px}.b-sub{color:var(--page-muted);font-size:13px}" +
        ".b-stat b{display:block;font-size:26px;line-height:1.2;color:var(--page-accent)}" +
        ".b-stat span{color:var(--page-muted);font-size:12px}" +
        ".b-tag{display:inline-block;padding:2px 8px;border-radius:999px;font-size:12px;" +
        "background:color-mix(in srgb,var(--page-accent) 14%,transparent);color:var(--page-accent)}" +
        ".b-tag.warn{background:color-mix(in srgb,var(--page-warn) 16%,transparent);color:var(--page-warn)}" +
        ".b-tag.bad{background:color-mix(in srgb,var(--page-danger) 14%,transparent);color:var(--page-danger)}" +
        ".b-steps{counter-reset:s;list-style:none;padding:0;margin:0}" +
        ".b-steps li{counter-increment:s;position:relative;padding:0 0 12px 34px}" +
        ".b-steps li::before{content:counter(s);position:absolute;left:0;top:0;width:24px;height:24px;border-radius:50%;" +
        "background:var(--page-accent);color:var(--page-on-accent);font-size:13px;display:flex;align-items:center;" +
        "justify-content:center}" +
        ".b-timeline{list-style:none;margin:0;padding:0 0 0 14px;border-left:2px solid var(--page-border)}" +
        ".b-timeline li{position:relative;padding:0 0 12px 12px}" +
        ".b-timeline li::before{content:'';position:absolute;left:-21px;top:7px;width:10px;height:10px;border-radius:50%;" +
        "background:var(--page-accent)}" +
        ".b-bar{height:8px;border-radius:4px;background:var(--page-border);overflow:hidden}" +
        ".b-bar i{display:block;height:100%;background:var(--page-accent)}" +
        ".b-btn{display:inline-block;border:0;border-radius:999px;padding:10px 16px;background:var(--page-accent);" +
        "color:var(--page-on-accent);font-weight:600}" +
        ".b-btn.ghost{background:transparent;color:var(--page-accent);border:1px solid var(--page-border)}"

/** Up and down is the conversation's; a page wider than the screen still moves sideways. */
private const val INLINE_STYLE = "html,body{overflow-y:hidden!important;overscroll-behavior:none}"

/**
 * First of anything on the page: peer connections go (their network traffic is outside the content policy and the
 * WebView's own network block), also in frames the page reaches into; then the bridge. `BothyHost` is the app's
 * ([PageBridge]); a page sees only `assistant.send`. The bridge also says when a field that takes typing gets or loses
 * the focus, for a window that takes the keyboard only then.
 */
private const val BRIDGE_SCRIPT =
    "(function(){var off=function(w){['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel'].forEach(function(k){" +
        "try{Object.defineProperty(w,k,{value:undefined,writable:false,configurable:false});}catch(e){}});};off(window);" +
        "var cw=Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype,'contentWindow');" +
        "if(cw&&cw.get)Object.defineProperty(HTMLIFrameElement.prototype,'contentWindow',{get:function(){" +
        "var w=cw.get.call(this);if(w)off(w);return w;},configurable:false});" +
        "var host=window.BothyHost;window.assistant={send:function(t){if(host)host.send(String(t));}};" +
        "function typing(e){if(!e)return false;if(e.isContentEditable||e.tagName==='TEXTAREA')return true;" +
        "return e.tagName==='INPUT'&&/^(text|search|email|number|tel|url|password)$/.test(e.type);}" +
        "document.addEventListener('focusin',function(v){if(host&&typing(v.target))host.editing(true);});" +
        "document.addEventListener('focusout',function(v){if(host&&typing(v.target))host.editing(false);});" +
        "function report(){var b=document.body;if(!b||!host)return;var s=getComputedStyle(b);" +
        "host.height(Math.ceil(Math.max(b.scrollHeight,b.getBoundingClientRect().height)+" +
        "(parseFloat(s.marginTop)||0)+(parseFloat(s.marginBottom)||0)));}" +
        "document.addEventListener('DOMContentLoaded',function(){report();" +
        "if(window.ResizeObserver)new ResizeObserver(report).observe(document.body);});" +
        "window.addEventListener('load',report);})();"

private val LEADING_DOCTYPE = Regex("^\\s*<!doctype[^>]*>", RegexOption.IGNORE_CASE)
private const val RGB_MASK = 0xFFFFFF
