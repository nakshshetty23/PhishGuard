package com.phishguard.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: CheckViewModel by viewModels()
    private val browseVm: BrowseViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhishGuardTheme { AppRoot(vm, browseVm) }
        }
        // Only on a fresh launch; after rotation the ViewModel already has the result.
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::firstUrl)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!url.isNullOrBlank()) vm.checkIncoming(url)
    }

    /** Shared text often looks like "Look at this https://x.y/z!" — pull out the link. */
    private fun firstUrl(text: String): String {
        val m = Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(text)
        return (m?.value ?: text).trim()
    }
}
