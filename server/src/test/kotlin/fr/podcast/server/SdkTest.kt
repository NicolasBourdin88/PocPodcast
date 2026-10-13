package fr.podcast.server

import fr.podcast.shared.account.AccountService
import fr.podcast.shared.api.ApiException
import fr.podcast.shared.home.LoadHomeState
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.repository.RemotePodcastRepository
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The :shared SDK against the real server: the two sides of the contract together. */
class SdkTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    @Test
    fun sdkSignsInLoadsTheHomeAndActs() = testApplication {
        podcastServer()
        val accounts = AccountService(client, deviceName = "SDK test")

        val account = accounts.signIn("http://localhost", TestSeed.ALICE, TestSeed.ALICE_PASSWORD)
        assertEquals("Podcast (test)", account.serverName)
        assertEquals("Alice", account.displayName)

        val repository = RemotePodcastRepository(account, client)
        val home = LoadHomeState(repository)()
        assertEquals(listOf("LCC 330 - Les nouveautés de Kotlin 2.4", "Qui a inventé le copier-coller ?"), home.resume.map { it.episode.title })
        assertEquals("Les Cast Codeurs", home.resume.first().podcast.title)
        assertEquals(4, repository.subscriptions().size)
        assertEquals("Talking Kotlin", repository.podcast("2")!!.title)
        assertNull(repository.episode("999"))

        // Actions go through the HAL-FORMS templates.
        repository.updateProgress("1", positionSeconds = 300)
        assertEquals(EpisodeStatus.IN_PROGRESS, repository.episode("1")!!.status)
        repository.markPlayed("1")
        assertEquals(EpisodeStatus.PLAYED, repository.episode("1")!!.status)
        repository.markPlayed("1") // no template any more: nothing happens
        repository.markUnplayed("1")
        assertEquals(EpisodeStatus.NEW, repository.episode("1")!!.status)

        accounts.signOut(account)
        assertFailsWith<ApiException.Unauthorized> { RemotePodcastRepository(account, client).subscriptions() }
    }

    @Test
    fun wrongCredentialsAreReported() = testApplication {
        podcastServer()

        assertFailsWith<ApiException.Unauthorized> {
            AccountService(client, "SDK test").signIn("http://localhost", TestSeed.ALICE, "wrong")
        }
    }
}
