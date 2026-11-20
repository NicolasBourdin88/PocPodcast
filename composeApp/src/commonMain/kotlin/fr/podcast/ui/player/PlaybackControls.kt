package fr.podcast.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.podcast.player.PlaybackController
import fr.podcast.ui.components.PodcastIcons
import fr.podcast.ui.theme.LocalStrings
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.IconButtonDefaults

/** Playback of the active account (or of the demo), for every screen. */
val LocalPlayback = staticCompositionLocalOf<PlaybackController> { error("No PlaybackController provided") }

/** "Back 15 s" or "forward 30 s": a circular arrow with the number inside. */
@Composable
fun SkipButton(forward: Boolean, onClick: () -> Unit, size: Dp = 48.dp, enabled: Boolean = true) {
    val strings = LocalStrings.current
    val seconds = (if (forward) PlaybackController.SKIP_FORWARD_SECONDS else PlaybackController.SKIP_BACK_SECONDS).toInt()
    IconButton(onClick = onClick, modifier = Modifier.size(size), enabled = enabled, shapes = IconButtonDefaults.shapes()) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (forward) PodcastIcons.Forward else PodcastIcons.Replay,
                contentDescription = if (forward) strings.skipForward(seconds) else strings.skipBack(seconds),
                modifier = Modifier.size(size * 0.8f),
            )
            Text(
                "$seconds",
                fontSize = (size.value * 0.19f).sp,
                modifier = Modifier.padding(top = size * 0.1f),
            )
        }
    }
}

@Composable
fun PlayPauseButton(playing: Boolean, buffering: Boolean, onClick: () -> Unit, size: Dp = 48.dp, tonal: Boolean = false) {
    val strings = LocalStrings.current
    val content: @Composable () -> Unit = {
        if (buffering && playing) {
            LoadingIndicator(Modifier.size(size * 0.6f), color = LocalContentColor.current)
        } else {
            Icon(
                if (playing) PodcastIcons.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) strings.pause else strings.play,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
    // Material 3 Expressive (ADR 0013): round while paused, its corners squared off while playing.
    // Both states name their colors: a checked color left out is the text's, unreadable on the fill.
    val shapes = IconButtonDefaults.toggleableShapes()
    if (tonal) {
        val colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        FilledTonalIconToggleButton(
            checked = playing,
            onCheckedChange = { onClick() },
            modifier = Modifier.size(size),
            shapes = shapes,
            colors = colors,
            content = content,
        )
    } else {
        val colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            checkedContainerColor = MaterialTheme.colorScheme.primary,
            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
        )
        FilledIconToggleButton(
            checked = playing,
            onCheckedChange = { onClick() },
            modifier = Modifier.size(size),
            shapes = shapes,
            colors = colors,
            content = content,
        )
    }
}

@Composable
fun SpeedButton(speed: Float, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(LocalStrings.current.speed(speed), style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun NextButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(PodcastIcons.SkipNext, contentDescription = LocalStrings.current.next)
    }
}

/**
 * Position bar with elapsed and remaining time. While dragging, the thumb follows the finger
 * and the player is only asked to seek once released.
 */
@Composable
fun PositionSlider(positionSeconds: Double, durationSeconds: Double, onSeek: (Double) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val strings = LocalStrings.current
    val duration = durationSeconds.takeIf { it > 0 } ?: 1.0
    var dragging by remember { mutableStateOf<Float?>(null) }
    val position = (dragging?.toDouble() ?: positionSeconds).coerceIn(0.0, duration)
    Column(modifier) {
        Slider(
            value = position.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek(it.toDouble()) }
                dragging = null
            },
            valueRange = 0f..duration.toFloat(),
            enabled = enabled,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(strings.clock(position.toInt()), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text("-" + strings.clock((duration - position).toInt()), style = MaterialTheme.typography.labelMedium)
        }
    }
}
