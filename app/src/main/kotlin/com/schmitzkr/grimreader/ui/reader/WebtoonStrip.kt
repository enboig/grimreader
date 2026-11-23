package com.schmitzkr.grimreader.ui.reader

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.size.Dimension
import coil3.size.Size
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Webtoon mode: every page of a comic stacked into one continuous vertical
 * strip, each scaled to the screen width with no gap between them, the way
 * long-strip comics are drawn to be read.
 *
 * The page on screen is the topmost visible one, except once the strip can
 * scroll no further, when it is the last: a last page shorter than the
 * screen never reaches the top, and the book could not be finished
 * otherwise. Taps follow the paged reader (left edge back, right edge
 * forward, middle toggles the chrome), and like volume keys an edge tap
 * scrolls most of a screen rather than a whole page, which may be many
 * screens tall.
 *
 * Zoom applies to the whole strip. While zoomed, a horizontal drag pans
 * and a vertical drag first pans to the zoomed view's own edge and then
 * scrolls the strip, so reading on downwards never needs zooming out.
 */
@Composable
fun WebtoonStrip(
    urls: List<String>,
    initialIndex: Int,
    jumpTo: Int?,
    onJumped: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    volumeKeys: VolumeKeyPager,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex.coerceIn(0, (urls.size - 1).coerceAtLeast(0)))
    val scope = rememberCoroutineScope()
    val pageChanged by rememberUpdatedState(onPageChanged)
    val toggleChrome by rememberUpdatedState(onToggleChrome)
    // Width / height of each page once it has loaded, so a page scrolled away
    // and back keeps its height and the strip never jumps under the reader.
    val ratios = remember(urls) { mutableStateMapOf<Int, Float>() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(list, urls.size) {
        // Nothing is reported before the first layout: canScrollForward reads false
        // until then, which would claim the last page and save it as the place.
        snapshotFlow {
            if (list.layoutInfo.visibleItemsInfo.isEmpty()) null
            else stripCurrentPage(list.firstVisibleItemIndex, list.canScrollForward, urls.size)
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { pageChanged(it) }
    }
    LaunchedEffect(jumpTo) {
        val target = jumpTo ?: return@LaunchedEffect
        list.scrollToItem(target.coerceIn(0, (urls.size - 1).coerceAtLeast(0)))
        onJumped()
    }

    fun step(forward: Boolean) {
        val distance = viewport.height * STRIP_STEP / scale
        scope.launch { list.animateScrollBy(if (forward) distance else -distance) }
    }
    VolumeKeyPaging(volumeKeys) { step(it) }

    fun maxX(s: Float) = viewport.width * (s - 1) / 2
    fun maxY(s: Float) = viewport.height * (s - 1) / 2

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap ->
                        val x = tap.x / viewport.width.toFloat().coerceAtLeast(1f)
                        when {
                            scale.isZoomed -> toggleChrome()
                            x < 0.3f -> step(false)
                            x > 0.7f -> step(true)
                            else -> toggleChrome()
                        }
                    },
                    onDoubleTap = { tap ->
                        if (scale.isZoomed) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            val centre = Offset(viewport.width / 2f, viewport.height / 2f)
                            val o = (centre - tap) * (scale - 1)
                            offset = Offset(o.x.coerceIn(-maxX(scale), maxX(scale)), o.y.coerceIn(-maxY(scale), maxY(scale)))
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // Taken on the initial pass, ahead of the strip's own scrolling:
                // a pinch must not also scroll it, and while zoomed every drag
                // is ours. An unzoomed single-finger drag is left to the strip.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed > 1 || scale.isZoomed) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            if (newScale == 1f) {
                                offset = Offset.Zero
                                // Unzoomed but two fingers down: their drag still reads on.
                                if (pan.y != 0f) list.dispatchRawDelta(-pan.y)
                            } else {
                                val x = (offset.x + pan.x).coerceIn(-maxX(newScale), maxX(newScale))
                                val wantY = offset.y + pan.y
                                val y = wantY.coerceIn(-maxY(newScale), maxY(newScale))
                                // What is left past the zoomed view's edge scrolls the strip;
                                // the strip is drawn scaled, so a screen pixel is 1/scale of it.
                                val overflow = wantY - y
                                if (overflow != 0f) list.dispatchRawDelta(-overflow / newScale)
                                offset = Offset(x, y)
                            }
                            scale = newScale
                            if (zoom != 1f || pan != Offset.Zero) {
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        if (viewport.width == 0 || viewport.height == 0) return@Box
        val context = LocalPlatformContext.current
        val placeholderHeight = with(LocalDensity.current) { viewport.height.toDp() }
        val width = viewport.width
        LazyColumn(
            state = list,
            userScrollEnabled = !scale.isZoomed,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            items(urls.size, key = { it }) { index ->
                val url = urls[index]
                val request = remember(url, width) {
                    ImageRequest.Builder(context)
                        .data(url)
                        // Decoded to the screen width; strip pages are far taller than
                        // the default 4096 px cap, which would leave them blurred.
                        .size(Size(Dimension(width), Dimension.Undefined))
                        .maxBitmapSize(Size(STRIP_MAX_BITMAP, STRIP_MAX_BITMAP))
                        .build()
                }
                val ratio = ratios[index]
                AsyncImage(
                    model = request,
                    contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.FillWidth,
                    onSuccess = { s ->
                        val img = s.result.image
                        if (img.width > 0 && img.height > 0) ratios[index] = img.width.toFloat() / img.height
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.height(placeholderHeight)),
                )
            }
        }
    }
}

/** The strip's current page: the topmost visible one, or the last once the end is reached. */
fun stripCurrentPage(firstVisible: Int, canScrollForward: Boolean, count: Int): Int {
    if (count <= 0) return 0
    return if (canScrollForward) firstVisible.coerceIn(0, count - 1) else count - 1
}

/** How much of a screen an edge tap or volume key scrolls: a little overlap keeps the reader's place. */
private const val STRIP_STEP = 0.85f

/**
 * Largest decoded strip page side. At a 1440 px wide screen a 16384 px tall
 * page is ~94 MB, just inside what a hardware canvas will draw.
 */
private const val STRIP_MAX_BITMAP = 16384
