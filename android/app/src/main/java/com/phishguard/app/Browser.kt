package com.phishguard.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.toArgb
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Runs at document start. Wraps getUserMedia so the app learns when a page asks for
 * the camera/mic, and whether the user had interacted with the page first.
 * Detection only: the original call is still made (the native prompt is then denied).
 */
private val HOOK_JS = """
(function () {
  if (window.__pgHooked) return;
  window.__pgHooked = true;
  var interacted = false;
  ['pointerdown', 'keydown', 'touchstart', 'click'].forEach(function (e) {
    window.addEventListener(e, function () { interacted = true; },
      { capture: true, passive: true });
  });
  function report(c) {
    var kind = (c && c.video && c.audio) ? 'camera+mic' : (c && c.video) ? 'camera' :
               (c && c.audio) ? 'mic' : 'media';
    try { PhishGuardBridge.onMediaRequest(kind, interacted); } catch (e) {}
  }
  var md = navigator.mediaDevices;
  if (md && md.getUserMedia) {
    var orig = md.getUserMedia.bind(md);
    md.getUserMedia = function (c) { report(c); return orig(c); };
  }
})();
""".trimIndent()

/** Receives signals from the page. Exposed to page JS, so it accepts only tiny inputs. */
private class MediaBridge(private val vm: BrowseViewModel) {
    @JavascriptInterface
    fun onMediaRequest(kind: String?, userInteracted: Boolean) {
        val k = when (kind) {
            "camera", "mic", "camera+mic" -> kind
            else -> "media"
        }
        vm.onMediaSignal(k, userInteracted, native = false)
    }
}

private class GuardClient(
    private val vm: BrowseViewModel,
    private val assets: WebViewAssetLoader,
    private val needsLateHook: Boolean,
) : WebViewClient() {

    /** Link clicks and redirects. Cancel unless already cleared; the VM re-loads once approved. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return true // no intent:/tel: etc.
        if (vm.isCleared(url)) return false
        vm.navigate(url)   // async check; UI thread is not blocked
        return true
    }

    /** Safety net for navigations that skip shouldOverrideUrlLoading (back/forward, form posts). */
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (needsLateHook) view.evaluateJavascript(HOOK_JS, null)
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return
        if (vm.isCleared(url)) {
            vm.onPageCommitted(url)
        } else {
            view.stopLoading()
            vm.navigate(url)
        }
    }

    override fun shouldInterceptRequest(
        view: WebView, request: WebResourceRequest,
    ): WebResourceResponse? = assets.shouldInterceptRequest(request.url)
}

@SuppressLint("SetJavaScriptEnabled")
fun createGuardedWebView(context: Context, vm: BrowseViewModel): WebView {
    val assets = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()
    val hasDocStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    return WebView(context).apply {
        setBackgroundColor(Paper.toArgb())
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        settings.apply {
            javaScriptEnabled = true            // required to observe getUserMedia
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
        }
        addJavascriptInterface(MediaBridge(vm), "PhishGuardBridge")
        if (hasDocStart) WebViewCompat.addDocumentStartJavaScript(this, HOOK_JS, setOf("*"))

        webViewClient = GuardClient(vm, assets, needsLateHook = !hasDocStart)
        webChromeClient = object : WebChromeClient() {
            /** Native camera/mic prompt: deny by default and warn. */
            override fun onPermissionRequest(request: PermissionRequest) {
                val cam = PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources
                val mic = PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources
                request.deny()
                val kind = if (cam && mic) "camera+mic" else if (cam) "camera" else if (mic) "mic" else "media"
                vm.onMediaSignal(kind, userInteracted = false, native = true)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: android.webkit.GeolocationPermissions.Callback,
            ) = callback.invoke(origin, false, false)
        }
    }
}
