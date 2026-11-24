package com.phishguard.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

internal val Corner = RoundedCornerShape(2.dp)

@Composable
fun PhishGuardScreen(vm: CheckViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val gate by vm.gate.collectAsStateWithLifecycle()
    val loading = state is UiState.Loading
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // Close the keyboard on submit so it doesn't cover the result or pan the header away.
    val submit = {
        keyboard?.hide()
        focus.clearFocus()
        vm.check()
    }

    Column(Modifier.fillMaxSize().background(Paper)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MonoLabel("URL TO INSPECT")
                OutlinedTextField(
                    value = input,
                    onValueChange = vm::onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = Corner,
                    placeholder = { Text("paste or type a link", color = Muted, fontFamily = Mono) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Ink, unfocusedBorderColor = Muted,
                        cursorColor = Ink,
                        focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                    ),
                )
            }

            Button(
                onClick = submit,
                enabled = !loading && input.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = Corner,
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ink, contentColor = Paper,
                    disabledContainerColor = Muted.copy(alpha = 0.35f), disabledContentColor = Paper,
                ),
            ) {
                Text("Check this link", fontFamily = Mono, fontWeight = FontWeight.Bold)
            }

            when (val s = state) {
                UiState.Idle -> Text(
                    "Paste a link above, or share one to PhishGuard from any app " +
                        "(\"Check this link\"). Only the address is analysed — the page is never opened.",
                    color = Muted, fontSize = 13.sp,
                )
                UiState.Loading -> LoadingIndicator()
                is UiState.Success -> ResultCard(s.result) { GateActions(gate, vm) }
                is UiState.Error -> ErrorBox(
                    s.message, onRetry = vm::retry, onReset = vm::reset,
                    gate = gate?.takeIf { it.status == Gate.Status.PENDING },
                    onOpenAnyway = vm::openAnyway, onDontOpen = vm::dontOpen,
                )
            }
        }
    }
}

@Composable
private fun CheckAnotherButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = Corner,
        border = BorderStroke(1.dp, Ink),
    ) { Text("Check another link", color = Ink, fontFamily = Mono) }
}

/** The explicit choice for a held link: "Don't open" is the safe default. */
@Composable
private fun OpenChoice(onDontOpen: () -> Unit, onOpenAnyway: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onDontOpen, modifier = Modifier.weight(1f), shape = Corner,
            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper),
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        ) { Text("Don't open", fontFamily = Mono, fontWeight = FontWeight.Bold) }
        OutlinedButton(
            onClick = onOpenAnyway, modifier = Modifier.weight(1f), shape = Corner,
            border = BorderStroke(1.dp, Danger),
        ) { Text("Open anyway", color = Danger, fontFamily = Mono) }
    }
}

/** Bottom of the result card: plain "check another", or the safety-gate state. */
@Composable
private fun GateActions(gate: Gate?, vm: CheckViewModel) {
    when (gate?.status) {
        null -> CheckAnotherButton(vm::reset)
        Gate.Status.OPENING -> Text("Checked — safe. Opening in your browser…",
            color = Safe, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Gate.Status.PENDING -> {
            Text("This link has NOT been opened.", color = Ink,
                fontWeight = FontWeight.Bold, fontSize = 14.sp)
            OpenChoice(onDontOpen = vm::dontOpen, onOpenAnyway = vm::openAnyway)
        }
        Gate.Status.OPENED -> {
            MonoLabel("OPENED IN YOUR BROWSER")
            CheckAnotherButton(vm::reset)
        }
        Gate.Status.DECLINED -> {
            MonoLabel("LINK NOT OPENED")
            CheckAnotherButton(vm::reset)
        }
    }
}

/** Spinner; after 5 s explains that the hosted server may be waking up (up to ~60 s). */
@Composable
private fun LoadingIndicator() {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(5_000); slow = true }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            MonoLabel("INSPECTING…")
        }
        if (slow) {
            Text("Waking up the server. The first check after a quiet spell can take up to a minute.",
                color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun MonoLabel(text: String) {
    Text(text, color = Muted, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp,
        fontWeight = FontWeight.Medium)
}

private data class VerdictStyle(val word: String, val color: Color)

private fun styleFor(verdict: String) = when (verdict.lowercase()) {
    "safe" -> VerdictStyle("CLEAR", Safe)
    "malicious" -> VerdictStyle("MALICIOUS", Danger)
    else -> VerdictStyle("CAUTION", Caution)
}

/** Verdict report card; [actions] renders the buttons at the bottom. */
@Composable
internal fun ResultCard(r: CheckResponse, actions: @Composable ColumnScope.() -> Unit) {
    val style = styleFor(r.verdict)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .border(BorderStroke(1.dp, Ink.copy(alpha = 0.2f)), Corner)
            .background(Color.White, Corner),
    ) {
        // Status stripe
        Box(Modifier.width(8.dp).fillMaxHeight().background(style.color))

        Column(
            Modifier.weight(1f).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(style.word, color = style.color, fontFamily = Mono,
                fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 1.sp)
            r.host?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Muted, fontFamily = Mono, fontSize = 12.sp)
            }
            val summary = r.guidance ?: if (r.verdict.equals("safe", ignoreCase = true))
                "No phishing signals strong enough to warn about." else null
            summary?.let { Text(it, fontSize = 15.sp, color = Ink) }

            MonoLabel(
                "SCORE ${String.format(Locale.US, "%.3f", r.score)}  ·  " +
                    "SOURCE ${r.source.uppercase()}  ·  " +
                    "${String.format(Locale.US, "%.0f", r.checkedMs)} MS"
            )

            r.impersonates?.takeIf { it.isNotBlank() }?.let { brand ->
                val name = brand.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                Text("Appears to imitate $name", color = Danger, fontWeight = FontWeight.Bold,
                    fontSize = 14.sp)
            }

            val factors = r.factors.orEmpty()
            val isSafe = r.verdict.equals("safe", ignoreCase = true)
            if (factors.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                if (isSafe) {
                    // A CLEAR verdict must not read as "flagged": neutral header, muted bars.
                    MonoLabel("MINOR SIGNALS CONSIDERED")
                    Text("None of these crossed the warning threshold.",
                        color = Muted, fontSize = 12.sp)
                    factors.forEach { FactorBar(it, Muted.copy(alpha = 0.6f)) }
                } else {
                    MonoLabel("WHY THIS VERDICT")
                    factors.forEach { FactorBar(it, style.color) }
                }
            }

            actions()
        }
    }
}

@Composable
private fun FactorBar(f: Factor, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(f.label, fontSize = 13.sp, color = Ink)
        Box(Modifier.fillMaxWidth().height(8.dp).background(Ink.copy(alpha = 0.08f))) {
            Box(
                Modifier.fillMaxWidth(f.weight.toFloat().coerceIn(0f, 1f))
                    .fillMaxHeight().background(color)
            )
        }
    }
}

@Composable
private fun ErrorBox(
    message: String, onRetry: () -> Unit, onReset: () -> Unit,
    gate: Gate? = null, onOpenAnyway: () -> Unit = {}, onDontOpen: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth()
            .border(BorderStroke(1.dp, Danger), Corner)
            .background(Danger.copy(alpha = 0.06f), Corner)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("COULDN'T CHECK THIS LINK", color = Danger, fontFamily = Mono,
            fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
        Text(message, fontSize = 13.sp, color = Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onRetry, shape = Corner,
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper),
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
            ) { Text("Retry", fontFamily = Mono) }
            if (gate == null) {
                OutlinedButton(onClick = onReset, shape = Corner, border = BorderStroke(1.dp, Ink)) {
                    Text("Check another link", color = Ink, fontFamily = Mono)
                }
            }
        }
        if (gate != null) {
            // Unverified link: never redirect silently; the user decides.
            Text("This link could not be checked and has NOT been opened.",
                fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ink)
            OpenChoice(onDontOpen = onDontOpen, onOpenAnyway = onOpenAnyway)
        }
    }
}
