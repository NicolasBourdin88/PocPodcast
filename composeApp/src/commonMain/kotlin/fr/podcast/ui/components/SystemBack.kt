package fr.podcast.ui.components

import androidx.compose.runtime.Composable

/** The system back gesture or button, where the platform has one (Android). */
@Composable
expect fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit)
