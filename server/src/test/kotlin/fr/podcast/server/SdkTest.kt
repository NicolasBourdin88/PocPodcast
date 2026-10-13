package fr.podcast.server

import fr.podcast.shared.account.AccountService
import fr.podcast.shared.api.ApiException
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.home.LoadHomeState
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.FileStatus
import fr.podcast.shared.repository.RemotePodcastRepository
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun sdkManagesTheLibrary() = testApplication {
        podcastServer()
        val account = AccountService(client, deviceName = "SDK test").signIn("http://localhost", TestSeed.ALICE, TestSeed.ALICE_PASSWORD)
        val repository = RemotePodcastRepository(account, client)

        val results = repository.search("kotlin")!!
        assertTrue(results.single { it.title == "Talking Kotlin" }.subscribed)

        val added = repository.subscribe("https://feeds.test/podcast20.xml")
        assertEquals("Le Podcast de Test", added.podcast.title)
        assertEquals(5, repository.subscriptionItems().size)

        val noted = repository.updateSubscription(added.subscription.id, "Pour le train", autoDownload = true)
        assertEquals("Pour le train", noted.subscription.note)
        assertTrue(noted.subscription.autoDownload)

        // Podcast 2.0 resources come as links.
        val episodes = repository.podcastEpisodes(added.podcast.id)
        val withExtras = episodes.first { it.title == "Épisode 3 : Podcast 2.0" }
        assertEquals("https://test.example.org/ep3/chapters.json", withExtras.chaptersUrl)
        assertEquals(listOf("text/vtt", "application/x-subrip"), withExtras.transcripts.map { it.type })
        assertEquals("fr", withExtras.transcripts.first().language)

        repository.keep(withExtras.id)
        repository.download(withExtras.id)
        val requested = repository.episode(withExtras.id)!!
        assertTrue(requested.kept)
        assertEquals(FileStatus.PENDING, requested.fileStatus)

        val opml = repository.exportOpml()
        assertTrue("https://feeds.test/podcast20.xml" in opml)
        val report = repository.importOpml(opml)
        assertEquals(0, report.imported)
        assertEquals(5, report.alreadySubscribed)

        repository.unsubscribe(added.subscription.id)
        assertEquals(4, repository.subscriptionItems().size)

        // Refusals carry their Problem Details type, turned into a message for people.
        val refused = assertFailsWith<ApiException.Rejected> { repository.subscribe("https://feeds.test/not-a-feed.html") }
        assertEquals("/problems/not-a-feed", refused.type)
        assertEquals(Language.French.strings.notAFeed, refused.userMessage(Language.French.strings))
    }

    @Test
    fun wrongCredentialsAreReported() = testApplication {
        podcastServer()

        assertFailsWith<ApiException.Unauthorized> {
            AccountService(client, "SDK test").signIn("http://localhost", TestSeed.ALICE, "wrong")
        }
    }
}
