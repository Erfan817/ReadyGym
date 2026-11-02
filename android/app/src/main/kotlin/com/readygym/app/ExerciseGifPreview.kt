package com.readygym.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.readygym.core.exercise.Exercise

/** Plays the catalog's exact demonstration, using bundled media first. Never saves a plan. */
@Composable
internal fun ExerciseGifPreview(
    exercise: Exercise,
    modifier: Modifier = Modifier.fillMaxWidth().height(220.dp),
) {
    val context = LocalContext.current
    var attempt by remember(exercise.id) { mutableIntStateOf(0) }
    val source = exercise.gifAsset?.let { "file:///android_asset/$it" }
        ?: exercise.gifPath?.takeIf { BuildConfig.SERVICE_BASE_URL.isNotBlank() }
            ?.let { "${BuildConfig.SERVICE_BASE_URL.trimEnd('/')}/gifs/$it" }
    val frame = modifier.clip(GymShapes.Md).background(ReadyGymColors.Sunken)
    if (source == null) {
        Box(frame, contentAlignment = Alignment.Center) {
            Text("这个动作暂时没有演示，可以先看动作名称和说明。",
                modifier = Modifier.padding(16.dp), fontSize = 13.sp, color = ReadyGymColors.MutedText)
        }
        return
    }
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context).data(source)
            .memoryCacheKey("${exercise.id}:preview:$attempt").build(),
        imageLoader = gifLoader(context), contentDescription = "${exercise.name}动作演示",
        contentScale = ContentScale.Fit,
        modifier = frame,
        loading = {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                CircularProgressIndicator()
                Text("正在加载动作演示…", fontSize = 13.sp, color = ReadyGymColors.MutedText)
            }
        },
        error = {
            Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("演示暂时没加载出来", fontWeight = FontWeight.SemiBold)
                Text("可以先看动作说明，联网后重试。", fontSize = 13.sp, color = ReadyGymColors.MutedText)
                TextButton(onClick = { attempt++ }) { Text("重新加载") }
            }
        },
    )
}
