package com.phishguard.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: CheckViewModel by viewModels()
    private val browseVm: BrowseViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhishGuardTheme { AppRoot(vm, browseVm, onOpenExternal = ::openInBrowser) }
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
        when (intent?.action) {
            // A link tapped in another app: check it at the safety gate first.
            Intent.ACTION_VIEW -> intent.dataString
                ?.takeIf { it.isNotBlank() }
                ?.let { vm.checkIncoming(it, gated = true) }
            // Shared text: show the verdict only, never auto-open.
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let(::firstUrl)
                ?.takeIf { it.isNotBlank() }
                ?.let { vm.checkIncoming(it, gated = false) }
        }
    }

    /** Shared text often looks like "Look at this https://x.y/z!" — pull out the link. */
    private fun firstUrl(text: String): String {
        val m = Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(text)
        return (m?.value ?: text).trim()
    }

    /**
     * Hand a checked URL to a real browser. PhishGuard itself handles http/https,
     * so a plain ACTION_VIEW could come straight back here; instead the target
     * package is chosen explicitly and is never this app.
     */
    private fun openInBrowser(url: String) {
        val uri = Uri.parse(if ("://" in url) url else "https://$url")
        val pkg = pickBrowser(uri)
        if (pkg == null) {
            Toast.makeText(this, "No browser app found to open this link.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Couldn't open a browser for this link.", Toast.LENGTH_LONG).show()
        }
    }

    /** The user's default browser if it isn't PhishGuard; otherwise any other browser. */
    @Suppress("DEPRECATION")
    private fun pickBrowser(uri: Uri): String? {
        val pm = packageManager
        // Probe with a neutral web URL so app links (e.g. a YouTube URL) don't count as "browsers".
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val default = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        // "android" = the system chooser (no default set).
        if (default != null && default != packageName && default != "android") return default
        val candidates = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .filter { it != packageName }
            .distinct()
        return candidates.firstOrNull { it == "com.android.chrome" } ?: candidates.firstOrNull()
    }
}
