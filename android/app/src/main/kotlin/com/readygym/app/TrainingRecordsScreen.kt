package com.readygym.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
internal fun TrainingRecordsScreen(vm: GymViewModel, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
        .padding(GymSpacing.Lg).testTag("training-records-screen"),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Md)) {
        TextButton(onClick = onClose, modifier = Modifier.testTag("records-close")) { Text("‹ 返回统计") }
        ScreenTitle("训练记录", "选一天，看看自己练过什么。")
        RecordsCalendar(vm)
    }
}
