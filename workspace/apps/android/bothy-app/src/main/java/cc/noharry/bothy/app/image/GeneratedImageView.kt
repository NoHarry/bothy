package cc.noharry.bothy.app.image
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cc.noharry.agent.appserver.protocol.view.OverlayPicture
import cc.noharry.agent.appserver.protocol.view.SAVED_PICTURE
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.color
import cc.noharry.bothy.app.console.ui.StatusDot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The console's inline picture: a fixed frame (so the transcript does not jump when the image decodes), the
 * prompt beneath once known, a pulsing line while drawing, and a note when the file has been cleared.
 */
@Composable
internal fun GeneratedImageRow(picture: OverlayPicture) {
    val colors = ConsoleColors
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = Dimens.spaceXs),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
    ) {
        val reference = picture.reference
        // File checks stay off the main thread; until known, the frame shows as if present. A web photo has no file.
        val present by produceState<Boolean?>(null, reference) {
            value = reference?.let { ref -> !SAVED_PICTURE.matches(ref) || withContext(Dispatchers.IO) { savedPictureFile(context, ref) != null } }
        }
        when {
            reference == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = colors.activity, pulsing = true, modifier = Modifier.size(7.dp))
                Spacer(Modifier.width(Dimens.spaceSm))
                Text(stringResource(R.string.image_generating_picture), style = ConsoleType.meta, color = colors.muted)
            }
            present == false -> {
                Text(stringResource(R.string.image_file_cleared), style = ConsoleType.meta, color = colors.tertiary)
            }
            else -> {
                val bitmap by rememberPicture(reference, CONSOLE_IMAGE_PX)
                var viewing by remember(reference) { mutableStateOf(false) }
                if (viewing) GeneratedImageViewer(reference, picture.prompt) { viewing = false }
                val viewLarger = stringResource(R.string.image_view_larger)
                val description = picture.prompt.ifBlank { stringResource(R.string.image_generated) }
                Box(
                    Modifier
                        .size(CONSOLE_IMAGE_SIZE)
                        .clip(RoundedCornerShape(Dimens.radiusMd))
                        .background(colors.panel)
                        .clickable(onClickLabel = viewLarger) { viewing = true },
                ) {
                    bitmap?.let {
                        Image(it, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
        }
        if (picture.prompt.isNotBlank()) {
            Text(picture.prompt, style = ConsoleType.meta, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    val description = caption.ifBlank { stringResource(R.string.image_generated) }
    val close = stringResource(R.string.common_close)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .pointerInput(reference) { detectTapGestures(onTap = { onDismiss() }) },
        ) {
            bitmap?.let { ZoomableImage(it, description, onDismiss) }
            if (caption.isNotBlank()) {
                Text(
                    caption,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(Dimens.spaceLg),
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(Dimens.spaceMd)) {
                Icon(Icons.Filled.Close, contentDescription = close, tint = Color.White)
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
