package fr.podcast.player

// The Mac has no audio session: AVPlayer plays as it is.
internal actual fun configureAudioSession() = Unit

internal actual fun activateAudioSession() = Unit
