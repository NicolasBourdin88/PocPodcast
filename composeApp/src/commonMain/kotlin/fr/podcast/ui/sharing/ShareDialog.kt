package fr.podcast.ui.sharing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.model.ShareRules
import fr.podcast.shared.model.User
import fr.podcast.shared.repository.PodcastClient
import fr.podcast.ui.components.FormDialog
import fr.podcast.ui.components.attempt
import fr.podcast.ui.theme.LocalStrings

/** The other accounts of the server, or why they could not be read. */
private sealed interface Recipients {
    data object Loading : Recipients
    data class Loaded(val users: List<User>) : Recipients
    data class Failed(val error: Throwable) : Recipients
}

/**
 * Shares with another account of the server (ADR 0009): whom, what they may do when
 * [withPermission] (a subscription; a recommendation is read-only), and an optional message.
 * [onShare] sends it; the dialog closes when the server accepts.
 */
@Composable
fun ShareDialog(
    client: PodcastClient,
    title: String,
    confirmLabel: String,
    withPermission: Boolean,
    onDismiss: () -> Unit,
    onShare: suspend (user: User, permission: SharePermission, message: String?) -> Unit,
) {
    val strings = LocalStrings.current
    val recipients by produceState<Recipients>(Recipients.Loading, client) {
        value = attempt {
            val me = client.me()
            client.users().filter { it.id != me.id }
        }.fold({ Recipients.Loaded(it) }, { Recipients.Failed(it) })
    }
    var selected by remember { mutableStateOf<User?>(null) }
    var permission by remember { mutableStateOf(SharePermission.READ) }
    var message by remember { mutableStateOf("") }
    val messageTooLong = message.length > ShareRules.MAX_MESSAGE_LENGTH

    FormDialog(
        title = title,
        confirmLabel = confirmLabel,
        onDismiss = onDismiss,
        canConfirm = selected != null && !messageTooLong,
        onConfirm = { onShare(selected!!, permission, message.trim().ifEmpty { null }) },
    ) {
        Text(strings.shareWith, style = MaterialTheme.typography.titleSmall)
        when (val current = recipients) {
            Recipients.Loading -> CircularProgressIndicator(Modifier.padding(8.dp))
            is Recipients.Failed -> Text(current.error.userMessage(strings), color = MaterialTheme.colorScheme.error)
            is Recipients.Loaded -> if (current.users.isEmpty()) {
                Text(strings.noOtherUsers, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column {
                    current.users.forEach { user ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = user == selected, role = Role.RadioButton, onClick = { selected = user })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = user == selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(user.displayName)
                                Text(user.username, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if (withPermission) PermissionChoice(permission, onChange = { permission = it })
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text(strings.shareMessage) },
            minLines = 2,
            isError = messageTooLong,
            supportingText = if (messageTooLong) ({ Text(strings.messageTooLong(ShareRules.MAX_MESSAGE_LENGTH)) }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Read-only or read and modify: one line each, so that the labels fit narrow dialogs. */
@Composable
fun PermissionChoice(permission: SharePermission, onChange: (SharePermission) -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    Column(modifier) {
        Text(strings.sharePermission, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
        SharePermission.entries.forEach { choice ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = choice == permission, role = Role.RadioButton, onClick = { onChange(choice) })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = choice == permission, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(strings.permission(choice))
            }
        }
    }
}
