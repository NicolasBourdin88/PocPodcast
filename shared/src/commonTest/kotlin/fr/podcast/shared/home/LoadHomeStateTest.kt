package fr.podcast.shared.home

import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.repository.FakePodcastRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoadHomeStateTest {

    private val loadHomeState = LoadHomeState(FakePodcastRepository())

    @Test
    fun resumeContainsOnlyEpisodesInProgress() = runTest {
        val state = loadHomeState()

        assertEquals(listOf("e2", "e4"), state.resume.map { it.episode.id })
        assertTrue(state.resume.all { it.episode.status == EpisodeStatus.IN_PROGRESS })
    }

    @Test
    fun latestEpisodesAreJoinedWithTheirPodcast() = runTest {
        val state = loadHomeState()

        assertEquals(5, state.latest.size)
        assertTrue(state.latest.all { it.episode.podcastId == it.podcast.id })
    }

    @Test
    fun latestRespectsTheLimit() = runTest {
        val state = LoadHomeState(FakePodcastRepository(), latestLimit = 2)()

        assertEquals(listOf("e1", "e2"), state.latest.map { it.episode.id })
    }

    @Test
    fun episodeComputedProperties() = runTest {
        val episode = loadHomeState().resume.first().episode // 38 min out of 95

        assertEquals(0.4f, episode.progress, absoluteTolerance = 0.001f)
        assertEquals(57, episode.remainingMinutes)
    }
}
