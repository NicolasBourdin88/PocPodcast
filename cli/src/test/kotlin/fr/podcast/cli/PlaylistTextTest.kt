package fr.podcast.cli

import fr.podcast.shared.i18n.Language
import fr.podcast.shared.repository.FakePodcastRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaylistTextTest {
    private val client = FakePodcastRepository()
    private val french = Language.French.strings

    @Test
    fun ownPlaylistsComeFirstThenThoseSharedWithTheUser() = runBlocking {
        val entries = numberedPlaylists(client.playlists(), client.sharedWithMe())
        assertEquals(listOf("Kotlin", "Pour le train", "Android"), entries.map { it.playlist.title })

        val text = renderPlaylistsText(entries, french)
        assertContains(text, " 1. Kotlin — Dynamique · 2 épisodes\n    kotlin · Non écoutés seulement · Les plus récents d'abord · ≤ 50")
        assertContains(text, " 2. Pour le train — Statique · 2 épisodes")
        assertContains(text, "Partagées avec moi\n 3. Android — Partagée par Bob · Lecture seule · Statique · 2 épisodes\n    ✉ Pour découvrir")
        assertEquals("No playlists yet.", renderPlaylistsText(emptyList(), Language.English.strings))
    }

    @Test
    fun aPlaylistShowsItsEpisodesWithTheUsersFavoritesAndRatings() = runBlocking {
        val text = renderPlaylistText(1, client.playlist("pl2")!!, french)
        assertContains(text, " 1. Kotlin — Dynamique · 2 épisodes")
        assertContains(text, " 1. Compose Multiplatform for iOS is stable — Talking Kotlin\n    52 min · Nouveau · ★★★★☆")
        assertContains(text, " 2. LCC 330 - Les nouveautés de Kotlin 2.4 — Les Cast Codeurs\n    1 h 35 · 57 min restantes · Favori")
        assertFalse(text.contains("Flux RSS"))

        // Only its owner sees the feed, once published.
        val feed = client.publishFeed("pl1")
        assertContains(renderPlaylistText(2, client.playlist("pl1")!!, french), "    Flux RSS — $feed")
    }

    @Test
    fun anEmptyPlaylistSaysSo() = runBlocking {
        val created = client.createPlaylist("Vide")
        assertContains(renderPlaylistText(3, created, french), " 3. Vide — Statique · 0 épisode\n\nCette playlist est vide.")
    }
}
