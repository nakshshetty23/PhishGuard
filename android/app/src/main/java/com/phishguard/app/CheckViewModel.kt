package com.phishguard.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Success(val result: CheckResponse) : UiState
    data class Error(val message: String) : UiState
}

/**
 * A link that was tapped in another app (ACTION_VIEW) and is waiting at the
 * safety gate. Nothing is opened unless the verdict is safe or the user chooses
 * "Open anyway".
 */
data class Gate(val url: String, val status: Status) {
    enum class Status { PENDING, OPENING, OPENED, DECLINED }
}

class CheckViewModel : ViewModel() {
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _gate = MutableStateFlow<Gate?>(null)
    val gate: StateFlow<Gate?> = _gate.asStateFlow()

    /** URLs the activity should hand to an external browser (never to PhishGuard). */
    private val _handoff = Channel<String>(Channel.BUFFERED)
    val handoff = _handoff.receiveAsFlow()

    /** Bumped on every incoming VIEW/SEND link so the UI can switch to the CHECK tab. */
    private val _incoming = MutableStateFlow(0)
    val incoming: StateFlow<Int> = _incoming.asStateFlow()

    private var job: Job? = null
    private var handoffJob: Job? = null

    init {
        // Wake the hosted backend as soon as the app opens, so it is likely awake
        // by the time the user checks a link. Failures are ignored.
        viewModelScope.launch {
            try { ApiClient.api.health().close() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
    }

    fun onInputChange(text: String) {
        _input.value = text
    }

    /**
     * A URL arriving from another app. [gated] = true for ACTION_VIEW (a tapped
     * link): a safe verdict continues to the browser, anything else is held.
     * Shares (ACTION_SEND) pass false and only show the verdict.
     */
    fun checkIncoming(url: String, gated: Boolean) {
        _input.value = url
        _gate.value = if (gated) Gate(url, Gate.Status.PENDING) else null
        _incoming.value += 1
        run()
    }

    /** Manual check (button / keyboard): never redirects. */
    fun check() {
        _gate.value = null
        run()
    }

    /** Retry after an error, keeping any pending gate. */
    fun retry() = run()

    /** User chose to open a risky or unverified link. */
    fun openAnyway() {
        val g = _gate.value ?: return
        _handoff.trySend(g.url)
        _gate.value = g.copy(status = Gate.Status.OPENED)
    }

    fun dontOpen() {
        handoffJob?.cancel()
        _gate.value = _gate.value?.copy(status = Gate.Status.DECLINED)
    }

    private fun run() {
        val url = _input.value.trim()
        if (url.isEmpty()) return
        job?.cancel()
        handoffJob?.cancel()
        _state.value = UiState.Loading
        job = viewModelScope.launch {
            _state.value = try {
                val r = ApiClient.api.check(CheckRequest(url))
                val g = _gate.value
                if (g != null && g.status == Gate.Status.PENDING &&
                    r.verdict.equals("safe", ignoreCase = true)) {
                    // Safe: show "opening..." briefly, then continue to the browser.
                    _gate.value = g.copy(status = Gate.Status.OPENING)
                    handoffJob = viewModelScope.launch {
                        delay(900)
                        _handoff.send(g.url)
                        _gate.value = g.copy(status = Gate.Status.OPENED)
                    }
                }
                UiState.Success(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                UiState.Error("The server answered with an error (HTTP ${e.code()}). " +
                    "Check the backend console for details.")
            } catch (e: IOException) {
                UiState.Error("Can't reach the PhishGuard server at ${BuildConfig.BASE_URL}\n\n" +
                    "• Check that this phone is online.\n" +
                    "• The server sleeps when idle and can take up to a minute to wake. " +
                    "Tap Retry in a moment.")
            } catch (e: Exception) {
                UiState.Error("Unexpected error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun reset() {
        job?.cancel()
        handoffJob?.cancel()
        _gate.value = null
        _input.value = ""
        _state.value = UiState.Idle
    }
}
