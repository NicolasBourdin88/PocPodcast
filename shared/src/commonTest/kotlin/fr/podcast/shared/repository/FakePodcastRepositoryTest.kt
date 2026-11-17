package fr.podcast.shared.repository

import fr.podcast.shared.api.ApiException
import fr.podcast.shared.api.userMessage
import fr.podcast.shared.i18n.EnglishStrings
import fr.podcast.shared.i18n.FrenchStrings
import fr.podcast.shared.model.Access
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.model.ShareType
import fr.podcast.shared.model.UserRole
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

class FakeSharingTest {

    private val repository = FakePodcastRepository()

    @Test
    fun whatOthersShareBehavesAsOnAServer() = runTest {
        val received = repository.sharedWithMe()
        assertEquals(listOf(ShareType.SUBSCRIPTION, ShareType.EPISODE), received.map { it.share.type })

        // Alice's subscription, read-only: it can be listened to, not changed.
        val methode = received.first().subscription!!
        assertEquals(Access.READ, methode.subscription.access)
        assertEquals("Alice", methode.subscription.ownerName)
        assertEquals(listOf("e6", "e7"), repository.podcastEpisodes(methode.podcast.id).map { it.id })
        val refused = assertFailsWith<ApiException.Rejected> { repository.updateSubscription(methode.subscription.id, "Mine", false) }
        assertEquals(FrenchStrings.notAllowed, refused.userMessage(FrenchStrings))
        assertFailsWith<ApiException.Rejected> { repository.unsubscribe(methode.subscription.id) }

        // Neither in the library nor among the latest episodes, until one follows it oneself.
        assertTrue(repository.subscriptionItems().none { it.podcast.id == methode.podcast.id })
        assertTrue(repository.latestEpisodes(10).none { it.podcastId == methode.podcast.id })
        repository.subscribe(methode.podcast.feedUrl)
        assertTrue(repository.latestEpisodes(10).any { it.podcastId == methode.podcast.id })

        // Bob's recommendation, declined.
        assertEquals("KMP in production", received[1].episode!!.episode.title)
        assertEquals("Bob", received[1].share.owner.displayName)
        repository.unshare(received[1].share.id)
        assertEquals(1, repository.sharedWithMe().size)
    }

    @Test
    fun theDemoUserSharesAndRecommends() = runTest {
        assertEquals(listOf("Bob"), repository.sharesOf("s2").map { it.grantee.displayName })
        val alice = repository.users().single { it.username == "alice" }

        val share = repository.shareSubscription("s1", alice.id, SharePermission.WRITE, "Pour toi")
        repository.shareSubscription("s1", alice.id) // again: replaced, not added
        assertEquals(listOf(SharePermission.READ), repository.sharesOf("s1").map { it.permission })
        assertEquals(SharePermission.WRITE, repository.changePermission(share.id, SharePermission.WRITE).permission)

        val recommendation = repository.recommend("e1", alice.id, "Écoute ça")
        assertEquals(SharePermission.READ, recommendation.permission)
        assertFailsWith<ApiException.Rejected> { repository.changePermission(recommendation.id, SharePermission.WRITE) }

        val toMyself = assertFailsWith<ApiException.Rejected> { repository.shareSubscription("s1", repository.me().id) }
        assertEquals(EnglishStrings.noSuchUser, toMyself.userMessage(EnglishStrings))

        // Shares go with what they share.
        repository.unsubscribe("s1")
        assertFailsWith<ApiException.NotFound> { repository.sharesOf("s1") }
        assertEquals(1, repository.sharesOf("s2").size)
    }
}

class FakeAccountsTest {

    private val repository = FakePodcastRepository()

    @Test
    fun theDemoUserAdministersTheServer() = runTest {
        assertEquals(UserRole.ADMIN, repository.me().role)
        assertEquals(listOf("Alice", "Bob", "Demo"), repository.users().map { it.displayName })

        val carol = repository.createUser("carol", "Carol", "carol-password")
        fun field(refused: ApiException.Rejected) = refused.errors.single().field
        assertEquals("username", field(assertFailsWith { repository.createUser("carol", "Carol", "carol-password") }))
        assertEquals("password", field(assertFailsWith { repository.createUser("dave", "Dave", "short") }))
        assertEquals(UserRole.ADMIN, repository.updateUser(carol.id, role = UserRole.ADMIN).role)
        repository.updateUser(carol.id, role = UserRole.USER)
        assertEquals("role", field(assertFailsWith { repository.updateUser(repository.me().id, role = UserRole.USER) }))
        repository.deleteUser(carol.id)
        assertFailsWith<ApiException.Rejected> { repository.deleteUser(repository.me().id) }

        assertEquals("Démo", repository.updateProfile(" Démo ").displayName)
        assertEquals(3, repository.devices().size)
        repository.revokeDevice("d3")
        assertFailsWith<ApiException.NotFound> { repository.revokeDevice("d3") }
        // A new password signs the other devices out.
        repository.changePassword("anything", "new-password")
        assertEquals(listOf(true), repository.devices().map { it.current })
    }

    @Test
    fun aDeletedAccountTakesItsShares() = runTest {
        repository.deleteUser(repository.users().single { it.username == "bob" }.id)
        assertEquals(listOf(ShareType.SUBSCRIPTION), repository.sharedWithMe().map { it.share.type })
        assertTrue(repository.sharesOf("s2").isEmpty())
    }
}
