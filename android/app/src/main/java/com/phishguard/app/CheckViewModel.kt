package com.phishguard.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Success(val result: CheckResponse) : UiState
    data class Error(val message: String) : UiState
}

class CheckViewModel : ViewModel() {
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private var job: Job? = null

    fun onInputChange(text: String) {
        _input.value = text
    }

    /** Called for URLs arriving via share sheet / VIEW intents. */
    fun checkIncoming(url: String) {
        _input.value = url
        check()
    }

    fun check() {
        val url = _input.value.trim()
        if (url.isEmpty()) return
        job?.cancel()
        _state.value = UiState.Loading
        job = viewModelScope.launch {
            _state.value = try {
                UiState.Success(ApiClient.api.check(CheckRequest(url)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                UiState.Error("The server answered with an error (HTTP ${e.code()}). " +
                    "Check the backend console for details.")
            } catch (e: IOException) {
                UiState.Error("Can't reach the PhishGuard server at ${BuildConfig.BASE_URL}\n\n" +
                    "• Start the backend: uvicorn app:app --app-dir backend --host 0.0.0.0 --port 8000\n" +
                    "• Emulator uses 10.0.2.2; a physical device needs your PC's LAN IP " +
                    "(set phishguard.baseUrl in gradle.properties).\n" +
                    "• Phone and PC must be on the same Wi-Fi.")
            } catch (e: Exception) {
                UiState.Error("Unexpected error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun reset() {
        job?.cancel()
        _input.value = ""
        _state.value = UiState.Idle
    }
}
