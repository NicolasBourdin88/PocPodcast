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

class FakeLibraryTest {

    private val repository = FakePodcastRepository()

    @Test
    fun demoLibraryWorksWithoutServer() = runTest {
        val results = repository.search("kotlin")!!
        assertEquals(listOf("Talking Kotlin", "Kotlin Internals"), results.map { it.title })
        assertEquals(listOf(true, false), results.map { it.subscribed })

        val added = repository.subscribe("https://feeds.example.org/kotlin-internals.xml")
        assertEquals("Kotlin Internals", added.podcast.title)
        assertEquals(5, repository.subscriptionItems().size)

        val noted = repository.updateSubscription(added.subscription.id, "Pour le train", autoDownload = true)
        assertEquals("Pour le train", noted.subscription.note)

        repository.unsubscribe(added.subscription.id)
        assertEquals(4, repository.subscriptionItems().size)

        repository.keep("e1")
        repository.download("e1")
        val episode = repository.episode("e1")!!
        assertEquals(true, episode.kept)
        assertEquals(fr.podcast.shared.model.FileStatus.READY, episode.fileStatus)

        val report = repository.importOpml(repository.exportOpml() + """<outline xmlUrl="https://feeds.example.org/adb.xml"/>""")
        assertEquals(1, report.imported)
        assertEquals(4, report.alreadySubscribed)
    }
}

class FakeQueueTest {

    private val repository = FakePodcastRepository()

    @Test
    fun demoQueueKeepsItsOrder() = runTest {
        repository.enqueue("e1")
        repository.enqueue("e3")
        repository.enqueue("e5", position = 0)
        assertEquals(listOf("e5", "e1", "e3"), repository.queue().map { it.episode.id })
        assertEquals(true, repository.episodeItem("e1")!!.episode.inQueue)

        repository.moveInQueue("e5", 2)
        repository.markPlayed("e1") // played episodes leave the queue
        assertEquals(listOf("e3", "e5"), repository.queue().map { it.episode.id })
        assertEquals(false, repository.episodeItem("e1")!!.episode.inQueue)

        assertEquals("demo:e3", repository.episodeItem("e3")!!.episode.audioUrl)
        repository.clearQueue()
        assertEquals(emptyList(), repository.queue())
    }
}
