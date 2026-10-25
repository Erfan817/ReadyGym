package com.readygym.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile
import com.readygym.core.profile.defaultTrainingDays
import com.readygym.core.profile.weekdayLabel

/**
 * One-page profile form. The coach chat fills the same [UserProfile]; this is the quick path and the
 * place to edit it later. Field values survive rotation through [rememberSaveable].
 */
@Composable
fun ProfileFormScreen(initial: UserProfile?, onSave: (UserProfile) -> Unit, onCancel: (() -> Unit)?) {
    var age by rememberSaveable { mutableStateOf(initial?.age?.toString().orEmpty()) }
    var height by rememberSaveable { mutableStateOf(initial?.heightCm?.let(::plain).orEmpty()) }
    var weight by rememberSaveable { mutableStateOf(initial?.weightKg?.let(::plain).orEmpty()) }
    var gender by rememberSaveable { mutableStateOf(initial?.gender ?: Gender.UNDISCLOSED) }
    var goal by rememberSaveable { mutableStateOf(initial?.goal ?: Goal.MUSCLE) }
    var location by rememberSaveable { mutableStateOf(initial?.location ?: Location.GYM) }
    var equipment by rememberSaveable { mutableStateOf(initial?.equipment?.toList() ?: listOf(Equipment.DUMBBELL, Equipment.MACHINE)) }
    var days by rememberSaveable { mutableStateOf(initial?.daysPerWeek ?: 3) }
    var weekdays by rememberSaveable { mutableStateOf(initial?.trainingDays?.toList().orEmpty()) }
    var pickWeekdays by rememberSaveable { mutableStateOf(initial?.trainingDays?.isNotEmpty() == true) }
    var minutes by rememberSaveable { mutableStateOf(initial?.minutesPerSession ?: 45) }
    var experience by rememberSaveable { mutableStateOf(initial?.experience ?: Experience.BEGINNER) }
    var limitations by rememberSaveable { mutableStateOf(initial?.limitations.orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(ReadyGymColors.Background).safeDrawingPadding().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(GymSpacing.Xl),
            verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
        ) {
            ScreenTitle(if (initial == null) "建立档案" else "编辑档案", "教练根据这些信息为你制定计划，只保存在这台手机上。")
            Panel {
                SectionHeader("身体数据")
                Row(horizontalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
                    NumberInput("年龄", "岁", age, { age = it }, Modifier.weight(1f).testTag("profile-age"))
                    NumberInput("身高", "cm", height, { height = it }, Modifier.weight(1f).testTag("profile-height"), decimal = true)
                    NumberInput("体重", "kg", weight, { weight = it }, Modifier.weight(1f).testTag("profile-weight"), decimal = true)
                }
                Text("性别", fontSize = 13.sp, color = ReadyGymColors.MutedText)
                ChoiceRow(Gender.entries, { it.label }, { it == gender }, { gender = it })
            }
            Panel {
                SectionHeader("目标与经验")
                ChoiceRow(Goal.entries, { it.label }, { it == goal }, { goal = it })
                ChoiceRow(Experience.entries, { it.label }, { it == experience }, { experience = it })
            }
            Panel {
                SectionHeader("在哪练，有什么器械")
                ChoiceRow(Location.entries, { it.label }, { it == location }, { location = it })
                ChoiceRow(Equipment.entries, { it.label }, { it in equipment }, multi = true, onPick = { e ->
                    equipment = if (e in equipment) equipment - e else equipment + e
                })
            }
            Panel {
                SectionHeader("训练频率")
                Text("每周几天", fontSize = 13.sp, color = ReadyGymColors.MutedText)
                ChoiceRow((1..7).toList(), { "$it 天" }, { it == days }, { d ->
                    days = d
                    // A new count invalidates the old selection; start from an even spread instead.
                    if (pickWeekdays && weekdays.size != d) weekdays = defaultTrainingDays(d).sorted()
                })
                if (!pickWeekdays) TextButton(onClick = { pickWeekdays = true; weekdays = defaultTrainingDays(days).sorted() },
                    modifier = Modifier.testTag("profile-pick-weekdays")) { Text("选择具体哪几天练 ›", fontSize = 13.sp) }
                else {
                    Text("哪几天练（选 $days 天，已选 ${weekdays.size} 天）", fontSize = 13.sp, color = ReadyGymColors.MutedText)
                    ChoiceRow((1..7).toList(), { weekdayLabel(it) }, { it in weekdays }, multi = true, onPick = { d ->
                        weekdays = when {
                            d in weekdays -> weekdays - d
                            weekdays.size < days -> (weekdays + d).sorted()
                            // Full: swap out the earliest pick so a tap always does something visible.
                            else -> (weekdays.drop(1) + d).sorted()
                        }
                    })
                    TextButton(onClick = { pickWeekdays = false; weekdays = emptyList() }) {
                        Text("不固定哪几天", fontSize = 13.sp, color = ReadyGymColors.MutedText)
                    }
                }
                Text("每次多久", fontSize = 13.sp, color = ReadyGymColors.MutedText)
                ChoiceRow(listOf(15, 20, 30, 45, 60, 90, 120), { "$it 分钟" }, { it == minutes }, { minutes = it })
            }
            Panel {
                SectionHeader("伤病或想避开的动作（选填）")
                OutlinedTextField(limitations, { limitations = it.take(200) }, Modifier.fillMaxWidth().testTag("profile-limitations"),
                    placeholder = { Text("例如：右膝旧伤，不想做跳跃", color = ReadyGymColors.FaintText) }, minLines = 2, shape = GymShapes.Md)
                Text("如果正在疼痛或有医生限制，请先咨询医生；本应用不提供医疗建议。",
                    fontSize = 12.sp, color = ReadyGymColors.MutedText, lineHeight = 18.sp)
            }
        }
        Column(Modifier.padding(horizontal = GymSpacing.Xl, vertical = GymSpacing.Md), verticalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
            error?.let { Notice(it) }
            PrimaryButton("保存档案", modifier = Modifier.testTag("profile-save"), onClick = {
                val profile = buildProfile(age, height, weight, gender, goal, location, equipment.toSet(), days, minutes, experience, limitations,
                    if (pickWeekdays) weekdays.toSet() else emptySet())
                if (pickWeekdays && weekdays.size != days) error = "请选满 $days 个训练日，或点「不固定哪几天」。"
                else if (profile == null) error = "请填写有效的年龄（14–90）、身高（100–250cm）、体重（30–250kg），并至少选一种器械。"
                else { error = null; onSave(profile) }
            })
            if (onCancel != null) TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("取消", color = ReadyGymColors.MutedText)
            }
        }
    }
}

@Composable
private fun NumberInput(label: String, unit: String, value: String, onChange: (String) -> Unit,
                        modifier: Modifier, decimal: Boolean = false) {
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() || (decimal && it == '.') }.take(5)) }, modifier,
        label = { Text(label) }, suffix = { Text(unit, color = ReadyGymColors.MutedText) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        shape = GymShapes.Md)
}

internal fun buildProfile(
    age: String, height: String, weight: String, gender: Gender, goal: Goal, location: Location,
    equipment: Set<Equipment>, days: Int, minutes: Int, experience: Experience, limitations: String,
    trainingDays: Set<Int> = emptySet(),
): UserProfile? = runCatching {
    UserProfile(age.trim().toInt(), height.trim().toDouble(), weight.trim().toDouble(), gender, goal, location,
        equipment, days, minutes, experience, limitations.trim(), trainingDays)
}.getOrNull()

private fun plain(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
