package com.readygym.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Large page title used at the top of every tab. */
@Composable
internal fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(GymSpacing.Xs)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.Text)
        if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = ReadyGymColors.MutedText, lineHeight = 20.sp)
    }
}

@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(top = GymSpacing.Sm), fontSize = 15.sp,
        fontWeight = FontWeight.Bold, color = ReadyGymColors.Text)
}

/**
 * The black full-width call to action; shows a spinner instead of the label while [busy].
 *
 * Every primary action in the app is this one control at 52dp with the default panel radius. The
 * review found four different "start" buttons (49.9 / 68.5 / 37.7 / 29.7dp tall) which read as four
 * different products, so secondary actions below are deliberately smaller and outlined, not a
 * second filled style competing for the same "this is what you click" reading.
 */
@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                           enabled: Boolean = true, busy: Boolean = false) {
    Button(onClick = onClick, enabled = enabled && !busy,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp), shape = GymShapes.Md,
        colors = ButtonDefaults.buttonColors(containerColor = ReadyGymColors.Action, contentColor = ReadyGymColors.OnAction,
            disabledContainerColor = ReadyGymColors.Sunken, disabledContentColor = ReadyGymColors.MutedText)) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = ReadyGymColors.MutedText, strokeWidth = 2.dp)
            Spacer(Modifier.width(GymSpacing.Sm))
        }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** A smaller filled action inside a card: the same green, 40dp, for a row-level "start". */
@Composable
internal fun InlineAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.heightIn(min = 40.dp).clip(GymShapes.Sm).background(ReadyGymColors.AccentWash)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.AccentText) }
}

@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = GymShapes.Md) {
        Text(text, color = ReadyGymColors.Text, fontWeight = FontWeight.SemiBold)
    }
}

/** A pill that can be picked; [multi] switches the semantics from radio to checkbox. */
@Composable
internal fun Chip(label: String, selected: Boolean, onClick: () -> Unit, multi: Boolean = false, modifier: Modifier = Modifier) {
    val base = modifier
        .clip(GymShapes.Sm)
        .background(if (selected) ReadyGymColors.Action else ReadyGymColors.Surface)
        .border(1.dp, if (selected) ReadyGymColors.Action else ReadyGymColors.Outline.copy(alpha = 0.4f), GymShapes.Sm)
    val clickable = if (multi) base.toggleable(selected, role = Role.Checkbox, onValueChange = { onClick() })
    else base.selectable(selected, role = Role.RadioButton, onClick = onClick)
    Box(clickable.heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 14.sp, color = if (selected) ReadyGymColors.OnAction else ReadyGymColors.Text,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceRow(options: List<T>, label: (T) -> String, isSelected: (T) -> Boolean,
                           onPick: (T) -> Unit, multi: Boolean = false) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(GymSpacing.Sm), verticalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
        options.forEach { Chip(label(it), isSelected(it), { onPick(it) }, multi) }
    }
}

/** Inline message in a tinted box: errors in red, cautions in amber. */
@Composable
internal fun Notice(text: String, warning: Boolean = false, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.fillMaxWidth().clip(GymShapes.Md)
        .background(if (warning) ReadyGymColors.WarningSurface else ReadyGymColors.ErrorSurface)
        .padding(GymSpacing.Md),
        color = if (warning) ReadyGymColors.Warning else ReadyGymColors.Error, fontSize = 14.sp, lineHeight = 20.sp)
}
