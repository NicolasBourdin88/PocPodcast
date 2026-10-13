package fr.podcast.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fr.podcast.shared.account.Account
import fr.podcast.shared.api.ApiException
import fr.podcast.shared.api.userMessage
import fr.podcast.ui.theme.LocalStrings
import kotlinx.coroutines.launch

/**
 * Server address, username and password. The password is only sent to get a device token
 * and is never stored (ADR 0005).
 */
@Composable
fun SignInScreen(
    defaultServerUrl: String,
    signIn: suspend (serverUrl: String, username: String, password: String) -> Account,
    onSignedIn: (Account) -> Unit,
    onDemo: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    var server by rememberSaveable { mutableStateOf(defaultServerUrl) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canSubmit = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty()

    fun submit() {
        if (!canSubmit) return
        busy = true
        error = null
        scope.launch {
            try {
                onSignedIn(signIn(server, username.trim(), password))
            } catch (failure: ApiException) {
                error = failure.userMessage(strings)
            } finally {
                busy = false
            }
        }
    }

    Box(
        modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.appName, style = MaterialTheme.typography.headlineMedium)
            Text(strings.signInTitle, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text(strings.serverUrl) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(strings.username) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(strings.password) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = ::submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(strings.signIn)
            }
            onDemo?.let { TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text(strings.demoMode) } }
            onCancel?.let { TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text(strings.cancel) } }
        }
    }
}
