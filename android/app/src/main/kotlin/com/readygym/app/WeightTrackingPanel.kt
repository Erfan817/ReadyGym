package com.readygym.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate

@Composable
internal fun WeightTrackingPanel(profileWeightKg: Double?, model: WeightViewModel? = null) {
    val context = LocalContext.current
    val factory = remember(context) { WeightViewModel.factory(context) }
    val weights = model ?: viewModel<WeightViewModel>(factory = factory)
    val state by weights.state.collectAsState()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var editorDate by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var editorKg by rememberSaveable { mutableStateOf(70.0) }
    var editingExisting by rememberSaveable { mutableStateOf(false) }
    var returnToHistory by rememberSaveable { mutableStateOf(false) }
    var handledSaveRevision by rememberSaveable { mutableLongStateOf(weights.completedSaveRevision) }
    var handledInstance by rememberSaveable { mutableStateOf(weights.instanceId) }
    LaunchedEffect(weights.instanceId, weights.completedSaveRevision) {
        if (handledInstance != weights.instanceId) {
            handledInstance = weights.instanceId
            handledSaveRevision = 0L
        }
        if (weights.completedSaveRevision > handledSaveRevision) {
            handledSaveRevision = weights.completedSaveRevision
            sheet = if (returnToHistory) "history" else null
            returnToHistory = false
        }
    }
    fun dismissEditor() {
        if (!weights.saving) {
            sheet = if (returnToHistory) "history" else null
            returnToHistory = false
            weights.clearError()
        }
    }
    when {
        state.loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.readError != null -> Panel {
            Text(requireNotNull(state.readError))
            SecondaryButton("重新读取", weights::retryRead, enabled = !weights.saving)
        }
        else -> WeightChartCard(state.entries, state.targetKg, profileWeightKg,
            onRecord = {
                weights.clearError()
                editorDate = LocalDate.now().toEpochDay()
                editorKg = state.entries.lastOrNull()?.kilograms ?: profileWeightKg ?: 70.0
                editingExisting = false; returnToHistory = false; sheet = "record"
            },
            onTarget = {
                weights.clearError()
                editorKg = state.targetKg ?: state.entries.lastOrNull()?.kilograms ?: profileWeightKg ?: 70.0
                returnToHistory = false; sheet = "target"
            },
            onHistory = { weights.clearError(); sheet = "history" })
    }
    when (sheet) {
        "record", "target" -> WeightValueSheet(
            title = if (sheet == "target") "目标体重" else if (editingExisting) "修改称重记录" else "记录体重",
            initialKg = editorKg,
            isTarget = sheet == "target",
            initialDate = LocalDate.ofEpochDay(editorDate),
            hasExistingEntry = editingExisting,
            saving = weights.saving,
            error = weights.error,
            onDismiss = ::dismissEditor,
            onSave = { date, kilograms ->
                if (sheet == "target") weights.saveTarget(kilograms) else weights.saveEntry(date, kilograms)
            },
            onClearTarget = if (sheet == "target" && state.targetKg != null) ({ weights.saveTarget(null) }) else null,
        )
        "history" -> WeightHistorySheet(
            entries = state.entries,
            onDismiss = { if (!weights.saving) { sheet = null; weights.clearError() } },
            onEdit = { entry ->
                if (!weights.saving) {
                    weights.clearError()
                    editorDate = entry.date.toEpochDay(); editorKg = entry.kilograms
                    editingExisting = true; returnToHistory = true; sheet = "record"
                }
            },
            onDelete = { entry -> weights.deleteEntry(entry.date) },
            deletingDate = weights.deletingDate,
            error = weights.error,
        )
    }
}
