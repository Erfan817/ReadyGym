package com.readygym.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.readygym.core.MuscleGroup
import com.readygym.core.MuscleTaxonomy
import com.readygym.core.exercise.Exercise
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.exercise.MuscleExercisePicker
import com.readygym.core.exercise.label
import com.readygym.core.plan.PlanLimits
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Local muscle browsing; only the selected demonstration needs to animate. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MuscleExerciseScreen(
    library: ExerciseLibrary,
    profile: UserProfile?,
    plan: WorkoutPlan?,
    nextDay: Int = 0,
    onClose: () -> Unit,
    onCreateProfile: () -> Unit,
    onOpenPlan: () -> Unit,
    onAddExercise: suspend (String, Int, Int) -> String?,
    workoutInProgress: Boolean = false,
) {
    var selected by rememberSaveable { mutableStateOf<MuscleGroup?>(null) }
    var showNames by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var success by rememberSaveable { mutableStateOf<String?>(null) }
    val recommendations = remember(library, profile, selected) {
        selected?.let { MuscleExercisePicker.recommend(library, profile, it) }.orEmpty()
    }
    fun select(muscle: MuscleGroup) { selected = muscle }

    Surface(Modifier.fillMaxSize(), color = ReadyGymColors.Background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = GymSpacing.Lg, vertical = GymSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(ReadyGymColors.Surface)
                    .clickable(role = Role.Button, onClickLabel = "返回计划", onClick = onClose)
                    .testTag("muscle-picker-close"), contentAlignment = Alignment.Center) {
                    Icon(GymIcons.ChevronLeft, "返回计划", Modifier.size(20.dp))
                }
                Spacer(Modifier.width(GymSpacing.Md))
                Column {
                    Text("探索肌肉", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.semantics { heading() })
                    Text("选一块肌肉，看看可以怎么练", fontSize = 13.sp, color = ReadyGymColors.MutedText)
                }
            }
            success?.let { message ->
                Row(Modifier.fillMaxWidth().padding(horizontal = GymSpacing.Lg)
                    .clip(GymShapes.Md).background(ReadyGymColors.AccentWash)
                    .testTag("muscle-add-success").semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(start = GymSpacing.Md), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = ReadyGymColors.AccentText, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenPlan) { Text("查看计划", fontSize = 12.sp) }
                }
            }
            LazyColumn(
                Modifier.fillMaxSize().testTag("muscle-picker-list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = GymSpacing.Lg, end = GymSpacing.Lg, top = GymSpacing.Sm, bottom = GymSpacing.Xl),
                verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
            ) {
                item("body") {
                    Panel {
                        MuscleBodyMap(selected, ::select, Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(ReadyGymColors.Accent))
                            Spacer(Modifier.width(7.dp))
                            Text(selected?.let { "已选：${it.label}" } ?: "点选正面或背面的肌肉",
                                fontSize = 12.sp, color = ReadyGymColors.MutedText, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showNames = !showNames },
                                modifier = Modifier.testTag("muscle-name-toggle")) {
                                Text(if (showNames) "收起名称" else "按名称选", fontSize = 12.sp)
                            }
                        }
                        if (showNames) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            MuscleTaxonomy.DRAWABLE.forEach { muscle ->
                                Chip(muscle.label, selected == muscle, { select(muscle) },
                                    modifier = Modifier.testTag("muscle-chip-${muscle.name}"))
                            }
                        }
                    }
                }
                item("recommendation-title") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(selected?.label?.let { "$it · 推荐动作" } ?: "想练哪里，就点哪里",
                                fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (selected != null) Text("${recommendations.size} 个", fontSize = 13.sp,
                                color = ReadyGymColors.MutedText)
                        }
                        Text(if (profile == null) "先看看入门动作，建好档案后可加入计划。"
                            else "已按你的场地、器械和训练经验筛选，点动作看演示。",
                            fontSize = 12.sp, lineHeight = 18.sp, color = ReadyGymColors.MutedText)
                    }
                }
                if (selected != null && recommendations.isEmpty()) item("empty") {
                    Panel {
                        Text("暂时没有适合你的动作", fontWeight = FontWeight.SemiBold)
                        Text("可以换一块肌肉看看，或回「我的」更新可用器械。", fontSize = 14.sp,
                            color = ReadyGymColors.MutedText)
                    }
                }
                items(recommendations, key = { it.id }) { exercise ->
                    Panel(Modifier.testTag("muscle-exercise-${exercise.id}")
                        .clickable(role = Role.Button, onClickLabel = "查看${exercise.name}演示") {
                            detailId = exercise.id
                        }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ExerciseImage(exercise, animate = false, Modifier.size(76.dp))
                            Spacer(Modifier.width(GymSpacing.Md))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(exercise.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("${exercise.equipment.label} · ${exercise.level.label}", fontSize = 12.sp,
                                    color = ReadyGymColors.MutedText)
                                Text(if (exercise.primary == selected) "主要练${exercise.primary.label}"
                                    else "辅助练${selected?.label.orEmpty()}", fontSize = 12.sp,
                                    color = ReadyGymColors.AccentText)
                            }
                            Icon(GymIcons.ChevronRight, null, Modifier.size(16.dp), ReadyGymColors.FaintText)
                        }
                    }
                }
            }
        }
    }
    detailId?.let(library::get)?.let { exercise ->
        MuscleExerciseDetail(exercise, profile, plan, nextDay, workoutInProgress,
            onDismiss = { detailId = null }, onCreateProfile = onCreateProfile, onOpenPlan = onOpenPlan,
            onAddExercise = onAddExercise,
            onAdded = { day -> success = "${exercise.name}已加入第 ${day + 1} 天"; detailId = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MuscleExerciseDetail(
    exercise: Exercise,
    profile: UserProfile?,
    plan: WorkoutPlan?,
    nextDay: Int,
    workoutInProgress: Boolean,
    onDismiss: () -> Unit,
    onCreateProfile: () -> Unit,
    onOpenPlan: () -> Unit,
    onAddExercise: suspend (String, Int, Int) -> String?,
    onAdded: (Int) -> Unit,
) {
    fun eligible(index: Int): Boolean = plan?.days?.getOrNull(index)?.let { day ->
        day.items.size < PlanLimits.ITEMS_PER_DAY.last && day.items.none { it.exerciseId == exercise.id }
    } == true
    var dayIndex by rememberSaveable(exercise.id, plan?.version) {
        val indices = plan?.days?.indices?.toList().orEmpty()
        val first = indices.sortedBy { (it - nextDay + indices.size) % indices.size }
            .firstOrNull(::eligible) ?: 0
        mutableIntStateOf(first)
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !busy })
    BackHandler(busy) { }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheetState,
        containerColor = ReadyGymColors.Surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
            .testTag("muscle-detail-scroll").verticalScroll(rememberScrollState())
            .padding(horizontal = GymSpacing.Lg).padding(bottom = GymSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(GymSpacing.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(exercise.name, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
            }
            Text("${exercise.primary.label} · ${exercise.equipment.label} · ${exercise.level.label}",
                fontSize = 13.sp, color = ReadyGymColors.MutedText)
            MuscleGifPreview(exercise)
            if (exercise.tips.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("怎么练", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                exercise.tips.forEachIndexed { i, tip ->
                    Text("${i + 1}. $tip", fontSize = 14.sp, lineHeight = 21.sp, color = ReadyGymColors.MutedText)
                }
            }
            when {
                profile == null -> PrimaryButton("先建档案，再加入计划", onCreateProfile,
                    Modifier.testTag("muscle-create-profile"))
                plan == null -> {
                    Text("先生成并确认一份计划，再把喜欢的动作加进去。", fontSize = 14.sp,
                        color = ReadyGymColors.MutedText)
                    PrimaryButton("去生成计划", onOpenPlan, Modifier.testTag("muscle-open-plan"))
                }
                else -> {
                    Text("加入哪一天？", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    plan.days.forEachIndexed { index, day ->
                        val duplicate = day.items.any { it.exerciseId == exercise.id }
                        val full = day.items.size >= PlanLimits.ITEMS_PER_DAY.last
                        val enabled = !duplicate && !full
                        Row(Modifier.fillMaxWidth().clip(GymShapes.Md)
                            .background(if (dayIndex == index && enabled) ReadyGymColors.AccentWash else ReadyGymColors.Sunken)
                            .selectable(dayIndex == index && enabled, enabled = enabled && !busy,
                                role = Role.RadioButton, onClick = { dayIndex = index; error = null })
                            .testTag("muscle-day-$index").padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("第 ${index + 1} 天 · ${day.title}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(when { duplicate -> "这一天已有这个动作"; full -> "已满 8 个动作，可选其他天"
                                    else -> "${day.items.size} 个动作" }, fontSize = 12.sp, color = ReadyGymColors.MutedText)
                            }
                            if (dayIndex == index && enabled) Text("已选", fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, color = ReadyGymColors.AccentText)
                        }
                    }
                    val item = MuscleExercisePicker.defaultItem(exercise)
                    Text("加入后：${item.target}，组间休息 ${item.restSeconds} 秒。先用轻松可控的强度。",
                        fontSize = 13.sp, lineHeight = 19.sp, color = ReadyGymColors.MutedText)
                    if (workoutInProgress) Text("正在练的这次保持原安排，新动作会在下次训练时出现。",
                        fontSize = 13.sp, lineHeight = 19.sp, color = ReadyGymColors.AccentText)
                    error?.let { Notice(it) }
                    PrimaryButton(if (busy) "正在加入…" else "加入训练计划", {
                        if (!busy) {
                            val targetDay = dayIndex
                            val version = plan.version
                            busy = true; error = null
                            scope.launch {
                                try {
                                    val failure = onAddExercise(exercise.id, targetDay, version)
                                    if (failure == null) onAdded(targetDay) else error = failure
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error = "这次没能保存，先回计划查看，再试一次。"
                                } finally { busy = false }
                            }
                        }
                    }, Modifier.testTag("muscle-add-confirm"), enabled = eligible(dayIndex), busy = busy)
                    if (plan.days.indices.none(::eligible)) Text("每一天都已包含这个动作或已满，先回计划查看。",
                        fontSize = 13.sp, color = ReadyGymColors.MutedText)
                }
            }
        }
    }
}

@Composable
private fun MuscleGifPreview(exercise: Exercise) {
    val context = LocalContext.current
    var attempt by remember(exercise.id) { mutableIntStateOf(0) }
    val source = exercise.gifAsset?.let { "file:///android_asset/$it" }
        ?: exercise.gifPath?.takeIf { BuildConfig.SERVICE_BASE_URL.isNotBlank() }
            ?.let { "${BuildConfig.SERVICE_BASE_URL.trimEnd('/')}/gifs/$it" }
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context).data(source)
            .memoryCacheKey("${exercise.id}:muscle:$attempt").build(),
        imageLoader = gifLoader(context), contentDescription = "${exercise.name}动作演示",
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().height(220.dp).clip(GymShapes.Md).background(ReadyGymColors.Sunken),
        loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
        error = {
            Column(Modifier.fillMaxSize().padding(GymSpacing.Lg), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("演示暂时没加载出来", fontWeight = FontWeight.SemiBold)
                Text("可以先看下方动作要点，联网后重试。", fontSize = 13.sp, color = ReadyGymColors.MutedText)
                TextButton(onClick = { attempt++ }) { Text("重新加载") }
            }
        },
    )
}
