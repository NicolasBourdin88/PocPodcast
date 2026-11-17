package fr.podcast.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.model.AccountRules
import fr.podcast.shared.model.Device
import fr.podcast.shared.model.User
import fr.podcast.shared.model.UserRole
import fr.podcast.shared.repository.PodcastClient
import fr.podcast.ui.components.FormDialog
import fr.podcast.ui.components.PasswordField
import fr.podcast.ui.components.SectionTitle
import fr.podcast.ui.components.attempt
import fr.podcast.ui.theme.LocalStrings
import kotlinx.coroutines.launch

/** Forms stay readable on wide windows. */
private val FORM_WIDTH = 560.dp

private sealed interface SettingsDialog {
    data object AddUser : SettingsDialog
    data class ResetPassword(val user: User) : SettingsDialog
    data class DeleteUser(val user: User) : SettingsDialog
    data class RevokeDevice(val device: Device) : SettingsDialog
}

/** What the server says about the active account: who they are, their devices, the server's accounts. */
private data class AccountDetails(val me: User, val devices: List<Device>, val users: List<User>)

/**
 * Settings: the accounts saved on this device ([accounts]), then the active account as its server
 * knows it — profile, password, signed-in devices — and, for an administrator, the server's
 * accounts (ADR 0009). [onProfileChanged] lets the device update its copy of the account.
 */
@Composable
fun SettingsScreen(
    client: PodcastClient,
    onProfileChanged: (User) -> Unit,
    modifier: Modifier = Modifier,
    accounts: LazyListScope.() -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var details by remember(client) { mutableStateOf<AccountDetails?>(null) }
    var failure by remember(client) { mutableStateOf<Throwable?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    LaunchedEffect(client, reloads) {
        attempt {
            val me = client.me()
            AccountDetails(me, client.devices(), if (me.role == UserRole.ADMIN) client.users() else emptyList())
        }.fold(
            onSuccess = {
                details = it
                failure = null
            },
            onFailure = { failure = it },
        )
    }

    fun tell(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    /** Sends [action]; on success, reloads and tells [done]. */
    fun act(done: String? = null, onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        scope.launch {
            attempt { action() }.fold(
                onSuccess = {
                    reloads++
                    onSuccess()
                    done?.let(::tell)
                },
                onFailure = { tell(it.userMessage(strings)) },
            )
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            accounts()
            failure?.let { error ->
                item(key = "failure") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error.userMessage(strings), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { reloads++ }) { Text(strings.retry) }
                    }
                }
            }
            details?.let { (me, devices, users) ->
                item(key = "profile") {
                    Column {
                        SectionTitle(strings.profile)
                        ProfileForm(me) { name ->
                            act(strings.profileSaved) { onProfileChanged(client.updateProfile(name)) }
                        }
                    }
                }
                item(key = "password") {
                    Column {
                        SectionTitle(strings.changePassword)
                        PasswordForm { current, new, clear ->
                            act(strings.passwordChanged, onSuccess = clear) { client.changePassword(current, new) }
                        }
                    }
                }
                item(key = "devices") { SectionTitle(strings.devices) }
                items(devices, key = { "device-${it.id}" }) { device ->
                    DeviceItem(device, onRevoke = { dialog = SettingsDialog.RevokeDevice(device) })
                }
                if (me.role == UserRole.ADMIN) {
                    item(key = "users") {
                        SectionTitle(strings.users) {
                            TextButton(onClick = { dialog = SettingsDialog.AddUser }) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(strings.addUser)
                            }
                        }
                    }
                    items(users, key = { "user-${it.id}" }) { user ->
                        UserItem(
                            user = user,
                            self = user.id == me.id,
                            onRole = { role -> act { client.updateUser(user.id, role = role) } },
                            onResetPassword = { dialog = SettingsDialog.ResetPassword(user) },
                            onDelete = { dialog = SettingsDialog.DeleteUser(user) },
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    val close = { dialog = null }
    when (val current = dialog) {
        null -> Unit
        SettingsDialog.AddUser -> AddUserDialog(onDismiss = close) { username, displayName, password, role ->
            client.createUser(username, displayName, password, role)
            reloads++
            tell(strings.userCreated(displayName))
        }
        is SettingsDialog.ResetPassword -> ResetPasswordDialog(current.user, onDismiss = close) { password ->
            client.resetPassword(current.user.id, password)
        }
        is SettingsDialog.DeleteUser -> FormDialog(
            title = strings.deleteUser,
            confirmLabel = strings.delete,
            destructive = true,
            onDismiss = close,
            onConfirm = {
                client.deleteUser(current.user.id)
                reloads++
            },
        ) { Text(strings.deleteUserConfirm(current.user.displayName)) }
        is SettingsDialog.RevokeDevice -> FormDialog(
            title = strings.revokeDevice,
            confirmLabel = strings.revokeDevice,
            onDismiss = close,
            onConfirm = {
                client.revokeDevice(current.device.id)
                reloads++
            },
        ) { Text(strings.revokeDeviceConfirm(current.device.name)) }
    }
}

@Composable
private fun ProfileForm(me: User, onSave: (String) -> Unit) {
    val strings = LocalStrings.current
    var name by rememberSaveable(me.id, me.displayName) { mutableStateOf(me.displayName) }
    Column(Modifier.widthIn(max = FORM_WIDTH).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${me.username} · ${strings.role(me.role)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(strings.displayName) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = { onSave(name.trim()) },
                enabled = AccountRules.validDisplayName(name) && name.trim() != me.displayName,
            ) { Text(strings.save) }
        }
    }
}

/** The current password, then the new one; [onChange] gets a function that empties the fields once done. */
@Composable
private fun PasswordForm(onChange: (current: String, new: String, clear: () -> Unit) -> Unit) {
    val strings = LocalStrings.current
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    Column(Modifier.widthIn(max = FORM_WIDTH).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PasswordField(current, { current = it }, strings.currentPassword)
        PasswordField(new, { new = it }, strings.newPassword, supporting = strings.passwordHint(AccountRules.MIN_PASSWORD_LENGTH))
        Button(
            onClick = {
                onChange(current, new) {
                    current = ""
                    new = ""
                }
            },
            enabled = current.isNotEmpty() && AccountRules.validPassword(new),
        ) { Text(strings.changePassword) }
    }
}

@Composable
private fun DeviceItem(device: Device, onRevoke: () -> Unit) {
    val strings = LocalStrings.current
    ListItem(
        headlineContent = { Text(device.name) },
        supportingContent = {
            Text(
                when {
                    device.current -> strings.thisDevice
                    else -> device.lastUsedOn()?.let(strings::lastUsed) ?: strings.neverUsed
                },
            )
        },
        // This device signs out from the accounts above.
        trailingContent = if (device.current) null else ({ TextButton(onClick = onRevoke) { Text(strings.revokeDevice) } }),
    )
}

/** An account of the server; an administrator manages the others (their own is managed above). */
@Composable
private fun UserItem(user: User, self: Boolean, onRole: (UserRole) -> Unit, onResetPassword: () -> Unit, onDelete: () -> Unit) {
    val strings = LocalStrings.current
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(if (self) "${user.displayName} (${strings.you})" else user.displayName) },
        supportingContent = { Text("${user.username} · ${strings.role(user.role)}") },
        trailingContent = if (self) {
            null
        } else {
            {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = strings.moreActions) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val admin = user.role == UserRole.ADMIN
                        DropdownMenuItem(
                            text = { Text(if (admin) strings.removeAdmin else strings.makeAdmin) },
                            onClick = {
                                menu = false
                                onRole(if (admin) UserRole.USER else UserRole.ADMIN)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(strings.resetPassword) },
                            onClick = {
                                menu = false
                                onResetPassword()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(strings.deleteUser, color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun AddUserDialog(onDismiss: () -> Unit, onCreate: suspend (username: String, displayName: String, password: String, role: UserRole) -> Unit) {
    val strings = LocalStrings.current
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf(false) }
    val usernameValid = AccountRules.validUsername(username.trim())
    FormDialog(
        title = strings.addUser,
        confirmLabel = strings.create,
        onDismiss = onDismiss,
        canConfirm = usernameValid && AccountRules.validDisplayName(displayName) && AccountRules.validPassword(password),
        onConfirm = { onCreate(username.trim(), displayName.trim(), password, if (admin) UserRole.ADMIN else UserRole.USER) },
    ) {
        OutlinedTextField(
            value = username,
            onValueChange = { username = it.lowercase() },
            label = { Text(strings.username) },
            singleLine = true,
            isError = username.isNotEmpty() && !usernameValid,
            supportingText = if (username.isNotEmpty() && !usernameValid) ({ Text(strings.usernameUnavailable) }) else null,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text(strings.displayName) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(password, { password = it }, strings.password, supporting = strings.passwordHint(AccountRules.MIN_PASSWORD_LENGTH))
        Row(
            Modifier.fillMaxWidth().toggleable(value = admin, role = Role.Checkbox, onValueChange = { admin = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = admin, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Text(strings.role(UserRole.ADMIN))
        }
    }
}

@Composable
private fun ResetPasswordDialog(user: User, onDismiss: () -> Unit, onReset: suspend (String) -> Unit) {
    val strings = LocalStrings.current
    var password by remember { mutableStateOf("") }
    FormDialog(
        title = strings.resetPassword,
        confirmLabel = strings.resetPassword,
        onDismiss = onDismiss,
        canConfirm = AccountRules.validPassword(password),
        onConfirm = { onReset(password) },
    ) {
        Text(strings.resetPasswordNotice)
        PasswordField(password, { password = it }, strings.resetPasswordOf(user.displayName), supporting = strings.passwordHint(AccountRules.MIN_PASSWORD_LENGTH))
    }
}
