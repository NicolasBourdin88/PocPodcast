package fr.podcast.server

import io.ktor.http.HttpHeaders
import io.ktor.client.request.header
import io.ktor.client.request.post
import fr.podcast.shared.account.AccountService
import fr.podcast.shared.api.ApiException
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.home.LoadHomeState
import fr.podcast.shared.model.Access
import fr.podcast.shared.model.EpisodeQuery
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.FileStatus
import fr.podcast.shared.model.PlaylistOrder
import fr.podcast.shared.model.PlaylistRules
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.model.UserRole
import fr.podcast.shared.repository.RemotePodcastRepository
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
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
    fun sdkCreatesAnAppPasswordForAGpodderClient() = testApplication {
        podcastServer()
        val account = AccountService(client, deviceName = "SDK test").signIn("http://localhost", TestSeed.ALICE, TestSeed.ALICE_PASSWORD)
        val repository = RemotePodcastRepository(account, client)

        val password = repository.createAppPassword("AntennaPod")
        assertTrue(repository.devices().any { it.name == "AntennaPod" })
        // What AntennaPod then sends (ADR 0011).
        val basic = "Basic " + java.util.Base64.getEncoder().encodeToString("${TestSeed.ALICE}:$password".toByteArray())
        val login = client.post("/api/2/auth/alice/login.json") { header(HttpHeaders.Authorization, basic) }
        assertEquals(HttpStatusCode.OK, login.status)
    }

    @Test
    fun sdkSearchesTagsAndCountsListening() = testApplication {
        podcastServer()
        val account = AccountService(client, deviceName = "SDK test").signIn("http://localhost", TestSeed.ALICE, TestSeed.ALICE_PASSWORD)
        val repository = RemotePodcastRepository(account, client)

        // Tags through the update template (ADR 0012); a change that leaves them out keeps them.
        val kotlin = repository.subscriptionItems().single { it.podcast.title == "Talking Kotlin" }
        val tagged = repository.updateSubscription(kotlin.subscription.id, null, autoDownload = false, tags = listOf("tech", "english"))
        assertEquals(listOf("english", "tech"), tagged.subscription.tags)
        assertEquals(listOf("english", "tech"), repository.updateSubscription(kotlin.subscription.id, "JetBrains", autoDownload = false).subscription.tags)

        // Every criterion through the root's episodes template, page by page.
        assertEquals(listOf("1", "9"), repository.searchEpisodes(EpisodeQuery("kotlin", status = EpisodeStatus.NEW, tag = "TECH")).items.map { it.episode.id })
        assertEquals(listOf("3", "4"), repository.searchEpisodes(EpisodeQuery(from = LocalDate(2026, 10, 1), to = LocalDate(2026, 10, 8))).items.map { it.episode.id })
        val first = repository.searchEpisodes(EpisodeQuery("kotlin"), limit = 2)
        val second = repository.searchEpisodes(EpisodeQuery("kotlin"), limit = 2, offset = 2)
        assertEquals(4, first.total)
        assertEquals(listOf("1", "2", "5", "9"), (first.items + second.items).map { it.episode.id })

        // Listening, through the me link.
        assertEquals(0, repository.listeningStats().totalSeconds)
        repository.updateProgress("7", positionSeconds = 15)
        repository.updateProgress("7", positionSeconds = 30)
        repository.markPlayed("7")
        val stats = repository.listeningStats()
        assertEquals(30, stats.totalSeconds)
        assertEquals(1, stats.episodesFinished)
        assertEquals("Fragmented", stats.byPodcast.single().podcastTitle)
        assertEquals(12, stats.byMonth.size)
    }

    @Test
    fun sdkPlaysFromTheQueue() = testApplication {
        podcastServer()
        val account = AccountService(client, deviceName = "SDK test").signIn("http://localhost", TestSeed.ALICE, TestSeed.ALICE_PASSWORD)
        val repository = RemotePodcastRepository(account, client)

        repository.enqueue("1")
        repository.enqueue("6")
        repository.enqueue("9", position = 0)
        assertEquals(listOf("9", "1", "6"), repository.queue().map { it.episode.id })
        repository.moveInQueue("9", 2)
        repository.dequeue("1")
        assertEquals(listOf("6", "9"), repository.queue().map { it.episode.id })
        assertTrue(repository.episode("6")!!.inQueue)

        // A fresh episode for the player: its podcast, and an absolute signed link that opens without the token.
        val item = repository.episodeItem("6")!!
        assertEquals("Les Cast Codeurs", item.podcast.title)
        val audio = item.episode.audioUrl!!
        assertTrue(audio.startsWith("http://localhost/episodes/6/audio?expires="), audio)
        val player = createClient { followRedirects = false }
        assertEquals(HttpStatusCode.Found, player.get(audio).status)

        repository.clearQueue()
        assertEquals(emptyList(), repository.queue())
    }

    @Test
    fun sdkManagesAccounts() = testApplication {
        podcastServer()
        val service = AccountService(client, deviceName = "SDK test")
        suspend fun signIn(username: String, password: String) = RemotePodcastRepository(service.signIn("http://localhost", username, password), client)
        val alice = signIn(TestSeed.ALICE, TestSeed.ALICE_PASSWORD)

        assertEquals(UserRole.ADMIN, alice.me().role)
        assertEquals("Alice B.", alice.updateProfile("Alice B.").displayName)
        // The avatar alone, through the template's options (ADR 0013); a new sign-in brings it.
        assertEquals("owl", alice.updateProfile(avatar = "owl").avatar)
        assertEquals("owl", service.signIn("http://localhost", TestSeed.ALICE, TestSeed.ALICE_PASSWORD).avatar)
        val carol = alice.createUser("carol", "Carol", "carol-password")
        assertEquals(listOf("Alice B.", "Bob", "Carol"), alice.users().map { it.displayName })
        val taken = assertFailsWith<ApiException.Rejected> { alice.createUser("carol", "Carol", "carol-password") }
        assertEquals(Language.English.strings.usernameUnavailable, taken.userMessage(Language.English.strings))
        assertEquals(UserRole.ADMIN, alice.updateUser(carol.id, role = UserRole.ADMIN).role)
        assertEquals("Carol", alice.updateUser(carol.id, role = UserRole.USER).displayName) // the name is kept

        alice.resetPassword(carol.id, "new-carol-password")
        val carolSession = signIn("carol", "new-carol-password")
        alice.deleteUser(carol.id)
        assertFailsWith<ApiException.Unauthorized> { carolSession.me() }

        // Bob is not an administrator: the server does not offer him these actions.
        val bob = signIn(TestSeed.BOB, TestSeed.BOB_PASSWORD)
        assertFailsWith<ApiException.NotOffered> { bob.createUser("dave", "Dave", "dave-password") }

        // A new password: the device that sets it stays signed in, the others are signed out.
        val laptop = signIn(TestSeed.BOB, TestSeed.BOB_PASSWORD)
        assertEquals(listOf(true, false), laptop.devices().map { it.current }) // the most recently used first
        val wrong = assertFailsWith<ApiException.Rejected> { laptop.changePassword("wrong-password", "bob-new-password") }
        assertEquals(Language.French.strings.wrongPassword, wrong.userMessage(Language.French.strings))
        laptop.changePassword(TestSeed.BOB_PASSWORD, "bob-new-password")
        assertFailsWith<ApiException.Unauthorized> { bob.me() }
        val device = laptop.devices().single()
        assertTrue(device.current)
    }

    @Test
    fun sdkShares() = testApplication {
        podcastServer()
        val service = AccountService(client, deviceName = "SDK test")
        val alice = RemotePodcastRepository(service.signIn("http://localhost", TestSeed.ALICE, TestSeed.ALICE_PASSWORD), client)
        val bob = RemotePodcastRepository(service.signIn("http://localhost", TestSeed.BOB, TestSeed.BOB_PASSWORD), client)

        // Alice shares Les Cast Codeurs read-only, then lets Bob modify it.
        val cast = alice.subscriptionItems().single { it.podcast.title == "Les Cast Codeurs" }
        val share = alice.shareSubscription(cast.subscription.id, bob.me().id, message = "Le meilleur podcast Java")
        val received = bob.sharedWithMe().single()
        assertEquals("Alice", received.share.owner.displayName)
        assertEquals("Le meilleur podcast Java", received.share.message)
        assertEquals(Access.READ, received.subscription!!.subscription.access)
        assertFailsWith<ApiException.NotOffered> { bob.updateSubscription(cast.subscription.id, "Mine", autoDownload = false) }

        assertEquals(SharePermission.WRITE, alice.changePermission(share.id, SharePermission.WRITE).permission)
        val noted = bob.updateSubscription(cast.subscription.id, "Écouté en famille", autoDownload = false)
        assertEquals(Access.WRITE, noted.subscription.access)
        assertEquals("Écouté en famille", alice.subscription(cast.subscription.id)!!.subscription.note)
        assertEquals(listOf("Bob"), alice.sharesOf(cast.subscription.id).map { it.grantee.displayName })

        // Bob recommends one of its episodes back; Alice declines.
        val episode = bob.podcastEpisodes(cast.podcast.id).first()
        val recommendation = bob.recommend(episode.id, alice.me().id, "À partir de 12 min")
        assertEquals(episode.id, alice.sharedWithMe().single().episode!!.episode.id)
        alice.unshare(recommendation.id)
        assertTrue(alice.sharedWithMe().isEmpty())

        // Alice takes her share back: the subscription is out of Bob's sight.
        alice.unshare(share.id)
        assertNull(bob.subscription(cast.subscription.id))
    }

    @Test
    fun sdkManagesPlaylists() = testApplication {
        podcastServer()
        val service = AccountService(client, deviceName = "SDK test")
        val alice = RemotePodcastRepository(service.signIn("http://localhost", TestSeed.ALICE, TestSeed.ALICE_PASSWORD), client)
        val bob = RemotePodcastRepository(service.signIn("http://localhost", TestSeed.BOB, TestSeed.BOB_PASSWORD), client)

        val train = alice.createPlaylist("Pour le train", "Les trajets").playlist.id
        alice.addToPlaylist(train, "6")
        alice.addToPlaylist(train, "2", position = 0)
        alice.moveInPlaylist(train, "6", 0)
        assertEquals(listOf("6", "2"), alice.playlist(train)!!.episodes.map { it.episode.id })
        alice.removeFromPlaylist(train, "2")

        // A dynamic playlist on Alice's marks.
        alice.setFavorite("7", true)
        alice.rate("7", 5)
        assertEquals(5, alice.episode("7")!!.rating)
        val favorites = alice.createPlaylist("Favoris", rules = PlaylistRules(favoritesOnly = true, minRating = 4))
        assertEquals(listOf("7"), favorites.episodes.map { it.episode.id })
        val renamed = alice.updatePlaylist(favorites.playlist.id, "Mes favoris", null, PlaylistRules(favoritesOnly = true))
        assertEquals("Mes favoris", renamed.playlist.title)
        alice.rate("7", null)
        assertNull(alice.episode("7")!!.rating)
        val random = alice.createPlaylist("Surprise", rules = PlaylistRules(order = PlaylistOrder.RANDOM))
        assertEquals(random.episodes.size, alice.shufflePlaylist(random.playlist.id).episodes.size)

        // Shared with Bob, read-only; published for any podcast app.
        alice.sharePlaylist(train, bob.me().id)
        assertEquals("Pour le train", bob.sharedWithMe().single().playlist!!.title)
        assertFailsWith<ApiException.NotOffered> { bob.addToPlaylist(train, "1") }
        assertEquals(listOf("Bob"), alice.sharesOfPlaylist(train).map { it.grantee.displayName })
        val feed = alice.publishFeed(train)
        assertTrue(feed.startsWith("http://localhost/feeds/"), feed)
        assertEquals(HttpStatusCode.OK, client.get(feed).status)
        alice.unpublishFeed(train)
        assertNull(alice.playlist(train)!!.playlist.feedUrl)

        alice.deletePlaylist(favorites.playlist.id)
        assertNull(alice.playlist(favorites.playlist.id))
        assertEquals(listOf("Pour le train", "Surprise"), alice.playlists().map { it.title })
    }

    @Test
    fun wrongCredentialsAreReported() = testApplication {
        podcastServer()
        val service = AccountService(client, "SDK test")

        repeat(5) { assertFailsWith<ApiException.Unauthorized> { service.signIn("http://localhost", TestSeed.ALICE, "wrong") } }
        // Then even the right password waits, and the user is told how long.
        val locked = assertFailsWith<ApiException.TooManyAttempts> { service.signIn("http://localhost", TestSeed.ALICE, TestSeed.ALICE_PASSWORD) }
        assertEquals(Language.English.strings.tooManyAttempts(15), locked.userMessage(Language.English.strings))
    }
}
