package fr.podcast.shared.repository

import fr.podcast.shared.api.ApiException
import fr.podcast.shared.model.EpisodeQuery
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.TagRules
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Search, tags and statistics in the demo, as a server answers them (ADR 0012). */
class FakeComfortTest {

    @Test
    fun tagsAreTrimmedAndMergedRegardlessOfCase() {
        assertEquals(listOf("trajets", "Tech"), TagRules.parse(" trajets, Tech , tech,, "))
        assertTrue(TagRules.valid(listOf("a", "b")))
        assertFalse(TagRules.valid((1..11).map { "t$it" }))
        assertFalse(TagRules.valid(listOf("x".repeat(31))))
        // In alphabetical order, case and accents aside.
        assertEquals(listOf("Dev", "économie", "Éducation", "english", "œuvres", "tech"), TagRules.sorted(listOf("tech", "english", "économie", "Éducation", "œuvres", "Dev", "TECH")))
    }

    @Test
    fun theDemoSearchesLikeTheServer() = runTest {
        val repository = FakePodcastRepository()
        suspend fun ids(query: EpisodeQuery) = repository.searchEpisodes(query).items.map { it.episode.id }

        assertEquals(listOf("e4"), ids(EpisodeQuery(tag = "CULTURE")))
        assertEquals(listOf("e2", "e4"), ids(EpisodeQuery(status = EpisodeStatus.IN_PROGRESS)))
        assertEquals(listOf("e2"), ids(EpisodeQuery(favoritesOnly = true)))
        assertEquals(listOf("e1"), ids(EpisodeQuery(minRating = 4)))
        // Every word, in the title or the podcast's.
        assertEquals(listOf("e1", "e5"), ids(EpisodeQuery(text = "talking KOTLIN")))
        assertEquals(1, repository.searchEpisodes(EpisodeQuery(status = EpisodeStatus.IN_PROGRESS), limit = 1).items.size)
        assertEquals(2, repository.searchEpisodes(EpisodeQuery(status = EpisodeStatus.IN_PROGRESS), limit = 1).total)
    }

    @Test
    fun tagsAreChangedWithTheNote() = runTest {
        val repository = FakePodcastRepository()
        val updated = repository.updateSubscription("s3", note = null, autoDownload = false, tags = listOf("culture", " Science "))
        assertEquals(listOf("culture", "Science"), updated.subscription.tags)
        // Null keeps them.
        assertEquals(listOf("culture", "Science"), repository.updateSubscription("s3", "Le lundi", false).subscription.tags)
        assertFailsWith<ApiException.Rejected> { repository.updateSubscription("s3", null, false, tags = listOf("a,b")) }
    }

    @Test
    fun theDemoHasListened() = runTest {
        val stats = FakePodcastRepository().listeningStats()
        // Played: 61 min; in progress: 38 and 25 min.
        assertEquals((61 + 38 + 25) * 60L, stats.totalSeconds)
        assertEquals(1, stats.episodesFinished)
        assertEquals(12, stats.byMonth.size)
        assertEquals("2026-10", stats.byMonth.last().month)
        assertEquals("Talking Kotlin", stats.byPodcast.first().podcastTitle)
    }
}
