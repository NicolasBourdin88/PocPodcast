package fr.podcast.shared.repository

import fr.podcast.shared.model.EpisodeStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FakePodcastRepositoryTest {

    private val repository = FakePodcastRepository()

    @Test
    fun progressAndPlayedStateAreKept() = runTest {
        repository.updateProgress("e1", positionSeconds = 120)
        assertEquals(EpisodeStatus.IN_PROGRESS, repository.episode("e1")!!.status)
        assertEquals(listOf("e1", "e2", "e4"), repository.inProgress().map { it.id })

        repository.markPlayed("e1")
        assertEquals(EpisodeStatus.PLAYED, repository.episode("e1")!!.status)

        repository.markUnplayed("e1")
        assertEquals(0, repository.episode("e1")!!.positionSeconds)
        assertEquals(EpisodeStatus.NEW, repository.episode("e1")!!.status)
    }
}
