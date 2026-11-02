package com.readygym.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's own icon set, drawn on one 24dp grid with one stroke weight.
 *
 * Material's core set has no bar-chart, dumbbell or cable glyph, and mixing its filled glyphs with
 * a hand-drawn one makes the bottom bar look assembled from two families. These are all stroke
 * icons so the weight stays identical at 22dp; `Icon(tint = …)` recolours them through a
 * `ColorFilter`, so the literal stroke colour here never reaches the screen.
 *
 * Paths are kept as SVG strings rather than builder calls: the geometry stays readable, and the
 * parser is the same one Material's own icons use.
 */
internal object GymIcons {

    private const val SW = 1.8f

    private fun stroke(name: String, width: Float = SW, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    private fun filled(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    /** Roof and body, open at the bottom so it reads as a home rather than a letter. */
    val Home: ImageVector = stroke("home", path = "M3.6,11.1 L12,4.2 L20.4,11.1 M6.1,9.4 L6.1,19.4 L17.9,19.4 L17.9,9.4")

    /** Calendar with two binders — the same shape the plan screen uses for a training day. */
    val Calendar: ImageVector = stroke("calendar", path = """
        M3.4,9.4 L3.4,7.6 A3,3 0 0 1 6.4,4.6 L17.6,4.6 A3,3 0 0 1 20.6,7.6 L20.6,17.6
        A3,3 0 0 1 17.6,20.6 L6.4,20.6 A3,3 0 0 1 3.4,17.6 Z
        M3.4,9.4 L20.6,9.4 M8.2,2.9 L8.2,6.2 M15.8,2.9 L15.8,6.2
    """.trimIndent())

    /** Three bars: the shape a training log turns into. */
    val Bars: ImageVector = stroke("bars", width = 3.2f,
        path = "M6.4,19.2 L6.4,13.4 M12,19.2 L12,7.2 M17.6,19.2 L17.6,10.6")

    val Person: ImageVector = stroke("person", path = """
        M15.4,8.2 A3.4,3.4 0 1 1 8.6,8.2 A3.4,3.4 0 1 1 15.4,8.2 Z
        M4.9,20.2 A7.1,7.1 0 0 1 19.1,20.2
    """.trimIndent())

    /** Filled play triangle — the one filled glyph, because it sits on a saturated circle. */
    val Play: ImageVector = filled("play", "M9.1,6.3 L18.2,12 L9.1,17.7 Z")

    val Dumbbell: ImageVector = stroke("dumbbell", width = 2f, path = """
        M2.6,12 L21.4,12 M5.4,8.2 L5.4,15.8 M8.2,6.6 L8.2,17.4 M15.8,6.6 L15.8,17.4 M18.6,8.2 L18.6,15.8
    """.trimIndent())

    val Cable: ImageVector = stroke("cable", width = 2f,
        path = "M12,3.4 L12,10.2 M6.2,19.6 L12,10.2 L17.8,19.6 M9.1,19.6 L14.9,19.6")

    /**
     * Lower body: a squat stance — hip, knee forward, foot under the shoulder, mirrored.
     *
     * Two straight bars parse as a pause button; two bars that only curl at the bottom read as the
     * character 儿. The knee bend is what makes it a leg, and it is the one shape the reference's
     * leg-day icon is distinguished by as well.
     */
    val Legs: ImageVector = stroke("legs", width = 2.1f, path = """
        M9.2,4.6 L9.2,7.4 L6.4,11.6 L8.6,18.4 M5.9,18.4 L11.0,18.4
        M14.8,4.6 L14.8,7.4 L17.6,11.6 L15.4,18.4 M13.0,18.4 L18.1,18.4
    """.trimIndent())

    /** Clock with an arrow: the history shortcut on the stats title row. */
    val History: ImageVector = stroke("history", path = """
        M20.4,12 A8.4,8.4 0 1 1 5.9,6.6
        M3.6,3.4 L3.6,7.4 L7.6,7.4
        M12,7.9 L12,12.4 L15.4,14.2
    """.trimIndent())

    /** Chevron left / right for the week stepper. */
    val ChevronLeft: ImageVector = stroke("chevron-left", width = 2.2f, path = "M14.6,5.4 L8.2,12 L14.6,18.6")
    val ChevronRight: ImageVector = stroke("chevron-right", width = 2.2f, path = "M9.4,5.4 L15.8,12 L9.4,18.6")

    /** Arrow into a circle: "enter the coach conversation". */
    val ArrowRight: ImageVector = stroke("arrow-right", width = 2f,
        path = "M4.4,12 L19.2,12 M13.4,6.2 L19.2,12 L13.4,17.8")

    /** Pencil: rename the account. */
    val Pencil: ImageVector = stroke("pencil", width = 1.9f, path = """
        M4.2,19.8 L4.9,15.4 L15.6,4.7 A2.3,2.3 0 0 1 18.8,7.9 L8.1,18.6 Z
        M14.0,6.3 L17.2,9.5
    """.trimIndent())

    /** Camera body with raised shutter bump: "photograph the machine in front of you". */
    val Camera: ImageVector = stroke("camera", path = """
        M3.6,8.6 A2.2,2.2 0 0 1 5.8,6.4 L7.4,6.4 L8.6,4.4 L15.4,4.4 L16.6,6.4 L18.2,6.4
        A2.2,2.2 0 0 1 20.4,8.6 L20.4,17.2 A2.2,2.2 0 0 1 18.2,19.4 L5.8,19.4
        A2.2,2.2 0 0 1 3.6,17.2 Z
        M15.3,12.2 A3.3,3.3 0 1 1 8.7,12.2 A3.3,3.3 0 1 1 15.3,12.2 Z
    """.trimIndent())

    /** Bell with clapper: the reminder toggle. */
    val Bell: ImageVector = stroke("bell", path = """
        M12,3.6 A5.6,5.6 0 0 1 17.6,9.2 L17.6,14.6 L19.4,17.4 L4.6,17.4 L6.4,14.6 L6.4,9.2
        A5.6,5.6 0 0 1 12,3.6 Z
        M10.2,20.2 A1.9,1.9 0 0 0 13.8,20.2
    """.trimIndent())

    /** X mark: close a full-screen task. */
    val Close: ImageVector = stroke("close", width = 2.2f, path = "M6.4,6.4 L17.6,17.6 M17.6,6.4 L6.4,17.6")

    /** Microphone: switch the composer to hold-to-talk. */
    val Mic: ImageVector = stroke("mic", path = """
        M12,3.4 A3.1,3.1 0 0 1 15.1,6.5 L15.1,11.4 A3.1,3.1 0 0 1 8.9,11.4 L8.9,6.5
        A3.1,3.1 0 0 1 12,3.4 Z
        M5.8,11.4 A6.2,6.2 0 0 0 18.2,11.4
        M12,17.6 L12,20.6 M8.9,20.6 L15.1,20.6
    """.trimIndent())

    /** Keyboard: switch the composer back to typing. */
    val Keyboard: ImageVector = stroke("keyboard", path = """
        M3.4,7.2 A2,2 0 0 1 5.4,5.2 L18.6,5.2 A2,2 0 0 1 20.6,7.2 L20.6,16.8 A2,2 0 0 1 18.6,18.8
        L5.4,18.8 A2,2 0 0 1 3.4,16.8 Z
        M6.4,8.6 L7.6,8.6 M10.2,8.6 L11.4,8.6 M14,8.6 L15.2,8.6 M17,8.6 L17.6,8.6
        M6.4,12 L7.6,12 M10.2,12 L13.8,12 M16.4,12 L17.6,12
        M8,15.4 L16,15.4
    """.trimIndent())
}
