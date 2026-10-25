package com.readygym.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.readygym.core.exercise.ExerciseLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 器械指南: photograph the machine in front of you, and the AI names it.
 *
 * With a bundled vision key ([vision]), the photo — downscaled and re-encoded on the phone — is
 * sent to the model once per capture, the reply is matched against the catalog and the matching
 * guide is filtered to and expanded. A miss or a network failure never blocks the feature: the
 * manual list stays right there. Without a key, or after a failure, the photo is a local reference
 * only. Either way the photo never leaves the phone except for that one recognition call, and the
 * guide itself is bundled content that works offline.
 *
 * [mediaBaseUrl] is the ReadyGym server; when reachable, each station also plays its demonstration
 * GIF (ExerciseGymGifsDB, served at `/gifs/<path>`). Without a server the text steps stand alone.
 */
@Composable
fun EquipmentGuideScreen(library: ExerciseLibrary, mediaBaseUrl: String? = null, vision: DeepSeekApi? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    var photoUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCaptureUri by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<EquipmentCategory?>(null) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var recognition by remember { mutableStateOf<Recognition?>(null) }
    val scope = rememberCoroutineScope()

    fun recognizePhoto(uri: Uri) {
        val api = vision ?: return
        recognition = Recognition.Running
        scope.launch(Dispatchers.IO) {
            val next = try {
                val compressed = compressForRecognition(context, uri)
                val reply = compressed?.let { (b64, mime) ->
                    api.recognizeImage(EquipmentGuideCatalog.recognitionPrompt(), b64, mime)
                }
                val item = reply?.let { EquipmentGuideCatalog.matchEquipment(it) }
                when {
                    item != null -> {
                        query = item.name
                        expandedId = item.id
                        Recognition.Matched(item)
                    }
                    else -> reply?.let { EquipmentGuideCatalog.matchExtraMachine(it) }
                        ?.let { Recognition.Extra(it) }
                        ?: Recognition.NotFound
                }
            } catch (_: Exception) {
                Recognition.Failed
            }
            recognition = next
        }
    }

    fun newCaptureUri(): Uri {
        val dir = File(context.cacheDir, "equipment-guide").apply { mkdirs() }
        return FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider",
            File(dir, "photo-${System.currentTimeMillis()}.jpg"))
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            val path = pendingCaptureUri
            pendingCaptureUri = null
            if (path != null) {
                photoUri = path
                recognizePhoto(Uri.parse(path))
            }
        } else pendingCaptureUri = null
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pendingCaptureUri = null
        photoUri = uri?.toString()
        if (uri != null) recognizePhoto(uri)
    }

    val items = EquipmentGuideCatalog.search(query, category)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = GymSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
    ) {
        Spacer(Modifier.height(GymSpacing.Md))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("器械指南", fontSize = 29.sp, lineHeight = 35.sp, fontWeight = FontWeight.ExtraBold,
                    color = ReadyGymColors.Text, modifier = Modifier.semantics { heading() })
                Text("拍下眼前这台，对照步骤学怎么用", fontSize = 14.sp, color = ReadyGymColors.MutedText)
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(ReadyGymColors.Sunken)
                    .clickable(role = Role.Button, onClick = onClose).testTag("equipment-close")
                    .semantics { contentDescription = "关闭器械指南" },
                contentAlignment = Alignment.Center,
            ) { Icon(GymIcons.Close, null, Modifier.size(18.dp), tint = ReadyGymColors.Text) }
        }

        Panel {
            Text("拍器械，AI 帮你认", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                if (vision != null) "拍一张眼前的器械，AI 自动认出是哪台并展开教程。识别时照片会发送给 AI；照片本身只存在这台手机上。"
                else "照片只存在这台手机上，用来和下面的说明对照。拍完从列表里选一下是哪台器械即可。",
                fontSize = 12.sp, lineHeight = 18.sp, color = ReadyGymColors.MutedText,
            )
            photoUri?.let { path ->
                AsyncImage(
                    model = Uri.parse(path),
                    contentDescription = "你拍摄的器械照片（仅本机保存）",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                        .clip(GymShapes.Md).testTag("equipment-photo"),
                )
            }
            when (val r = recognition) {
                Recognition.Running -> Row(Modifier.testTag("equipment-recognizing"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("AI 正在认这台器械…", fontSize = 13.sp, color = ReadyGymColors.MutedText)
                }
                is Recognition.Matched -> Text(
                    "AI 识别为「${r.item.name}」，已展开教程；认错了就清空搜索从列表改选。",
                    fontSize = 13.sp, lineHeight = 19.sp, color = ReadyGymColors.AccentText,
                    modifier = Modifier.testTag("equipment-recognized-${r.item.id}"))
                is Recognition.Extra -> {
                    Panel(Modifier.fillMaxWidth().testTag("equipment-extra")) {
                        Text(
                            "这台是「${r.machine.label}」，指南还没有它的专门教程；可以用它做这些动作：",
                            fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
                        )
                        r.machine.exerciseIds.mapNotNull { library[it] }.forEach { ex ->
                            Row(Modifier.padding(top = GymSpacing.Sm), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(GymSpacing.Md)) {
                                ExerciseImage(library[ex.id], animate = true,
                                    modifier = Modifier.size(72.dp).testTag("equipment-extra-gif-${ex.id}"))
                                Text(ex.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                Recognition.NotFound -> Text(
                    "AI 没认出这台器械（不在指南范围或看不清），从下面列表手动选一下即可。",
                    fontSize = 13.sp, lineHeight = 19.sp, color = ReadyGymColors.MutedText,
                    modifier = Modifier.testTag("equipment-recognize-miss"))
                Recognition.Failed -> Text(
                    "识别没连上（网络原因），照片已保留；从下面列表手动选，或稍后再拍一张。",
                    fontSize = 13.sp, lineHeight = 19.sp, color = ReadyGymColors.MutedText,
                    modifier = Modifier.testTag("equipment-recognize-failed"))
                null -> Unit
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
                Box(Modifier.weight(1f)) {
                    PrimaryButton("拍一张", {
                        val uri = newCaptureUri()
                        pendingCaptureUri = uri.toString()
                        takePicture.launch(uri)
                    }, Modifier.testTag("equipment-capture"))
                }
                Box(Modifier.weight(1f)) {
                    SecondaryButton("从相册选", {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, Modifier.testTag("equipment-pick"))
                }
            }
        }

        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().testTag("equipment-search"),
            singleLine = true, label = { Text("搜器械名称，如下拉、倒蹬、跑步机") },
        )

        ChoiceRow(listOf<EquipmentCategory?>(null) + EquipmentCategory.entries.toList(),
            label = { it?.label ?: "全部" }, isSelected = { it == category },
            onPick = { category = it })

        if (items.isEmpty()) {
            Notice("没有匹配「${query.trim()}」的器械；换个叫法试试，比如「腿举」或「划船」。")
        }
        items.forEach { item ->
            EquipmentCard(item, library, mediaBaseUrl, expanded = expandedId == item.id,
                onToggle = { expandedId = if (expandedId == item.id) null else item.id })
        }
        Text(
            "器械说明为本地整理的通用做法，不同品牌机型会有差异，以器械上的图示和健身房工作人员指导为准；有伤病请先咨询专业人士。",
            fontSize = 12.sp, lineHeight = 18.sp, color = ReadyGymColors.MutedText,
            modifier = Modifier.testTag("equipment-footnote"),
        )
        Spacer(Modifier.height(GymSpacing.Xxl))
    }
}

/** One collapsible guide: collapsed shows name and tags, expanded shows the full how-to. */
@Composable
private fun EquipmentCard(item: EquipmentGuideItem, library: ExerciseLibrary, mediaBaseUrl: String?, expanded: Boolean, onToggle: () -> Unit) {
    Panel(Modifier.fillMaxWidth().testTag("equipment-item-${item.id}")) {
        Row(Modifier.fillMaxWidth().clip(GymShapes.Md).clickable(role = Role.Button, onClick = onToggle)
            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("equipment-name-${item.id}"))
                Row(horizontalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
                    Tag(item.category.label, ReadyGymColors.AccentWash, ReadyGymColors.AccentText)
                    if (item.beginnerFriendly) Tag("新手可用", ReadyGymColors.AccentWash, ReadyGymColors.AccentText)
                    else Tag("需教练指导", ReadyGymColors.WarningSurface, ReadyGymColors.Warning)
                }
            }
            Text(if (expanded) "收起" else "怎么看用", fontSize = 13.sp, color = ReadyGymColors.AccentText,
                fontWeight = FontWeight.Bold, modifier = Modifier.testTag("equipment-toggle-${item.id}"))
        }

        if (expanded) {
            item.beginnerNote?.let { Notice(it, warning = true) }
            when {
                item.demoGif != null && !mediaBaseUrl.isNullOrBlank() ->
                    EquipmentGifDemo(url = "${mediaBaseUrl.trimEnd('/')}/gifs/${item.demoGif}", title = item.name)
                item.demoGif != null -> Text(
                    "这台器械配有动作演示 GIF，连接 ReadyGym 服务器后可以加载查看；下面的文字步骤现在就能用。",
                    fontSize = 13.sp, lineHeight = 19.sp, color = ReadyGymColors.MutedText,
                    modifier = Modifier.testTag("equipment-demo-offline-${item.id}"),
                )
                else -> Unit
            }
            NumberedSection("先调整", item.setupSteps)
            NumberedSection("跟着做", item.usageSteps)
            BulletSection("安全要点", item.safety)
            BulletSection("常见错误", item.mistakes)
            if (item.relatedExerciseIds.isNotEmpty()) {
                Text("计划里的相关动作", style = MaterialTheme.typography.titleMedium)
                Text(
                    item.relatedExerciseIds.mapNotNull { library[it]?.name }.joinToString("、").ifBlank { "—" },
                    fontSize = 14.sp, lineHeight = 20.sp,
                    modifier = Modifier.testTag("equipment-related-${item.id}"),
                )
            }
        }
    }
}

/**
 * The station's demonstration GIF, streamed from the ReadyGym server and played through the shared
 * Coil GIF loader. The dataset canvas is square (360×360). A failed load says so and offers one
 * retry rather than silently showing nothing; the text steps below remain usable either way.
 */
@Composable
private fun EquipmentGifDemo(url: String, title: String) {
    val context = LocalContext.current
    var retry by remember(url) { mutableStateOf(0) }
    Text("动作演示", style = MaterialTheme.typography.titleMedium)
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context).data(url)
            .memoryCacheKey("$url#$retry")
            .setParameter("retry", retry)
            .build(),
        imageLoader = gifLoader(context),
        contentDescription = "${title}器械动作演示GIF",
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
            .clip(GymShapes.Md).background(ReadyGymColors.Surface).testTag("equipment-demo-gif"),
        loading = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
            }
        },
        error = {
            Column(Modifier.fillMaxSize().padding(GymSpacing.Md), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GymSpacing.Sm, Alignment.CenterVertically)) {
                Text("演示加载失败，检查网络后重试；下面的文字步骤不受影响。",
                    fontSize = 13.sp, lineHeight = 19.sp, color = ReadyGymColors.MutedText)
                InlineAction("重试", { retry++ }, Modifier.testTag("equipment-demo-retry"))
            }
        },
    )
}

@Composable
private fun NumberedSection(title: String, steps: List<String>) {
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.Text)
    steps.forEachIndexed { i, step -> Text("${i + 1}. $step", fontSize = 14.sp, lineHeight = 21.sp) }
}

@Composable
private fun BulletSection(title: String, bullets: List<String>) {
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.Text)
    bullets.forEach { Text("• $it", fontSize = 14.sp, lineHeight = 21.sp) }
}

@Composable
private fun Tag(label: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Box(Modifier.clip(GymShapes.Sm).background(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(label, fontSize = 11.sp, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

/** Photo → machine recognition progress, shown under the photo. */
private sealed interface Recognition {
    data object Running : Recognition
    data class Matched(val item: EquipmentGuideItem) : Recognition
    /** Not one of the 16 guides, but the model named it and the library has exercises for it. */
    data class Extra(val machine: EquipmentGuideCatalog.ExtraMachine) : Recognition
    data object NotFound : Recognition
    data object Failed : Recognition
}

/**
 * Downscale to ≤1280 px and re-encode as JPEG 70. Camera and gallery originals run into multiple
 * megabytes; the vision call needs about a tenth of that and mobile uplinks are the slow part.
 * Re-encoding also normalizes HEIC/PNG gallery picks to the mime announced in the data URL.
 */
private fun compressForRecognition(context: android.content.Context, uri: Uri): Pair<String, String>? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
    bitmap.recycle()
    return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) to "image/jpeg"
}
