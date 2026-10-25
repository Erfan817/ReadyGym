package com.readygym.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared visual pieces for the entry experience.
 *
 * Everything here is presentation only: no copy in this file states a safety guarantee, a
 * professional review, or a capability the app does not have. The text that carries those
 * claims stays in the screens, next to the data it describes.
 */

/** Small uppercase label used above section headings. */
@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = ReadyGymColors.Accent, fontWeight = FontWeight.Bold,
        fontSize = 12.sp, letterSpacing = 1.5.sp)
}

/**
 * A rounded white panel. The entry screen uses these instead of Material Card so the corner
 * radius and padding stay identical across every section.
 *
 * Radius is [GymShapes.Lg] (18dp) for every panel: the measurement pass found the stats screen
 * mixing 18dp and 22dp on the same page, which reads as two card systems rather than one.
 */
@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    container: androidx.compose.ui.graphics.Color = ReadyGymColors.Surface,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(GymShapes.Lg)
            .background(container)
            .padding(GymSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Sm),
        content = content,
    )
}

/**
 * Numbered step marker: a filled dot for the current step, a hollow ring for later ones.
 *
 * Purely decorative — the step is also written out in text, so the sequence is still readable
 * when the markers are not perceived as colour.
 */
@Composable
internal fun StepDot(active: Boolean, done: Boolean = false) {
    val fill = when {
        active -> ReadyGymColors.Action
        done -> ReadyGymColors.Accent
        else -> ReadyGymColors.Outline.copy(alpha = 0.35f)
    }
    Row(Modifier.width(18.dp), horizontalArrangement = Arrangement.Center) {
        Column(
            Modifier
                .padding(top = 6.dp)
                .size(if (active) 10.dp else 8.dp)
                .clip(CircleShape)
                .background(fill)
                .height(if (active) 10.dp else 8.dp)
        ) {}
    }
}

/** A horizontal rule that reads as a divider rather than a border. */
@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(ReadyGymColors.Outline.copy(alpha = 0.18f))
    ) {}
}

/** Key/value line used for profile summaries, so long values wrap instead of clipping. */
@Composable
internal fun FactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = ReadyGymColors.MutedText, fontSize = 13.sp,
            modifier = Modifier.width(72.dp), lineHeight = 20.sp)
        Text(value, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Chat bubble with a small tail-free shape: user messages hug the right, coach messages the left.
 * A visible author label stays in both cases, because colour alone must not carry who spoke.
 */
@Composable
internal fun MessageBubble(user: Boolean, author: String, text: String, modifier: Modifier = Modifier) {
    val shape = if (user) GymShapes.BubbleUser else GymShapes.BubbleCoach
    Column(
        modifier
            .clip(shape)
            .background(if (user) ReadyGymColors.SelectedSurface else ReadyGymColors.Surface)
            .padding(horizontal = GymSpacing.Lg, vertical = GymSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(author, color = ReadyGymColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(text, lineHeight = 24.sp)
    }
}
