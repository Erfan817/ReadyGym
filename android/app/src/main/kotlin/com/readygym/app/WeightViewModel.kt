package com.readygym.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.readygym.app.data.GymDatabase
import com.readygym.app.data.WeightRepository
import com.readygym.core.weight.WeightEntry
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal data class WeightTrackingState(
    val entries: List<WeightEntry> = emptyList(),
    val targetKg: Double? = null,
    val loading: Boolean = true,
    val readError: String? = null,
)

/** Weight tracking owns its data; logging a reading never rewrites the training profile or plan. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class WeightViewModel(private val repository: WeightRepository) : ViewModel() {
    val instanceId: String = UUID.randomUUID().toString()
    private val reload = MutableStateFlow(0)
    val state: StateFlow<WeightTrackingState> = reload.flatMapLatest {
        combine(repository.entries, repository.targetKg) { entries, target ->
            WeightTrackingState(entries = entries, targetKg = target, loading = false)
        }.onStart { emit(WeightTrackingState()) }
            .catch { emit(WeightTrackingState(loading = false, readError = "暂时读不到体重记录，可以点下面重新读取。")) }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WeightTrackingState())

    var saving by mutableStateOf(false)
        private set
    var deletingDate by mutableStateOf<LocalDate?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var completedSaveRevision by mutableLongStateOf(0L)
        private set

    fun clearError() { if (!saving) error = null }
    fun retryRead() { if (!saving) { error = null; reload.value++ } }

    private suspend fun awaitVisible(predicate: (WeightTrackingState) -> Boolean) {
        withTimeout(10_000) {
            state.first {
                check(it.readError == null) { "Weight history could not be read" }
                !it.loading && predicate(it)
            }
        }
    }

    fun saveEntry(date: LocalDate, kilograms: Double) = performSave {
        repository.saveEntry(date, kilograms)
        awaitVisible { it.entries.any { entry -> entry.date == date && kotlin.math.abs(entry.kilograms - kilograms) < 0.051 } }
    }

    fun saveTarget(kilograms: Double?) = performSave {
        repository.saveTarget(kilograms)
        awaitVisible { if (kilograms == null) it.targetKg == null else it.targetKg?.let { target -> kotlin.math.abs(target - kilograms) < 0.051 } == true }
    }

    private fun performSave(block: suspend () -> Unit) {
        if (saving) return
        saving = true; error = null
        viewModelScope.launch {
            try {
                block()
                completedSaveRevision++
            } catch (_: TimeoutCancellationException) {
                error = "这次读取较慢，请重新打开记录，核对是否已保存。"
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalArgumentException) {
                error = "请输入 30–250 kg 的体重，日期不能晚于今天。"
            } catch (_: Exception) {
                error = "这次没能确认保存结果，请关闭后重新读取记录，核对后再试。"
            } finally { saving = false }
        }
    }

    fun deleteEntry(date: LocalDate) {
        if (saving) return
        saving = true; deletingDate = date; error = null
        viewModelScope.launch {
            try {
                repository.deleteEntry(date)
                awaitVisible { it.entries.none { entry -> entry.date == date } }
            } catch (_: TimeoutCancellationException) {
                error = "这次读取较慢，请重新打开记录，核对是否已删除。"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "这次没能确认删除结果，请关闭后重新读取记录，核对后再试。"
            } finally { saving = false; deletingDate = null }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WeightViewModel(WeightRepository(GymDatabase.get(context.applicationContext))) as T
        }
    }
}
