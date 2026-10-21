package cc.noharry.bothy.sample

import android.content.Context
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The console's inline picture: a fixed frame (so the transcript does not jump when the image decodes), the
 * prompt beneath once known, a pulsing line while drawing, and a note when the file has been cleared.
 */
@Composable
internal fun GeneratedImageRow(picture: DebugOverlayPicture) {
    val colors = DebugColors
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = DebugDimens.spaceXs),
        verticalArrangement = Arrangement.spacedBy(DebugDimens.spaceXs),
    ) {
        val reference = picture.reference
        // File checks stay off the main thread; until known, the frame shows as if present. A web photo has no file.
        val present by produceState<Boolean?>(null, reference) {
            value = reference?.let { ref -> !SAVED_PICTURE.matches(ref) || withContext(Dispatchers.IO) { generatedImageFile(context, ref) != null } }
        }
        when {
            reference == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                DebugStatusDot(color = colors.activity, pulsing = true, modifier = Modifier.size(7.dp))
                Spacer(Modifier.width(DebugDimens.spaceSm))
                Text("正在生成图片…", style = DebugType.meta, color = colors.muted)
            }
            present == false -> Text("图片文件已清理", style = DebugType.meta, color = colors.tertiary)
            else -> {
                val bitmap by rememberPicture(reference, CONSOLE_IMAGE_PX)
                var viewing by remember(reference) { mutableStateOf(false) }
                if (viewing) GeneratedImageViewer(reference, picture.prompt) { viewing = false }
                Box(
                    Modifier
                        .size(CONSOLE_IMAGE_SIZE)
                        .clip(RoundedCornerShape(DebugDimens.radiusMd))
                        .background(colors.panel)
                        .clickable(onClickLabel = "放大查看") { viewing = true },
                ) {
                    bitmap?.let {
                        Image(it, contentDescription = picture.prompt.ifBlank { "生成的图片" }, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        if (picture.prompt.isNotBlank()) {
            Text(picture.prompt, style = DebugType.meta, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Full-screen view of a generated picture: fitted to the screen over a dark backdrop. Pinch or double-tap
 * zooms; a tap on the backdrop or the close button returns.
 */
@Composable
internal fun GeneratedImageViewer(reference: String, caption: String, onDismiss: () -> Unit) {
    val bitmap by rememberPicture(reference, VIEWER_IMAGE_PX)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .pointerInput(reference) { detectTapGestures(onTap = { onDismiss() }) },
        ) {
            bitmap?.let { ZoomableImage(it, caption.ifBlank { "生成的图片" }, onDismiss) }
            if (caption.isNotBlank()) {
                Text(
                    caption,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(DebugDimens.spaceLg),
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(DebugDimens.spaceMd)) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}

/** Fitted image that pinch-zooms and pans; double tap toggles 2x, a single tap at 1x closes. */
@Composable
private fun ZoomableImage(image: ImageBitmap, description: String, onDismiss: () -> Unit) {
    var zoom by remember(image) { mutableFloatStateOf(1f) }
    var pan by remember(image) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, MAX_VIEWER_ZOOM)
        pan = if (zoom == 1f) Offset.Zero else pan + panChange
    }
    Image(
        image,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .padding(VIEWER_MARGIN)
            .pointerInput(image) {
                detectTapGestures(
                    onTap = { if (zoom == 1f) onDismiss() },
                    onDoubleTap = {
                        zoom = if (zoom > 1f) 1f else DOUBLE_TAP_ZOOM
                        pan = Offset.Zero
                    },
                )
            }
            .transformable(transform)
            .graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = pan.x
                translationY = pan.y
            },
    )
}

private const val TAG = "DebugImage"
private const val CONSOLE_IMAGE_PX = 512
private val CONSOLE_IMAGE_SIZE = 320.dp
/** Full source resolution for the viewer; providers return about 1024px. */
private const val VIEWER_IMAGE_PX = 2048
private const val MAX_VIEWER_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2f
private val VIEWER_MARGIN = 32.dp
