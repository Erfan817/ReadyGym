package com.readygym.app

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateTo
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.readygym.core.exercise.Exercise
import kotlinx.coroutines.launch

/**
 * Demonstration image for an exercise. The original 60 exercises ship their GIF in the APK
 * ([Exercise.gifAsset]); library-v2 exercises stream it from the ReadyGym server
 * (`SERVICE_BASE_URL/gifs/<gifPath>`), so a 287-exercise library costs almost no APK size.
 * With neither source available (or no server reachable) the row shows the exercise name.
 *
 * [animate] = false shows only the first frame, which keeps long plan lists cheap; the workout
 * screen animates the one exercise in focus.
 */
@Composable
internal fun ExerciseImage(exercise: Exercise?, animate: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var failed by remember(exercise?.id) { mutableStateOf(exercise?.let { it.gifAsset == null && it.gifPath == null } ?: true) }
    var model by remember(exercise?.id) { mutableStateOf<Any?>(null) }
    LaunchedEffect(exercise?.id, animate) {
        model = exercise?.let { ex ->
            when {
                ex.gifAsset != null -> "file:///android_asset/${ex.gifAsset}"
                ex.gifPath != null && BuildConfig.SERVICE_BASE_URL.isNotBlank() ->
                    "${BuildConfig.SERVICE_BASE_URL.trimEnd('/')}/gifs/${ex.gifPath}"
                else -> null
            }
        } ?: run { failed = true; null }
    }
    Box(modifier.clip(GymShapes.Md).background(if (failed || exercise == null || model == null) ReadyGymColors.Sunken else ReadyGymColors.Surface), contentAlignment = Alignment.Center) {
        if (failed || exercise == null || model == null) {
            Text(exercise?.name?.take(2) ?: "?", color = ReadyGymColors.FaintText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context).data(model)
                    .memoryCacheKey("${exercise.id}:$animate").build(),
                imageLoader = if (animate) gifLoader(context) else stillLoader(context),
                contentDescription = "${exercise.name}动作演示",
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * A press-feedback wrapper for list rows: scales the row down to 0.97 while held, on
 * [SunnyMotion.springSnappy], then back. The gesture and the scale both live on
 * `graphicsLayer`, so a long GIF list can scroll and be tapped without a single relayout.
 *
 * The scale is not applied when motion is reduced — the press still works, it just does not move.
 */
@Composable
internal fun PressScale(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val motion = LocalMotionSettings.current
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    fun to(target: Float) {
        if (motion.reduceMotion) return
        scope.launch { scale.animateTo(target, animationSpec = SunnyMotion.springSnappy()) }
    }
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .pointerInput(onClick, motion.reduceMotion) {
                if (onClick == null) return@pointerInput
                detectTapGestures(
                    onPress = {
                        to(0.97f)
                        tryAwaitRelease()
                        to(1f)
                    },
                    onTap = { onClick() },
                )
            },
    ) { content() }
}

/**
 * Decodes a GIF into memory ahead of time, so the frame it becomes visible is already a cache hit.
 *
 * Called for a small window around the viewport (see `preloadWindow`), never for the whole list: a
 * GIF is decoded on a background dispatcher and its first frame stays in the memory cache. Works
 * for both bundled assets and server-hosted GIFs (same model resolution as the composable).
 */
internal fun preloadExerciseGif(context: Context, exercise: Exercise?) {
    val model = exercise?.let { ex ->
        when {
            ex.gifAsset != null -> "file:///android_asset/${ex.gifAsset}"
            ex.gifPath != null && BuildConfig.SERVICE_BASE_URL.isNotBlank() ->
                "${BuildConfig.SERVICE_BASE_URL.trimEnd('/')}/gifs/${ex.gifPath}"
            else -> null
        }
    } ?: return
    val request = ImageRequest.Builder(context.applicationContext)
        .data(model)
        .memoryCacheKey("${exercise.id}:true")
        .build()
    gifLoader(context).enqueue(request)
}

/**
 * The indices worth preloading around [firstVisible] for a list of [size]: the visible window plus
 * [radius] items on each side. Pure, so the "±2" rule is testable without a running list.
 *
 * A viewport entirely past either end (a stale scroll position after a data refresh) yields the
 * nearest valid window rather than an empty or inverted range.
 */
internal fun preloadWindow(firstVisible: Int, lastVisible: Int, size: Int, radius: Int = 2): IntRange {
    if (size <= 0) return IntRange.EMPTY
    val last = size - 1
    val first = firstVisible.coerceIn(0, last)
    val end = lastVisible.coerceIn(0, last)
    val start = (minOf(first, end) - radius).coerceAtLeast(0)
    val stop = (maxOf(first, end) + radius).coerceAtMost(last)
    return start..stop
}

private var gif: ImageLoader? = null
private var still: ImageLoader? = null

/** Shared animated-GIF loader: the workout list and the equipment guide both play through it. */
internal fun gifLoader(context: Context): ImageLoader = gif ?: ImageLoader.Builder(context.applicationContext)
    .components { add(if (Build.VERSION.SDK_INT >= 28) ImageDecoderDecoder.Factory() else GifDecoder.Factory()) }
    .build().also { gif = it }

/** No GIF decoder registered, so Coil decodes the first frame as a bitmap. */
private fun stillLoader(context: Context): ImageLoader = still ?: ImageLoader.Builder(context.applicationContext)
    .build().also { still = it }
