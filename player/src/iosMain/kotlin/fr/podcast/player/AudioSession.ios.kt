package fr.podcast.player

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.setActive

@OptIn(ExperimentalForeignApi::class)
internal actual fun configureAudioSession() {
    AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, mode = AVAudioSessionModeSpokenAudio, options = 0u, error = null)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun activateAudioSession() {
    AVAudioSession.sharedInstance().setActive(true, error = null)
}
