package com.phishguard.app

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class Tab { CHECK, BROWSE }

@Composable
fun AppRoot(checkVm: CheckViewModel, browseVm: BrowseViewModel) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.CHECK) }

    // One WebView for the whole session so the page survives switching tabs. It is
    // created LAZILY the first time BROWSE opens: initialising Chromium on the main
    // thread at launch made cold start take 6-8 s and trip an ANR.
    var webView by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(tab) {
        if (tab == Tab.BROWSE && webView == null) {
            withFrameNanos { }   // let the "starting browser" placeholder draw first
            webView = createGuardedWebView(context, browseVm)
        }
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }
    // Commands are buffered in the ViewModel until the WebView exists.
    LaunchedEffect(webView) {
        val wv = webView ?: return@LaunchedEffect
        browseVm.commands.collect { wv.loadUrl(it) }
    }

    BackHandler(enabled = tab == Tab.BROWSE) {
        val wv = webView
        when {
            !browseVm.isBrowsing() -> browseVm.dismiss()
            wv != null && wv.canGoBack() -> wv.goBack()
            else -> tab = Tab.CHECK
        }
    }

    Column(Modifier.fillMaxSize().background(Paper)) {
        Row(
            Modifier.fillMaxWidth().background(Ink).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("PHISHGUARD", color = Paper, fontFamily = Mono, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
            TabLabel("CHECK", tab == Tab.CHECK) { tab = Tab.CHECK }
            TabLabel("BROWSE", tab == Tab.BROWSE) { tab = Tab.BROWSE }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val wv = webView
            when {
                tab == Tab.CHECK -> PhishGuardScreen(checkVm)
                wv != null -> BrowseScreen(browseVm, wv)
                else -> Box(Modifier.fillMaxSize().padding(16.dp)) { MonoLabel("STARTING BROWSER…") }
            }
        }
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, fontFamily = Mono, fontSize = 12.sp, letterSpacing = 1.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) Paper else Paper.copy(alpha = 0.55f),
        textDecoration = if (selected) TextDecoration.Underline else null,
        modifier = Modifier.clickable(onClick = onClick),
    )
}
