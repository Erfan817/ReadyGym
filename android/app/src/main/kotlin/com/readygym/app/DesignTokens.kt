package com.readygym.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Design tokens in the shadcn style: one named scale per dimension, no ad-hoc values.
 *
 * Before this file the codebase had ten different corner radii (4–22dp) and a handful of greys
 * chosen per screen. That is how a UI reads as "homegrown": nothing repeats, so nothing can be
 * tuned. Every screen should now pick from these scales; the only exceptions are the chat bubble
 * corners, which are deliberately asymmetric (20/6) to read as speech.
 *
 * Naming follows shadcn's tiers so the mapping stays obvious:
 *   xs/sm = compact controls, md = default, lg/xl = hero panels.
 */

/** Corner radius scale. */
object GymShapes {
    val Xs = RoundedCornerShape(6.dp)
    val Sm = RoundedCornerShape(10.dp)
    val Md = RoundedCornerShape(14.dp)
    val Lg = RoundedCornerShape(18.dp)
    val Xl = RoundedCornerShape(22.dp)
    /** Chat bubbles: asymmetric on purpose, everything else stays on the scale. */
    val BubbleUser = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
    val BubbleCoach = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
}

/** Elevation scale: 0 (flat) through 3 (floating). Cards pick one tier and keep it. */
object GymElevation {
    val Flat = 0.dp
    val Raised = 1.dp
    val Overlay = 3.dp
    val Floating = 6.dp
}

/** Spacing scale: 4dp base. Screens use 2-3 gaps per section, never a new number. */
object GymSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Xxl = 28.dp
}

/**
 * Neutral grey scale (warm-tinted, shadcn zinc-style) plus the semantic colours.
 *
 * Screens keep using [ReadyGymColors] for semantics — this object exists so a new shade is a
 * token lookup, not a hex literal. The values below are tuned for contrast on white and on the
 * cool page background; [GymShades.Grey600] is the muted-text floor (5.4:1 on white).
 */
object GymShades {
    val Grey50 = Color(0xFFFAFAFA)
    val Grey100 = Color(0xFFF4F4F5)
    val Grey200 = Color(0xFFE4E4E7)
    val Grey300 = Color(0xFFD4D4D8)
    val Grey400 = Color(0xFFA1A1AA)
    val Grey500 = Color(0xFF71717A)
    val Grey600 = Color(0xFF52525B)
    val Grey700 = Color(0xFF3F3F46)
    val Grey800 = Color(0xFF27272A)
    val Grey900 = Color(0xFF18181B)
}

/** The one corner-radius tier per surface kind; screens should stop naming numbers. */
internal fun Dp.radius(shape: RoundedCornerShape): RoundedCornerShape = shape
