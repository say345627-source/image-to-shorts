package com.example.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.util.ImageProcessor
import com.example.util.NotificationHelper
import com.example.util.VideoConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Idle : UiState

    data class Processing(
        val total: Int,
        val current: Int,
        val currentFileName: String,
        val currentThumbnailUri: Uri,
        val savedCount: Int,
        val failedCount: Int
    ) : UiState

    data class Done(
        val savedCount: Int,
        val failedCount: Int,
        val totalCount: Int,
        val lastSavedUri: Uri? = null
    ) : UiState
}

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var conversionJob: Job? = null

    fun processImages(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return

        conversionJob?.cancel()
        val appContext = context.applicationContext

        conversionJob = viewModelScope.launch(Dispatchers.Default) {
            val total = uris.size
            var savedCount = 0
            var failedCount = 0
            var lastSavedUri: Uri? = null

            for (index in uris.indices) {
                val current = index + 1
                val uri = uris[index]
                val fileName = ImageProcessor.getDisplayName(appContext, uri)

                _uiState.value = UiState.Processing(
                    total = total,
                    current = current,
                    currentFileName = fileName,
                    currentThumbnailUri = uri,
                    savedCount = savedCount,
                    failedCount = failedCount
                )

                NotificationHelper.showProgressNotification(
                    context = appContext,
                    current = current,
                    total = total,
                    fileName = fileName
                )

                val result = VideoConverter.convertImageToShort(appContext, uri)
                if (result.isSuccess) {
                    savedCount++
                    lastSavedUri = result.getOrNull()
                    Log.d("MainViewModel", "Successfully converted $fileName (${result.getOrNull()})")
                } else {
                    failedCount++
                    Log.e("MainViewModel", "Failed to convert $fileName", result.exceptionOrNull())
                    // Continue with the rest as requested:
                    // "If one image fails, skip it and continue with the rest."
                }
            }

            NotificationHelper.showDoneNotification(appContext, savedCount)

            _uiState.value = UiState.Done(
                savedCount = savedCount,
                failedCount = failedCount,
                totalCount = total,
                lastSavedUri = lastSavedUri
            )
        }
    }

    fun cancel(context: Context) {
        conversionJob?.cancel(CancellationException("Cancelled by user"))
        conversionJob = null
        NotificationHelper.clearNotification(context.applicationContext)
        _uiState.value = UiState.Idle
    }

    fun reset() {
        _uiState.value = UiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        conversionJob?.cancel()
    }
}
