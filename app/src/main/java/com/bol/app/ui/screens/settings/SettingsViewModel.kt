package com.bol.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bol.app.ai.deviceTotalRamMb
import com.bol.app.ai.model.ModelManager
import com.bol.app.ai.model.ModelState
import com.bol.app.data.backup.BackupManifest
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.data.preferences.ThemeMode
import com.bol.app.domain.usecase.ClearDataUseCase
import com.bol.app.domain.usecase.ExportDataUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    modelManager: ModelManager,
    private val preferencesManager: PreferencesManager,
    private val exportDataUseCase: ExportDataUseCase,
    private val clearDataUseCase: ClearDataUseCase,
    private val applicationContext: android.content.Context
) : ViewModel() {

    private val _clearRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    val clearRunning: StateFlow<Boolean> = _clearRunning

    private val _clearMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val clearMessage: StateFlow<String?> = _clearMessage

    val modelState: StateFlow<ModelState> = modelManager.state

    val deviceTotalRamMb: Int = deviceTotalRamMb(applicationContext)

    val themeMode: Flow<ThemeMode> = preferencesManager.themeMode.map { ThemeMode.fromKey(it) }
    val language: Flow<String> = preferencesManager.language

    val timeFormat: Flow<String> = preferencesManager.timeFormat

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferencesManager.setThemeMode(mode.key) }
    }

    fun setLanguage(lang: String) {
        viewModelScope.launch { preferencesManager.setLanguage(lang) }
    }

    fun setTimeFormat(format: String) {
        viewModelScope.launch { preferencesManager.setTimeFormat(format) }
    }

    val digestEnabled: Flow<Boolean> = preferencesManager.digestEnabled
    val digestTime: Flow<String> = preferencesManager.digestTime
    val notifyOnWorkDone: Flow<Boolean> = preferencesManager.notifyOnWorkDone

    fun setNotifyOnWorkDone(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setNotifyOnWorkDone(enabled) }
    }

    /** Hour-of-day behind the morning choices when setting a reminder by hand. */
    val defaultReminderHour: Flow<Int> = preferencesManager.defaultReminderHour

    fun setDefaultReminderHour(hour: Int) {
        viewModelScope.launch { preferencesManager.setDefaultReminderHour(hour) }
    }

    /** Hidden developer mode (7-tap unlock on the version row). */
    val devModeEnabled: Flow<Boolean> = preferencesManager.devModeEnabled

    fun setDevModeEnabled(value: Boolean) {
        viewModelScope.launch { preferencesManager.setDevModeEnabled(value) }
    }

    fun setDigestEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setDigestEnabled(enabled)
            if (enabled) {
                com.bol.app.service.digest.DailyDigestScheduler.schedule(
                    applicationContext, preferencesManager.digestTime.first(),
                )
            } else {
                com.bol.app.service.digest.DailyDigestScheduler.cancel(applicationContext)
            }
        }
    }

    fun setDigestTime(hhmm: String) {
        viewModelScope.launch {
            preferencesManager.setDigestTime(hhmm)
            if (preferencesManager.digestEnabled.first()) {
                com.bol.app.service.digest.DailyDigestScheduler.schedule(applicationContext, hhmm)
            }
        }
    }

    /**
     * Write a complete `.heedbackup` archive to [out]. Returns the
     * manifest the codec produced (so the screen can show counts in a
     * confirmation toast). The OutputStream is NOT closed by this
     * method — the caller's `.use { }` block owns its lifecycle.
     */
    suspend fun exportTo(out: java.io.OutputStream): BackupManifest {
        return exportDataUseCase.export(out)
    }

    /**
     * Nukes all user data (items, recordings, queues, search history, tags,
     * enrichments, logs) and deletes audio WAV files. Keeps downloaded
     * models and all preferences. Categories are reset to defaults.
     *
     * Guarded by the UI's confirmation dialog — runs straight through once
     * invoked. Exposes [clearRunning] so the button can disable during the
     * wipe and [clearMessage] for a quick toast-style confirmation.
     */
    fun clearAllData() {
        if (_clearRunning.value) return
        _clearRunning.value = true
        _clearMessage.value = null
        viewModelScope.launch {
            runCatching { clearDataUseCase.clearAll() }
                .onSuccess { _clearMessage.value = "All data cleared." }
                .onFailure { _clearMessage.value = "Clear failed: ${it.message?.take(120)}" }
            _clearRunning.value = false
        }
    }

    fun dismissClearMessage() {
        _clearMessage.value = null
    }
}
