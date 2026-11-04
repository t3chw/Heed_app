package com.bol.app.ui.screens.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bol.app.data.local.dao.PerformanceLogDao
import com.bol.app.data.local.entity.PerformanceLogEntity
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import com.bol.app.monitoring.MetricsSnapshot
import com.bol.app.monitoring.PerformanceMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LogViewerViewModel(
    private val performanceLogDao: PerformanceLogDao,
    private val performanceMonitor: PerformanceMonitor
) : ViewModel() {

    private val _selectedCategories = MutableStateFlow<Set<LogCategory>>(emptySet())
    val selectedCategories: StateFlow<Set<LogCategory>> = _selectedCategories.asStateFlow()

    private val _selectedLevel = MutableStateFlow<LogLevel?>(null)
    val selectedLevel: StateFlow<LogLevel?> = _selectedLevel.asStateFlow()

    private val _liveMetrics = MutableStateFlow<MetricsSnapshot?>(null)
    val liveMetrics: StateFlow<MetricsSnapshot?> = _liveMetrics.asStateFlow()

    private val allLogs = performanceLogDao.getAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val filteredLogs: StateFlow<List<PerformanceLogEntity>> = combine(
        allLogs, _selectedCategories, _selectedLevel
    ) { logs, cats, level ->
        logs.filter { entry ->
            (cats.isEmpty() || LogCategory.entries.any { it.name == entry.category && it in cats }) &&
            (level == null || entry.level == level.name)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val logCount: StateFlow<Int> = combine(allLogs) { (logs) ->
        logs.size
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        viewModelScope.launch {
            while (true) {
                _liveMetrics.value = try { performanceMonitor.capture() } catch (_: Exception) { null }
                delay(3000)
            }
        }
    }

    fun toggleCategory(category: LogCategory) {
        _selectedCategories.value = _selectedCategories.value.let {
            if (category in it) it - category else it + category
        }
    }

    fun setLevelFilter(level: LogLevel?) {
        _selectedLevel.value = if (_selectedLevel.value == level) null else level
    }

    fun clearAll() {
        viewModelScope.launch { performanceLogDao.deleteAll() }
    }

    suspend fun exportLogsAsJson(): String {
        val logs = performanceLogDao.getAllSnapshot()
        val exportData = logs.map { entry ->
            mapOf(
                "id" to entry.id,
                "timestamp" to entry.timestamp.toString(),
                "level" to entry.level,
                "category" to entry.category,
                "event" to entry.event,
                "message" to entry.message,
                "durationMs" to (entry.durationMs?.toString() ?: ""),
                "metricsJson" to (entry.metricsJson ?: ""),
                "extrasJson" to (entry.extrasJson ?: ""),
                "processName" to entry.processName
            )
        }
        return Json { prettyPrint = true }.encodeToString(exportData)
    }
}
