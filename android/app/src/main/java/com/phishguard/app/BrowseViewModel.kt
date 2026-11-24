package com.phishguard.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A camera/mic request observed on the current page. */
data class PageSignal(val kind: String, val onLoad: Boolean, val native: Boolean)

sealed interface BrowseState {
    data object Browsing : BrowseState
    data class Checking(val url: String) : BrowseState
    data class Blocked(val url: String, val result: CheckResponse) : BrowseState
    data class Warning(val url: String, val result: CheckResponse) : BrowseState
    data class Unverified(val url: String, val message: String) : BrowseState
    data class Report(val result: CheckResponse) : BrowseState
}

/** Host that WebViewAssetLoader serves bundled assets from (never hits the network). */
const val LOCAL_HOST = "appassets.androidplatform.net"
const val TEST_PAGE_URL = "https://$LOCAL_HOST/assets/camera_test.html"

class BrowseViewModel : ViewModel() {
    private val _state = MutableStateFlow<BrowseState>(BrowseState.Browsing)
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    private val _address = MutableStateFlow("")
    val address: StateFlow<String> = _address.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _commands = Channel<String>(Channel.UNLIMITED)
    /** URLs the UI should hand to WebView.loadUrl (only ever approved ones). */
    val commands = _commands.receiveAsFlow()

    // domain -> last server result (in-memory only)
    private val cache = ConcurrentHashMap<String, CheckResponse>()
    private val accepted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var job: Job? = null

    private var currentUrl = ""
    private var currentHost = ""
    private val signals = mutableListOf<PageSignal>()

    // ---- navigation gate -------------------------------------------------

    fun onAddressChange(text: String) { _address.value = text }

    fun submitAddress() = navigate(_address.value)

    fun isBrowsing() = _state.value is BrowseState.Browsing

    /** True if a load of [url] may proceed without a (new) server check. */
    fun isCleared(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == LOCAL_HOST || hostIn(host, TRUSTED) || host in accepted ||
            cache[host]?.verdict.equals("safe", ignoreCase = true)
    }

    /** Check [raw] and then load, warn or block. Never blocks the UI thread. */
    fun navigate(raw: String) {
        val url = normalize(raw) ?: return
        val host = hostOf(url) ?: return
        _address.value = url
        if (isCleared(url)) {
            job?.cancel()
            _state.value = BrowseState.Browsing   // drop any old block/warning overlay
            load(url)
            return
        }

        cache[host]?.let { decide(url, it); return }   // already seen this domain

        job?.cancel()
        _state.value = BrowseState.Checking(url)
        job = viewModelScope.launch {
            try {
                val r = ApiClient.api.check(CheckRequest(url))
                cache[host] = r
                decide(url, r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _state.value = BrowseState.Unverified(url,
                    "Can't reach the PhishGuard server at ${BuildConfig.BASE_URL}, so this link " +
                        "could not be checked.")
            } catch (e: Exception) {
                _state.value = BrowseState.Unverified(url,
                    "The check failed (${e.message ?: e.javaClass.simpleName}).")
            }
        }
    }

    private fun decide(url: String, r: CheckResponse) {
        when (r.verdict.lowercase()) {
            "safe" -> { _state.value = BrowseState.Browsing; load(url) }
            "malicious" -> _state.value = BrowseState.Blocked(url, r)
            else -> _state.value = BrowseState.Warning(url, r)   // caution / unknown
        }
    }

    private fun load(url: String) { _commands.trySend(url) }

    fun continueAnyway(url: String) {
        hostOf(url)?.let { accepted += it }
        _state.value = BrowseState.Browsing
        load(url)
    }

    fun retry(url: String) = navigate(url)

    /** "Go back" / "Back to safety": drop the overlay, stay on the previous page. */
    fun dismiss() {
        job?.cancel()
        _state.value = BrowseState.Browsing
        _address.value = currentUrl
    }

    /** Leave the current page entirely. */
    fun leavePage() {
        _state.value = BrowseState.Browsing
        _address.value = ""
        _commands.trySend("about:blank")
    }

    // ---- page signals (camera / mic hook) --------------------------------

    /** WebViewClient: a cleared page is now loading. */
    fun onPageCommitted(url: String) {
        if (url != currentUrl) synchronized(signals) { signals.clear() }
        currentUrl = url
        currentHost = hostOf(url).orEmpty()
        _address.value = url
    }

    /** Called from the JS bridge thread or the UI thread. */
    fun onMediaSignal(kind: String, userInteracted: Boolean, native: Boolean) {
        val first: Boolean
        synchronized(signals) {
            if (signals.any { it.kind == kind && it.native == native }) return
            first = signals.isEmpty()
            signals += PageSignal(kind, !userInteracted, native)
        }
        if (native) _notice.value = "Blocked a $kind request from $currentHost"
        if (first && _state.value is BrowseState.Browsing) {
            _state.value = BrowseState.Report(buildReport())
        }
    }

    fun clearNotice() { _notice.value = null }

    fun openReport() { _state.value = BrowseState.Report(buildReport()) }

    private fun buildReport(): CheckResponse {
        val base = cache[currentHost] ?: CheckResponse(
            url = currentUrl, host = currentHost, verdict = "safe", score = 0.0, threshold = 0.0,
            factors = emptyList(), impersonates = null, guidance = null,
            source = if (currentHost == LOCAL_HOST) "local test page" else "not scored",
            checkedMs = 0.0,
        )
        val sig = synchronized(signals) { signals.toList() }
        if (sig.isEmpty()) return base

        val extra = sig.map {
            val what = when (it.kind) {
                "camera" -> "camera"
                "mic" -> "microphone"
                else -> "camera and microphone"
            }
            Factor(
                feature = "page_media_request",
                label = if (it.onLoad) "Requests $what on load" else "Requests $what access",
                weight = if (it.onLoad) 0.9 else 0.5,
                value = 1.0,
            )
        }
        val onLoad = sig.any { it.onLoad }
        val escalate = onLoad && base.verdict.equals("safe", ignoreCase = true)
        return base.copy(
            verdict = if (escalate) "caution" else base.verdict,
            factors = base.factors.orEmpty() + extra,
            guidance = if (onLoad)
                "This page asked for your camera or microphone as soon as it loaded, before any " +
                    "interaction. Real sites wait until you tap something. The request was denied."
            else base.guidance,
        )
    }

    // ---- helpers -----------------------------------------------------------

    private fun normalize(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val url = if ("://" in t) t else "https://$t"
        return url.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    companion object {
        /** Small in-app allowlist (the backend has the full 240+ list). */
        private val TRUSTED = setOf(
            "google.com", "wikipedia.org", "github.com", "microsoft.com", "apple.com",
            "amazon.com", "youtube.com", "mozilla.org", "python.org",
        )

        fun hostOf(url: String): String? =
            try { Uri.parse(url).host?.lowercase() } catch (e: Exception) { null }

        private fun hostIn(host: String, domains: Set<String>): Boolean {
            val parts = host.split(".")
            return parts.indices.any { parts.drop(it).joinToString(".") in domains }
        }
    }
}
