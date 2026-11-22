package com.parachord.android.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.shared.plugin.PluginSignInPrompt
import com.parachord.shared.plugin.SignInPromptCopy
import org.koin.compose.koinInject

/**
 * Desktop's "sign in to X?" question after a play a plugin refused as not
 * signed in (#775, parachord#1523): the shared [PluginSignInPrompt]'s
 * [PluginSignInPrompt.pending], drawn once at the app's root. Sign in runs the
 * plugin's own sign-in; Not now (or dismissing) leaves the track stopped,
 * and Play asks again. iOS draws the same question (`RootView`).
 */
@Composable
fun PluginSignInPromptDialog(prompt: PluginSignInPrompt = koinInject()) {
    val request by prompt.pending.collectAsStateWithLifecycle()
    val r = request ?: return
    AlertDialog(
        onDismissRequest = { prompt.answer(r.requestId, signIn = false) },
        containerColor = ModalBg,
        titleContentColor = ModalTextActive,
        textContentColor = ModalTextPrimary,
        title = { Text(r.title) },
        text = { Text(r.message) },
        confirmButton = {
            TextButton(onClick = { prompt.answer(r.requestId, signIn = true) }) {
                Text(SignInPromptCopy.CONFIRM, color = ModalTextActive)
            }
        },
        dismissButton = {
            TextButton(onClick = { prompt.answer(r.requestId, signIn = false) }) {
                Text(SignInPromptCopy.DECLINE, color = ModalTextPrimary)
            }
        },
    )
}
