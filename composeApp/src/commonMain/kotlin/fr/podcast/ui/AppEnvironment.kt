package fr.podcast.ui

import fr.podcast.shared.account.KeyValueStore
import fr.podcast.shared.api.podcastHttpClient
import io.ktor.client.HttpClient

/** What each platform entry point provides to the shared UI. */
class AppEnvironment(
    /** Where accounts are saved (preferences, user defaults…). */
    val keyValueStore: KeyValueStore,
    /** Shown in the server's list of devices of the user. */
    val deviceName: String,
    /** Pre-filled server address; the Android emulator reaches the host at 10.0.2.2. */
    val defaultServerUrl: String,
    val httpClient: HttpClient = podcastHttpClient(),
)
