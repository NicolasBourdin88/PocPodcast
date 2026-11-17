package fr.podcast.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.podcast.shared.account.Account
import fr.podcast.shared.account.AccountsState
import fr.podcast.ui.components.SectionTitle
import fr.podcast.ui.theme.LocalStrings

/** Settings › Accounts: the accounts saved on this device, one per server or several on one; one is active (ADR 0006). */
fun LazyListScope.accountsSection(
    state: AccountsState,
    demo: Boolean,
    onSelect: (Account) -> Unit,
    onAdd: () -> Unit,
    onSignOut: (Account) -> Unit,
    onLeaveDemo: () -> Unit,
) {
    item(key = "accounts") { SectionTitle(LocalStrings.current.accounts) }
    if (demo) {
        item(key = "demo") {
            val strings = LocalStrings.current
            ListItem(
                leadingContent = { RadioButton(selected = true, onClick = null) },
                headlineContent = { Text(strings.demoAccount) },
                trailingContent = { TextButton(onClick = onLeaveDemo) { Text(strings.signOut) } },
            )
        }
    }
    items(state.accounts, key = { it.id }) { account ->
        ListItem(
            modifier = Modifier.clickable { onSelect(account) },
            leadingContent = { RadioButton(selected = !demo && account.id == state.activeId, onClick = { onSelect(account) }) },
            headlineContent = { Text(account.displayName) },
            supportingContent = { Text("${account.username} · ${account.serverName}\n${account.serverUrl}") },
            trailingContent = { TextButton(onClick = { onSignOut(account) }) { Text(LocalStrings.current.signOut) } },
        )
    }
    item(key = "add-account") {
        FilledTonalButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(LocalStrings.current.addAccount)
        }
    }
}
