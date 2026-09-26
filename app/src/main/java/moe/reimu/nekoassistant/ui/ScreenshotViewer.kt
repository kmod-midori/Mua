package moe.reimu.nekoassistant.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.reimu.nekoassistant.ai.ChatMessage

@Composable
private fun savedScreenshot(name: String?, thumbnail: Boolean = false): Bitmap? {
    val directory = File(LocalContext.current.filesDir, "conversation-screenshots")
    val bitmap by produceState<Bitmap?>(null, name, thumbnail) {
        value = null
        value = withContext(Dispatchers.IO) {
            name?.let {
                val path = File(directory, it).absolutePath
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                if (thumbnail) {
                    options.inJustDecodeBounds = true
                    BitmapFactory.decodeFile(path, options)
                    options.inJustDecodeBounds = false
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1024) {
                        options.inSampleSize *= 2
                    }
                }
                BitmapFactory.decodeFile(path, options)
            }
        }
    }
    return bitmap
}

@Composable
fun ScreenshotMessage(message: ChatMessage) {
    var expanded by remember { mutableStateOf(false) }
    val thumbnail = savedScreenshot(message.screenshotPath, thumbnail = true)
    val imageWidth = message.imageWidth ?: thumbnail?.width ?: 1
    val imageHeight = message.imageHeight ?: thumbnail?.height ?: 1
    val aspectRatio = imageWidth.coerceAtLeast(1).toFloat() / imageHeight.coerceAtLeast(1)
    val previewWidth = min(280f, 280f * aspectRatio).dp
    OutlinedCard(
        onClick = { expanded = true },
        modifier = Modifier.width(previewWidth + 24.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message.text, style = MaterialTheme.typography.labelLarge)
            if (thumbnail != null) {
                Image(thumbnail.asImageBitmap(), "${message.text} screenshot",
                    modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio), contentScale = ContentScale.Fit)
            } else {
                Text("Screenshot unavailable", style = MaterialTheme.typography.bodySmall)
            }
            if (message.imageWidth != null && message.imageHeight != null) {
                Text("${message.imageWidth} × ${message.imageHeight}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (expanded) {
        ScreenshotViewer(savedScreenshot(message.screenshotPath), "Full-resolution ${message.text}") {
            expanded = false
        }
    }
}

@Composable
internal fun ScreenshotViewer(bitmap: Bitmap?, description: String, onDismiss: () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        var viewport by remember { mutableStateOf(IntSize.Zero) }
        var zoom by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var gestureZoomed by remember { mutableStateOf(false) }
        val dismissThreshold = with(LocalDensity.current) { 120.dp.toPx() }

        fun boundedOffset(value: Offset, scale: Float): Offset {
            val image = bitmap ?: return Offset.Zero
            val fit = min(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
            val maxX = ((image.width * fit * scale - viewport.width) / 2).coerceAtLeast(0f)
            val maxY = ((image.height * fit * scale - viewport.height) / 2).coerceAtLeast(0f)
            return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
        }

        val transform = rememberTransformableState { centroid, zoomChange, pan, _ ->
            val previousZoom = zoom
            gestureZoomed = gestureZoomed || previousZoom > 1f || zoomChange != 1f
            zoom = (zoom * zoomChange).coerceIn(1f, 6f)
            if (zoom > 1f) {
                if (previousZoom == 1f) offset = Offset.Zero
                val center = Offset(viewport.width / 2f, viewport.height / 2f)
                offset = boundedOffset(
                    (offset - (centroid - center)) * (zoom / previousZoom) + (centroid - center) + pan, zoom,
                )
            } else {
                // A gesture that zoomed must finish before drag-to-dismiss is allowed.
                offset = if (gestureZoomed) Offset.Zero
                    else Offset(0f, (offset.y + pan.y).coerceAtLeast(0f))
            }
        }
        LaunchedEffect(transform.isTransformInProgress) {
            if (!transform.isTransformInProgress) {
                if (zoom == 1f) {
                    if (!gestureZoomed && offset.y >= dismissThreshold) dismiss()
                    offset = Offset.Zero
                }
                gestureZoomed = false
            }
        }
        val displayedZoom by animateFloatAsState(zoom,
            animationSpec = if (transform.isTransformInProgress) snap() else spring(), label = "Image zoom")
        val displayedOffset by animateOffsetAsState(offset,
            animationSpec = if (transform.isTransformInProgress) snap() else spring(), label = "Image offset")
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }
                .pointerInput(bitmap, viewport) {
                    detectTapGestures(onDoubleTap = { tap ->
                        if (zoom > 1f) {
                            zoom = 1f
                            offset = Offset.Zero
                        } else {
                            zoom = 3f
                            val center = Offset(viewport.width / 2f, viewport.height / 2f)
                            offset = boundedOffset((center - tap) * (zoom - 1f), zoom)
                        }
                    })
                }
                .transformable(transform, canPan = { zoom > 1f || abs(it.y) > abs(it.x) })
            ) {
                bitmap?.let {
                    Image(it.asImageBitmap(), description, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = displayedZoom; scaleY = displayedZoom
                            translationX = displayedOffset.x; translationY = displayedOffset.y
                        })
                }
            }
        }
    }
}
