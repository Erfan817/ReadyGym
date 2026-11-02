package com.readygym.app

import android.graphics.Matrix
import android.graphics.Region
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.MuscleGroup
import com.readygym.core.exercise.label

/**
 * Two anatomical views share the same paths for drawing and touch hit testing. The semantic
 * targets only expose accessibility actions; ordinary touches still hit the actual muscle shape
 * rather than an overlapping rectangular target around a neighbouring muscle.
 */
@Composable
internal fun MuscleBodyMap(
    selected: MuscleGroup?,
    onSelect: (MuscleGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shapes = remember { muscleMapShapes() }
    val targets = remember(shapes) {
        // One focus stop per muscle, positioned on its largest visible region.
        shapes.filter { it.muscle != null }.groupBy { requireNotNull(it.muscle) }
            .mapValues { (_, regions) -> regions.maxBy { it.path.getBounds().let { b -> b.width * b.height } } }
    }
    Column(modifier.testTag("muscle-body-map")) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(MAP_WIDTH / MAP_HEIGHT)) {
            val unit = maxWidth / MAP_WIDTH
            Box(
                Modifier.fillMaxWidth().aspectRatio(MAP_WIDTH / MAP_HEIGHT)
                    .pointerInput(shapes, onSelect) {
                        detectTapGestures { point ->
                            val x = point.x * MAP_WIDTH / size.width
                            val y = point.y * MAP_HEIGHT / size.height
                            shapes.lastOrNull { it.muscle != null && it.hit.contains(x.toInt(), y.toInt()) }
                                ?.muscle?.let(onSelect)
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(MAP_WIDTH / MAP_HEIGHT)) {
                    scale(size.width / MAP_WIDTH, size.height / MAP_HEIGHT, pivot = Offset.Zero) {
                        shapes.forEach { shape ->
                            val active = shape.muscle != null && shape.muscle == selected
                            drawPath(shape.path, when {
                                active -> ReadyGymColors.Accent
                                shape.muscle == null -> Color(0xFFD0D0D4)
                                else -> Color(0xFFDEDEE2)
                            })
                            drawPath(shape.path, Color.White, style = Stroke(width = 1.8f))
                            if (active) drawPath(shape.path, ReadyGymColors.AccentText, style = Stroke(width = 0.8f))
                        }
                    }
                }
                targets.forEach { (muscle, shape) ->
                    val bounds = shape.path.getBounds()
                    Box(
                        Modifier.offset(unit * bounds.left, unit * bounds.top)
                            .size(unit * bounds.width, unit * bounds.height)
                            .testTag("muscle-map-${muscle.name.lowercase()}")
                            .semantics {
                                contentDescription = muscle.label
                                role = Role.Button
                                this.selected = muscle == selected
                                onClick("选择${muscle.label}") { onSelect(muscle); true }
                            },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("正面", Modifier.weight(1f), textAlign = TextAlign.Center,
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ReadyGymColors.MutedText)
            Text("背面", Modifier.weight(1f), textAlign = TextAlign.Center,
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ReadyGymColors.MutedText)
        }
    }
}

private const val MAP_WIDTH = 380f
private const val MAP_HEIGHT = 415f
private const val BODY_CENTRE = 90f

private data class MuscleMapShape(val muscle: MuscleGroup?, val path: Path, val hit: Region)

/** All geometry is local, original vector artwork; no image or network asset is needed. */
private fun muscleMapShapes(): List<MuscleMapShape> = buildList {
    fun shape(muscle: MuscleGroup?, svg: String, back: Boolean = false, mirror: Boolean = false) {
        val path = PathParser().parsePathString(svg).toPath().asAndroidPath()
        val matrix = Matrix()
        if (mirror) matrix.setScale(-1f, 1f, BODY_CENTRE, 0f)
        if (back) matrix.postTranslate(200f, 0f)
        path.transform(matrix)
        val clip = Region(0, 0, MAP_WIDTH.toInt(), MAP_HEIGHT.toInt())
        val region = Region().also { it.setPath(path, clip) }
        add(MuscleMapShape(muscle, path.asComposePath(), region))
    }
    fun pair(muscle: MuscleGroup?, svg: String, back: Boolean = false) {
        shape(muscle, svg, back)
        shape(muscle, svg, back, mirror = true)
    }

    // Neutral head, ears, neck, hands, knees and feet establish the silhouette without suggesting
    // that those body parts are selectable muscle groups.
    for (back in listOf(false, true)) {
        shape(null, "M90,5 C76,5 72,15 73,27 C73,39 79,47 90,48 C101,47 107,39 107,27 C108,15 104,5 90,5 Z", back)
        pair(null, "M74,26 C69,22 70,35 76,37 Z", back)
        pair(null, "M83,47 L89,48 L88,61 L74,62 C81,57 82,52 83,47 Z", back)
        pair(null, "M23,182 C16,180 12,187 13,193 L20,202 L27,199 L29,187 Z", back)
        pair(null, "M12,191 L5,201 L4,208 L7,207 L16,197 Z M17,199 L10,212 L10,217 L13,214 L21,202 Z M22,201 L18,215 L19,219 L22,216 L26,202 Z M27,199 L27,211 L30,215 L31,210 L31,198 Z M12,184 L5,191 L2,191 L2,194 L7,194 L15,189 Z", back)
        pair(null, "M65,275 Q73,271 82,275 L80,286 Q73,293 67,285 Z", back)
        pair(null, "M70,368 L79,368 L78,383 L83,397 Q75,402 65,399 L67,388 Z", back)
        pair(null, "M66,401 L82,401 L83,407 Q73,411 61,406 Z", back)
    }

    // Front: chest and shoulders wrap around a narrow sternum; each abdominal segment and each
    // side of the body is drawn independently so the white separation survives on small screens.
    pair(MuscleGroup.TRAPEZIUS, "M80,49 L86,61 L69,61 Z")
    pair(MuscleGroup.SHOULDERS, "M66,62 C57,58 48,63 44,72 C40,81 43,89 48,94 L59,83 C58,74 61,68 66,66 Z")
    pair(MuscleGroup.CHEST, "M66,67 C72,63 84,63 88,70 L88,91 C87,100 78,104 66,100 C59,96 58,85 61,76 Z")
    pair(MuscleGroup.BICEPS, "M48,96 C53,89 57,90 56,101 C55,113 49,125 43,129 C39,124 42,105 48,96 Z")
    pair(MuscleGroup.TRICEPS, "M58,94 L62,105 C59,116 55,127 47,134 L43,132 C51,124 56,108 58,94 Z")
    pair(MuscleGroup.FOREARMS, "M41,136 C45,132 47,136 43,146 L30,181 L24,180 C28,162 32,144 41,136 Z")
    pair(MuscleGroup.FOREARMS, "M33,139 C35,138 36,141 32,152 L23,178 L20,180 C23,163 27,147 33,139 Z")
    pair(MuscleGroup.SERRATUS, "M62,102 L74,106 L71,111 L64,108 Z M64,110 L72,114 L69,119 L64,116 Z M64,118 L69,122 L68,127 L63,124 Z")
    pair(MuscleGroup.ABS, "M76,107 L87,103 L88,119 L75,121 Q73,114 76,107 Z")
    pair(MuscleGroup.ABS, "M75,124 L88,122 L88,136 L75,137 Z")
    pair(MuscleGroup.ABS, "M75,140 L88,139 L88,152 L76,151 Z")
    pair(MuscleGroup.ABS, "M76,154 L88,155 L88,178 C81,174 78,164 76,154 Z")
    pair(MuscleGroup.OBLIQUES, "M64,128 L71,124 L72,151 L78,170 L68,163 L62,153 Z")
    pair(MuscleGroup.HIP_FLEXORS, "M66,167 L78,175 L83,182 L77,197 L67,179 Z")
    pair(MuscleGroup.ADDUCTORS, "M81,184 L88,181 L87,220 L82,250 L77,233 L78,205 Z")
    pair(MuscleGroup.QUADRICEPS, "M64,183 C58,192 57,207 59,229 C60,249 65,265 69,272 L74,270 C70,249 67,224 69,203 Z")
    pair(MuscleGroup.QUADRICEPS, "M70,196 L75,203 L76,232 L81,257 Q81,268 77,274 L73,272 C69,249 67,222 70,196 Z")
    pair(MuscleGroup.QUADRICEPS, "M61,213 C55,231 57,251 64,268 L66,270 C61,247 62,230 61,213 Z")
    pair(MuscleGroup.TIBIALIS, "M69,292 L75,294 L74,327 L72,364 L68,370 L67,340 Z")
    pair(MuscleGroup.CALVES, "M78,291 C85,304 86,320 82,339 L77,359 L76,356 L78,321 Z")
    pair(MuscleGroup.CALVES, "M65,293 L65,326 L67,357 L66,365 C59,348 58,326 61,306 Z")

    // Back: the trapezius descends between the shoulder blades; the lats sweep towards the waist.
    // Hamstrings and calves are deliberately larger here than on the anterior view.
    pair(MuscleGroup.TRAPEZIUS, "M83,62 C76,61 70,63 67,67 L75,94 L87,117 L88,77 Q88,65 83,62 Z", back = true)
    pair(MuscleGroup.SHOULDERS, "M64,65 C53,60 45,68 43,77 L43,91 L54,84 C54,75 58,70 64,68 Z", back = true)
    pair(MuscleGroup.SHOULDERS, "M65,71 L72,92 Q62,94 56,85 C55,79 59,74 65,71 Z", back = true)
    pair(MuscleGroup.UPPER_BACK, "M55,90 C61,98 68,99 74,96 L84,117 L73,151 C64,141 60,117 55,103 Z", back = true)
    pair(MuscleGroup.UPPER_BACK, "M56,86 L70,95 Q63,102 56,96 Z", back = true)
    pair(MuscleGroup.TRICEPS, "M44,96 L54,89 C58,100 57,113 50,125 L43,130 C40,120 40,107 44,96 Z", back = true)
    pair(MuscleGroup.FOREARMS, "M42,135 L47,132 L43,150 L30,182 L24,181 C28,163 32,145 42,135 Z", back = true)
    pair(MuscleGroup.FOREARMS, "M33,139 L37,138 L31,160 L23,179 L20,180 C24,162 27,149 33,139 Z", back = true)
    pair(MuscleGroup.LOWER_BACK, "M87,122 L88,167 L74,176 L77,156 Z", back = true)
    pair(MuscleGroup.OBLIQUES, "M69,147 L74,155 L71,169 L61,175 C60,163 63,154 69,147 Z", back = true)
    pair(MuscleGroup.GLUTES, "M72,176 C81,172 88,177 88,188 L87,214 C78,222 64,215 59,203 C56,192 62,181 72,176 Z", back = true)
    pair(MuscleGroup.GLUTES, "M62,174 L69,174 L62,183 L57,188 Z", back = true)
    pair(MuscleGroup.HAMSTRINGS, "M61,214 L73,222 L71,252 L67,274 L62,279 C61,259 57,236 61,214 Z", back = true)
    pair(MuscleGroup.HAMSTRINGS, "M76,222 L85,220 L84,246 L79,276 L75,280 L73,267 L76,242 Z", back = true)
    pair(MuscleGroup.ADDUCTORS, "M87,219 L88,236 L83,263 L80,273 L83,242 Z", back = true)
    pair(MuscleGroup.CALVES, "M65,290 C58,300 59,320 64,337 Q69,343 72,331 L72,302 Z", back = true)
    pair(MuscleGroup.CALVES, "M77,291 C85,300 87,318 83,332 Q77,345 74,334 L74,303 Z", back = true)
    pair(null, "M66,342 L71,348 L72,370 L69,382 L66,370 Z M77,346 L81,341 L80,369 L76,382 L74,371 Z", back = true)
}
