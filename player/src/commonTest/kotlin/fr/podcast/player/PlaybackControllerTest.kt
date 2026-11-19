package fr.podcast.player

import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.repository.PodcastClient
import fr.podcast.shared.api.ApiException
import fr.podcast.shared.repository.FakePodcastRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A player driven by the test: it records what it is asked and plays only when told. */
private class ManualAudioPlayer : AudioPlayer {
    override val state = MutableStateFlow(PlayerState())
    val loads = mutableListOf<Pair<Media, Double>>()

    /** Like AVPlayer before its item is ready: still opening, at 0, the start not applied yet. */
    var opening = false

    override fun load(media: Media, startSeconds: Double, play: Boolean) {
        loads += media to startSeconds
        state.value = if (opening) {
            PlayerState(media.id, PlayerStatus.LOADING, false, positionSeconds = 0.0, durationSeconds = media.durationHint)
        } else {
            PlayerState(media.id, PlayerStatus.READY, play, positionSeconds = startSeconds, durationSeconds = media.durationHint)
        }
    }

    override fun play() = state.update { it.copy(playing = true) }
    override fun pause() = state.update { it.copy(playing = false) }
    override fun seekTo(seconds: Double) = state.update { it.copy(positionSeconds = seconds) }
    override fun setSpeed(speed: Float) = state.update { it.copy(speed = speed) }
    override fun stop() = state.update { PlayerState() }
    override fun release() = Unit

    fun advance(seconds: Double) = state.update { it.copy(positionSeconds = it.positionSeconds + seconds) }
    fun end() = state.update { it.copy(status = PlayerStatus.ENDED, playing = false) }
    fun fail(message: String) = state.update { it.copy(status = PlayerStatus.ERROR, playing = false, error = message) }
}

/** The demo client, whose network can go out. */
private class FlakyClient(private val repository: FakePodcastRepository) : PodcastClient by repository {
    var offline = false

    override suspend fun updateProgress(episodeId: String, positionSeconds: Int) {
        if (offline) throw ApiException.Unreachable(IllegalStateException("No network"))
        repository.updateProgress(episodeId, positionSeconds)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {

    private val player = ManualAudioPlayer()
    private val repository = FakePodcastRepository()

    private fun TestScope.controller() = PlaybackController(player, backgroundScope).also {
        it.attach(repository)
        runCurrent()
    }

    @Test
    fun playingReadsTheEpisodeAgainAndTakesItOutOfTheQueue() = runTest {
        repository.enqueue("e1")
        repository.enqueue("e3")
        val controller = controller()
        assertEquals(listOf("e1", "e3"), controller.queue.value.map { it.episode.id })

        controller.play("e2") // in progress at 38 min
        runCurrent()
        val (media, start) = player.loads.single()
        assertEquals("demo:e2", media.url)
        assertEquals("Les Cast Codeurs", media.artist)
        assertEquals(38 * 60.0, start)
        assertEquals("e2", controller.nowPlaying.value!!.item.episode.id)

        controller.play("e1")
        runCurrent()
        assertEquals(listOf("e3"), controller.queue.value.map { it.episode.id })
    }

    @Test
    fun thePositionReachesTheServerWhilePlayingAndOnPause() = runTest {
        val controller = controller()
        controller.play("e1")
        runCurrent()

        player.advance(20.0)
        advanceTimeBy(15.seconds)
        runCurrent()
        assertEquals(20, repository.episode("e1")!!.positionSeconds)

        player.advance(5.0)
        controller.pause()
        runCurrent()
        assertEquals(25, repository.episode("e1")!!.positionSeconds)
        assertEquals(EpisodeStatus.IN_PROGRESS, repository.episode("e1")!!.status)

        // A seek while paused is sent once the seek bar is let go.
        controller.seekTo(600.0)
        runCurrent()
        assertEquals(25, repository.episode("e1")!!.positionSeconds)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(600, repository.episode("e1")!!.positionSeconds)
    }

    @Test
    fun positionsWaitForTheNetwork() = runTest {
        val client = FlakyClient(repository)
        val controller = PlaybackController(player, backgroundScope).also {
            it.attach(client)
            runCurrent()
        }
        val failures = mutableListOf<Throwable>()
        backgroundScope.launch { controller.failures.toList(failures) }
        controller.play("e1")
        runCurrent()

        client.offline = true
        player.advance(20.0)
        advanceTimeBy(15.seconds)
        runCurrent()
        player.advance(10.0)
        controller.pause()
        runCurrent()
        assertEquals(0, repository.episode("e1")!!.positionSeconds)
        // The loss of network is said once, not at every position.
        assertEquals(1, failures.size)

        // The latest position goes at the first sync the server answers.
        client.offline = false
        advanceTimeBy(15.seconds)
        runCurrent()
        assertEquals(30, repository.episode("e1")!!.positionSeconds)
    }

    @Test
    fun aFileStillOpeningDoesNotOverwriteTheSavedPosition() = runTest {
        val controller = controller()
        val saved = repository.episode("e2")!!.positionSeconds
        player.opening = true
        controller.play("e2")
        runCurrent()

        // The app goes to the background, or closes, before the file opened.
        controller.saveNow()
        runCurrent()
        controller.flush()
        assertEquals(saved, repository.episode("e2")!!.positionSeconds)
    }

    @Test
    fun aPlaylistPlaysInItsOrderBeforeTheQueue() = runTest {
        repository.enqueue("e4")
        val controller = controller()

        controller.playAll(listOf("e5", "e1", "e3"))
        runCurrent()
        assertEquals("e5", controller.nowPlaying.value!!.item.episode.id)
        assertEquals(listOf("e1", "e3", "e4"), controller.queue.value.map { it.episode.id })
    }

    @Test
    fun theNextEpisodeOfTheQueueFollows() = runTest {
        repository.enqueue("e3")
        val controller = controller()
        controller.play("e1")
        runCurrent()

        player.end()
        runCurrent()
        assertEquals(EpisodeStatus.PLAYED, repository.episode("e1")!!.status)
        assertEquals("e3", player.loads.last().first.id)
        assertEquals(emptyList(), controller.queue.value)
    }

    @Test
    fun aFailedFileIsOpenedAgainOnceWithAFreshLink() = runTest {
        val controller = controller()
        val failures = mutableListOf<Throwable>()
        backgroundScope.launch { controller.failures.toList(failures) }
        controller.play("e1")
        runCurrent()

        player.advance(100.0)
        player.fail("HTTP 403")
        runCurrent()
        assertEquals(2, player.loads.size)
        assertEquals(100.0, player.loads.last().second) // where it stopped
        assertEquals(emptyList(), failures)

        player.fail("HTTP 403 again")
        runCurrent()
        assertTrue(failures.single() is PlaybackException)
    }

    @Test
    fun anotherAccountStopsPlayback() = runTest {
        val controller = controller()
        controller.play("e1")
        runCurrent()

        controller.attach(FakePodcastRepository())
        runCurrent()
        assertNull(controller.nowPlaying.value)
        assertEquals(PlayerStatus.IDLE, player.state.value.status)
    }

    @Test
    fun speedsGoRound() = runTest {
        val controller = controller()
        controller.play("e1")
        runCurrent()
        repeat(2) { controller.cycleSpeed() }
        assertEquals(1.5f, player.state.value.speed)
        repeat(3) { controller.cycleSpeed() }
        assertEquals(0.8f, player.state.value.speed)
    }

    @Test
    fun theSimulatedPlayerMovesWithTime() = runTest {
        val simulated = SimulatedAudioPlayer(backgroundScope)
        simulated.load(Media("e1", "demo:e1", "Title", "Podcast", durationHint = 10.0), startSeconds = 0.0, play = true)
        advanceTimeBy(2_100)
        assertEquals(2.0, simulated.state.value.positionSeconds, 0.01)

        simulated.setSpeed(2f)
        advanceTimeBy(1_000)
        assertEquals(4.0, simulated.state.value.positionSeconds, 0.01)

        advanceTimeBy(5_000)
        assertEquals(PlayerStatus.ENDED, simulated.state.value.status)
        assertEquals(10.0, simulated.state.value.positionSeconds)
    }
}
