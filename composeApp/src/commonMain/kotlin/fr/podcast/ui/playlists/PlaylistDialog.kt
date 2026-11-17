package fr.podcast.ui.playlists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.podcast.shared.model.Playlist
import fr.podcast.shared.model.PlaylistKind
import fr.podcast.shared.model.PlaylistLimits
import fr.podcast.shared.model.PlaylistOrder
import fr.podcast.shared.model.PlaylistRules
import fr.podcast.ui.components.FormDialog
import fr.podcast.ui.components.StarRating
import fr.podcast.ui.theme.LocalStrings

/**
 * Creates a playlist ([initial] null: static or dynamic) or changes one (its kind stays). A dynamic
 * playlist's rules select among the owner's subscriptions (ADR 0010).
 */
@Composable
fun PlaylistDialog(
    initial: Playlist?,
    onDismiss: () -> Unit,
    onSave: suspend (title: String, description: String?, rules: PlaylistRules?) -> Unit,
) {
    val strings = LocalStrings.current
    val rules = initial?.rules ?: PlaylistRules()
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: PlaylistKind.STATIC) }
    var keywords by remember { mutableStateOf(rules.keywords.joinToString(", ")) }
    var favoritesOnly by remember { mutableStateOf(rules.favoritesOnly) }
    var minRating by remember { mutableStateOf(rules.minRating) }
    var unplayedOnly by remember { mutableStateOf(rules.unplayedOnly) }
    var order by remember { mutableStateOf(rules.order) }
    var limit by remember { mutableStateOf(rules.limit.toString()) }

    val newRules = PlaylistRules(
        keywords = PlaylistRules.parseKeywords(keywords),
        favoritesOnly = favoritesOnly,
        minRating = minRating,
        unplayedOnly = unplayedOnly,
        order = order,
        limit = limit.toIntOrNull() ?: 0,
    )
    val dynamic = kind == PlaylistKind.DYNAMIC
    val valid = PlaylistLimits.validTitle(title) && description.length <= PlaylistLimits.MAX_DESCRIPTION_LENGTH &&
        (!dynamic || PlaylistLimits.invalidFields(newRules).isEmpty())

    FormDialog(
        title = if (initial == null) strings.newPlaylist else strings.editPlaylist,
        confirmLabel = strings.save,
        onDismiss = onDismiss,
        canConfirm = valid,
        onConfirm = { onSave(title.trim(), description.trim().ifEmpty { null }, newRules.takeIf { dynamic }) },
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(strings.playlistTitle) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text(strings.description) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        if (initial == null) {
            Column {
                PlaylistKind.entries.forEach { choice ->
                    Choice(selected = kind == choice, label = strings.playlistKind(choice), hint = strings.playlistKindHint(choice)) { kind = choice }
                }
            }
        }
        if (dynamic) {
            OutlinedTextField(
                value = keywords,
                onValueChange = { keywords = it },
                label = { Text(strings.keywords) },
                supportingText = { Text(strings.keywordsHint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Check(strings.favoritesOnly, favoritesOnly) { favoritesOnly = it }
            Check(strings.unplayedOnly, unplayedOnly) { unplayedOnly = it }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.minRating, style = MaterialTheme.typography.titleSmall)
                    if (minRating == 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(strings.anyRating, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                StarRating(rating = minRating, onRate = { minRating = it ?: 0 })
            }
            Column {
                PlaylistOrder.entries.forEach { choice ->
                    Choice(selected = order == choice, label = strings.playlistOrder(choice)) { order = choice }
                }
            }
            OutlinedTextField(
                value = limit,
                onValueChange = { value -> limit = value.filter(Char::isDigit).take(3) },
                label = { Text(strings.episodeLimit) },
                supportingText = { Text("1 – ${PlaylistRules.MAX_LIMIT}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Choice(selected: Boolean, label: String, hint: String? = null, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}
