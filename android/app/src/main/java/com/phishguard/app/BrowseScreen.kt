package com.phishguard.app

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
private fun FlatButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true) {
    if (filled) {
        Button(
            onClick = onClick, modifier = modifier, shape = Corner,
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper),
        ) { Text(text, fontFamily = Mono, fontWeight = FontWeight.Bold) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, shape = Corner,
            border = BorderStroke(1.dp, Ink)) { Text(text, color = Ink, fontFamily = Mono) }
    }
}

@Composable
fun BrowseScreen(vm: BrowseViewModel, webView: WebView) {
    val state by vm.state.collectAsStateWithLifecycle()
    val address by vm.address.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val submit = {
        keyboard?.hide()
        focus.clearFocus()
        vm.submitAddress()
    }

    Column(Modifier.fillMaxSize().background(Paper)) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = address, onValueChange = vm::onAddressChange,
                modifier = Modifier.weight(1f), singleLine = true, shape = Corner,
                placeholder = { Text("address", color = Muted, fontFamily = Mono) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Ink, unfocusedBorderColor = Muted, cursorColor = Ink,
                    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White),
            )
            FlatButton("GO", submit)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlatButton("REPORT", vm::openReport, filled = false)
            if (BuildConfig.DEBUG) {
                FlatButton("TEST PAGE", { vm.navigate(TEST_PAGE_URL) }, filled = false)
            }
        }

        notice?.let {
            Text(
                "$it  (tap to dismiss)", color = Paper, fontSize = 12.sp, fontFamily = Mono,
                modifier = Modifier.fillMaxWidth().background(Danger).clickable { vm.clearNotice() }
                    .padding(10.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = {
                    // Re-attach safely after switching tabs.
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (state !is BrowseState.Browsing) {
                Column(
                    Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { Overlay(state, vm) }
            }
        }
    }
}

@Composable
private fun Overlay(state: BrowseState, vm: BrowseViewModel) {
    when (val s = state) {
        BrowseState.Browsing -> {}

        is BrowseState.Checking -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            MonoLabel("CHECKING ${BrowseViewModel.hostOf(s.url).orEmpty().uppercase()}…")
        }

        is BrowseState.Blocked -> {
            Text("PAGE BLOCKED — NOT LOADED", color = Danger, fontFamily = Mono,
                fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
            ResultCard(s.result) {
                FlatButton("Back to safety", vm::dismiss, Modifier.fillMaxWidth())
            }
        }

        is BrowseState.Warning -> {
            Text("WARNING — PROCEED WITH CARE", color = Caution, fontFamily = Mono,
                fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
            ResultCard(s.result) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlatButton("Go back", vm::dismiss, Modifier.weight(1f))
                    FlatButton("Continue", { vm.continueAnyway(s.url) }, Modifier.weight(1f), filled = false)
                }
            }
        }

        is BrowseState.Unverified -> Column(
            Modifier.fillMaxWidth().border(BorderStroke(1.dp, Caution), Corner)
                .background(Caution.copy(alpha = 0.08f), Corner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("COULD NOT VERIFY THIS LINK", color = Caution, fontFamily = Mono,
                fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(s.message, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlatButton("Retry", { vm.retry(s.url) })
                FlatButton("Load anyway", { vm.continueAnyway(s.url) }, filled = false)
                FlatButton("Cancel", vm::dismiss, filled = false)
            }
        }

        is BrowseState.Report -> {
            MonoLabel("PAGE REPORT")
            ResultCard(s.result) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlatButton("Back to page", vm::dismiss, Modifier.weight(1f))
                    FlatButton("Leave page", vm::leavePage, Modifier.weight(1f), filled = false)
                }
            }
        }
    }
}
