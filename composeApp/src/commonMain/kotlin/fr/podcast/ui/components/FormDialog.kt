package fr.podcast.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.podcast.shared.api.userMessage
import fr.podcast.ui.theme.LocalStrings
import kotlinx.coroutines.launch

/**
 * A dialog that sends something to the server: [onConfirm] runs when confirmed; the dialog closes
 * when it succeeds, and shows why when the server refuses (the fields stay as typed).
 */
@Composable
fun FormDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: suspend () -> Unit,
    canConfirm: Boolean = true,
    destructive: Boolean = false,
    content: @Composable () -> Unit = {},
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm && !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        attempt { onConfirm() }.fold(
                            onSuccess = { onDismiss() },
                            onFailure = { error = it.userMessage(strings) },
                        )
                        busy = false
                    }
                },
            ) {
                if (busy) {
                    SmallLoading()
                } else {
                    Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(strings.cancel) } },
    )
}
