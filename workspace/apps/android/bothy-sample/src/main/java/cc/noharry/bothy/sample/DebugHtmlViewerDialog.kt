package cc.noharry.bothy.sample

import android.graphics.Color as AndroidColor
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** In-memory HTML page to show in the fullscreen viewer. */
internal data class DebugHtmlViewerRequest(
    val title: String,
    val html: String,
)

/**
 * Fullscreen WebView preview for generated pages (artifact card / file browser).
 * JavaScript is enabled here so interactive pages work; the small board card stays
 * JS-free. Pages render from memory via [WebView.loadDataWithBaseURL], with file/content
 * access, network loads, navigation, and new windows blocked.
 */
@Composable
internal fun DebugHtmlViewerDialog(
    request: DebugHtmlViewerRequest,
    onDismiss: () -> Unit,
) {
    val colors = DebugColors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.bg,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.panel)
                        .padding(horizontal = DebugDimens.spaceXs, vertical = DebugDimens.spaceXxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close preview",
                            tint = colors.muted,
                        )
                    }
                    Spacer(Modifier.width(DebugDimens.spaceXs))
                    Text(
                        text = request.title,
                        style = DebugType.body,
                        color = colors.text,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(DebugDimens.spaceXxs)
                        .background(colors.borderSubtle),
                )
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.blockNetworkLoads = true
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.setSupportMultipleWindows(true)
                            webViewClient = BlockingNavigationClient
                            webChromeClient = BlockingWindowClient
                            setBackgroundColor(AndroidColor.TRANSPARENT)
                        }
                    },
                    update = { webView ->
                        if (webView.tag != request) {
                            webView.tag = request
                            webView.loadDataWithBaseURL(
                                HTML_VIEWER_BASE_URL,
                                request.html,
                                HTML_VIEWER_MIME_TYPE,
                                HTML_VIEWER_ENCODING,
                                null,
                            )
                        }
                    },
                )
            }
        }
    }
}

private const val HTML_VIEWER_BASE_URL = "https://artifact.preview/"
private const val HTML_VIEWER_MIME_TYPE = "text/html"
private const val HTML_VIEWER_ENCODING = "UTF-8"

private object BlockingNavigationClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
}

private object BlockingWindowClient : WebChromeClient() {
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean = false
}
