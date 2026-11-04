package com.bol.app.ai.model

sealed class ModelState {
    data object NotDownloaded : ModelState()
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : ModelState()
    data object Downloaded : ModelState()
    data object Loading : ModelState()
    data object Ready : ModelState()
    data class Error(val message: String, val isRetryable: Boolean = true) : ModelState()
}
