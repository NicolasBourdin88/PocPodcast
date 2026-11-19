package fr.podcast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.podcast.shared.model.Avatar
import fr.podcast.ui.theme.LocalStrings
import fr.podcast.ui.theme.rgb

/** A profile picture (ADR 0013): its emoji on its color, in a rounded square as on streaming services. */
@Composable
fun AvatarImage(avatar: Avatar, size: Dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val emojiSize = with(LocalDensity.current) { (size * 0.58f).toSp() }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f))
            .background(rgb(avatar.color))
            .semantics { contentDescription?.let { this.contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        Text(avatar.emoji, fontSize = emojiSize)
    }
}

/** The avatars to choose from; [onChoose] saves at once, the dialog closes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarPickerDialog(current: Avatar, onChoose: (Avatar) -> Unit, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.chooseAvatar) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar.entries.forEach { avatar ->
                    val chosen = avatar == current
                    val shape = RoundedCornerShape(20.dp)
                    Box(
                        Modifier
                            .border(3.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                            .padding(5.dp)
                            .semantics {
                                role = Role.RadioButton
                                selected = chosen
                            },
                    ) {
                        AvatarImage(
                            avatar,
                            size = 64.dp,
                            contentDescription = strings.avatarName(avatar),
                            modifier = Modifier.clickable {
                                onChoose(avatar)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}
