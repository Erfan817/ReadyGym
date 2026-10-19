package com.readygym.app

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.readygym.core.exercise.Exercise

/**
 * Demonstration image for an exercise, read from the bundled assets.
 *
 * [animate] = false shows only the first frame, which keeps long plan lists cheap; the workout
 * screen animates the one exercise in focus. GIFs are gitignored, so a fresh checkout without the
 * build script still gets a readable placeholder instead of a blank box.
 */
@Composable
internal fun ExerciseImage(exercise: Exercise?, animate: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var failed by remember(exercise?.id) { mutableStateOf(exercise?.gifAsset == null) }
    Box(modifier.clip(GymShapes.Md).background(if (failed || exercise == null) ReadyGymColors.Sunken else ReadyGymColors.Surface), contentAlignment = Alignment.Center) {
        if (failed || exercise == null) {
            Text(exercise?.name?.take(2) ?: "?", color = ReadyGymColors.FaintText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context).data("file:///android_asset/${exercise.gifAsset}")
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

private var gif: ImageLoader? = null
private var still: ImageLoader? = null

private fun gifLoader(context: Context): ImageLoader = gif ?: ImageLoader.Builder(context.applicationContext)
    .components { add(if (Build.VERSION.SDK_INT >= 28) ImageDecoderDecoder.Factory() else GifDecoder.Factory()) }
    .build().also { gif = it }

/** No GIF decoder registered, so Coil decodes the first frame as a bitmap. */
private fun stillLoader(context: Context): ImageLoader = still ?: ImageLoader.Builder(context.applicationContext)
    .build().also { still = it }
