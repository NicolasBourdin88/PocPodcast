package fr.podcast.shared.repository

import fr.podcast.shared.api.ApiException
import fr.podcast.shared.api.FieldError
import fr.podcast.shared.home.EpisodeItem
import fr.podcast.shared.model.Access
import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.FileStatus
import fr.podcast.shared.model.OpmlImportReport
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.model.SearchResult
import fr.podcast.shared.model.Share
import fr.podcast.shared.model.SharePermission
import fr.podcast.shared.model.ShareRules
import fr.podcast.shared.model.ShareType
import fr.podcast.shared.model.Subscription
import fr.podcast.shared.model.SubscriptionItem
import fr.podcast.shared.repository.FakeAccountRepository.Companion.ALICE
import fr.podcast.shared.repository.FakeAccountRepository.Companion.BOB
import fr.podcast.shared.repository.FakeAccountRepository.Companion.ME
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * In-memory data, used by the demo mode and the tests. Nothing leaves the device.
 * Besides the demo user's library, Alice shares a subscription with them and Bob recommends an
 * episode; the demo user shares Talking Kotlin with Bob (ADR 0009).
 */
class FakePodcastRepository private constructor(
    private val accounts: FakeAccountRepository,
) : PodcastClient, AccountRepository by accounts {

    constructor() : this(FakeAccountRepository())

    private val mutex = Mutex()
    private val subscribedAt = Instant.parse("2026-09-01T08:00:00Z")

    private val podcasts = mutableListOf(
        Podcast("p1", "Les Cast Codeurs", "Emmanuel Bernard & co", "https://lescastcodeurs.libsyn.com/rss"),
        Podcast("p2", "Talking Kotlin", "JetBrains", "https://talkingkotlin.com/feed.xml"),
        Podcast("p3", "Le Code a changé", "France Inter", "https://radiofrance-podcast.net/podcast09/rss_20786.xml"),
        Podcast("p4", "Fragmented", "Donn Felker & Kaushik Gopal", "https://feeds.simplecast.com/LpAGSLnY"),
        // Followed by Alice only.
        Podcast("p5", "La Méthode scientifique", "France Culture", "https://feeds.example.org/methode.xml"),
    )

    /** Subscription, podcast id and owner. */
    private data class Followed(val subscription: Subscription, val podcastId: String, val ownerId: String)

    private val subscriptions = mutableListOf(
        Followed(Subscription("s1", subscribedAt = subscribedAt), "p1", ME),
        Followed(Subscription("s2", subscribedAt = subscribedAt), "p2", ME),
        Followed(Subscription("s3", subscribedAt = subscribedAt), "p3", ME),
        Followed(Subscription("s4", subscribedAt = subscribedAt), "p4", ME),
        Followed(Subscription("s5", note = "Une question de science par semaine", subscribedAt = subscribedAt), "p5", ALICE),
    )
    private var nextSubscription = 6

    // Ordered from most recent to oldest.
    private val episodes = mutableListOf(
        Episode("e1", "p2", "Compose Multiplatform for iOS is stable", Instant.parse("2026-10-12T06:00:00Z"), durationSeconds = 52 * 60),
        Episode("e6", "p5", "Les trous noirs ont-ils une mémoire ?", Instant.parse("2026-10-11T15:00:00Z"), durationSeconds = 58 * 60),
        Episode(
            "e2", "p1", "LCC 330 - Les nouveautés de Kotlin 2.4", Instant.parse("2026-10-10T05:00:00Z"),
            durationSeconds = 95 * 60, positionSeconds = 38 * 60, status = EpisodeStatus.IN_PROGRESS,
        ),
        Episode("e3", "p4", "KMP in production", Instant.parse("2026-10-08T12:00:00Z"), durationSeconds = 47 * 60),
        Episode(
            "e4", "p3", "Qui a inventé le copier-coller ?", Instant.parse("2026-10-05T04:30:00Z"),
            durationSeconds = 34 * 60, positionSeconds = 25 * 60, status = EpisodeStatus.IN_PROGRESS,
        ),
        Episode("e7", "p5", "Pourquoi le ciel est-il bleu ?", Instant.parse("2026-10-04T15:00:00Z"), durationSeconds = 55 * 60),
        Episode(
            "e5", "p2", "Ktor 3 and server-side Kotlin", Instant.parse("2026-09-28T06:00:00Z"),
            durationSeconds = 61 * 60, positionSeconds = 61 * 60, status = EpisodeStatus.PLAYED,
        ),
    ).map { it.copy(audioUrl = "$DEMO_AUDIO${it.id}") }.toMutableList()

    /** Episode ids, in listening order. */
    private val queueIds = mutableListOf<String>()

    /** A share, by the ids of what it shares and of its two users. */
    private data class DemoShare(
        val id: String,
        val type: ShareType,
        val resourceId: String,
        val ownerId: String,
        val granteeId: String,
        val permission: SharePermission,
        val message: String?,
        val createdAt: Instant,
    )

    private val shares = mutableListOf(
        DemoShare("sh1", ShareType.SUBSCRIPTION, "s5", ALICE, ME, SharePermission.READ, "Pour tes trajets", Instant.parse("2026-10-11T19:00:00Z")),
        DemoShare("sh2", ShareType.EPISODE, "e3", BOB, ME, SharePermission.READ, "À écouter à partir de 12 min", Instant.parse("2026-10-09T08:30:00Z")),
        DemoShare("sh3", ShareType.SUBSCRIPTION, "s2", ME, BOB, SharePermission.WRITE, null, Instant.parse("2026-10-01T10:00:00Z")),
    )
    private var nextShare = 4

    /** A small directory for the demo search. */
    private val directory = listOf(
        SearchResult("Talking Kotlin", "JetBrains", "https://talkingkotlin.com/feed.xml", source = "demo"),
        SearchResult("Kotlin Internals", "crashcasts.com", "https://feeds.example.org/kotlin-internals.xml", source = "demo"),
        SearchResult("Android Developers Backstage", "Android Developers", "https://feeds.example.org/adb.xml", source = "demo"),
        SearchResult("Podnews Weekly Review", "Podnews LLC", "https://feeds.example.org/podnews.xml", source = "demo"),
        SearchResult("La Méthode scientifique", "France Culture", "https://feeds.example.org/methode.xml", source = "demo"),
    )

    // --- PodcastRepository ---

    override suspend fun subscriptions(): List<Podcast> = subscriptionItems().map { it.podcast }

    override suspend fun podcast(id: String): Podcast? = mutex.withLock { podcasts.find { it.id == id } }

    override suspend fun episode(id: String): Episode? = mutex.withLock { episodes.find { it.id == id } }

    /** As on a server: the episodes of the podcasts the user follows. */
    override suspend fun latestEpisodes(limit: Int): List<Episode> = mutex.withLock {
        val followed = subscriptions.filter { it.ownerId == ME }.map { it.podcastId }.toSet()
        episodes.filter { it.podcastId in followed }.take(limit)
    }

    override suspend fun inProgress(): List<Episode> =
        mutex.withLock { episodes.filter { it.status == EpisodeStatus.IN_PROGRESS } }

    override suspend fun updateProgress(episodeId: String, positionSeconds: Int) =
        update(episodeId) { it.copy(positionSeconds = positionSeconds, status = EpisodeStatus.IN_PROGRESS) }

    override suspend fun markPlayed(episodeId: String) {
        update(episodeId) { it.copy(positionSeconds = it.durationSeconds, status = EpisodeStatus.PLAYED) }
        dequeue(episodeId)
    }

    override suspend fun markUnplayed(episodeId: String) =
        update(episodeId) { it.copy(positionSeconds = 0, status = EpisodeStatus.NEW) }

    // --- LibraryRepository ---

    override suspend fun subscriptionItems(): List<SubscriptionItem> = mutex.withLock {
        subscriptions.filter { it.ownerId == ME }.mapNotNull { it.asItem(Access.OWNER) }.sortedBy { it.podcast.title.lowercase() }
    }

    override suspend fun subscription(id: String): SubscriptionItem? {
        val (followed, access) = mutex.withLock { visibleSubscription(id) } ?: return null
        val owner = ownerName(followed)
        return mutex.withLock { followed.asItem(access, owner) }
    }

    override suspend fun subscribe(feedUrl: String): SubscriptionItem = mutex.withLock {
        val podcast = podcasts.find { it.feedUrl == feedUrl }
            ?: Podcast(
                id = "p${podcasts.size + 1}",
                title = directory.find { it.feedUrl == feedUrl }?.title ?: feedUrl.substringAfter("://").substringBefore('/'),
                author = directory.find { it.feedUrl == feedUrl }?.author.orEmpty(),
                feedUrl = feedUrl,
            ).also(podcasts::add)
        val followed = subscriptions.find { it.podcastId == podcast.id && it.ownerId == ME }
            ?: Followed(Subscription("s${nextSubscription++}", subscribedAt = subscribedAt), podcast.id, ME).also(subscriptions::add)
        SubscriptionItem(followed.subscription, podcast)
    }

    override suspend fun updateSubscription(subscriptionId: String, note: String?, autoDownload: Boolean): SubscriptionItem {
        val (followed, access) = mutex.withLock { visibleSubscription(subscriptionId) } ?: throw ApiException.NotFound("subscription $subscriptionId")
        if (access == Access.READ) throw forbidden("This subscription is shared with you read-only")
        val owner = ownerName(followed)
        return mutex.withLock {
            val updated = followed.copy(subscription = followed.subscription.copy(note = note, autoDownload = autoDownload))
            subscriptions[subscriptions.indexOf(followed)] = updated
            updated.asItem(access, owner)!!
        }
    }

    override suspend fun unsubscribe(subscriptionId: String) {
        mutex.withLock {
            val (followed, access) = visibleSubscription(subscriptionId) ?: throw ApiException.NotFound("subscription $subscriptionId")
            if (access != Access.OWNER) throw forbidden("Only its owner can end a subscription")
            subscriptions.remove(followed)
            // Shares go with what they share.
            shares.removeAll { it.type == ShareType.SUBSCRIPTION && it.resourceId == subscriptionId }
        }
    }

    override suspend fun refreshPodcast(podcastId: String): Podcast? = podcast(podcastId)

    override suspend fun podcastEpisodes(podcastId: String, limit: Int): List<Episode> =
        mutex.withLock { episodes.filter { it.podcastId == podcastId }.take(limit) }

    override suspend fun search(query: String): List<SearchResult> {
        val followed = subscriptions().map { it.feedUrl }.toSet()
        return directory
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true) }
            .map { it.copy(subscribed = it.feedUrl in followed) }
    }

    override suspend fun keep(episodeId: String) = update(episodeId) { it.copy(kept = true) }

    override suspend fun unkeep(episodeId: String) = update(episodeId) { it.copy(kept = false) }

    /** The demo has no server: the file is "downloaded" at once. */
    override suspend fun download(episodeId: String) = update(episodeId) { it.copy(fileStatus = FileStatus.READY) }

    // --- PlaybackRepository ---

    override suspend fun episodeItem(id: String): EpisodeItem? = mutex.withLock { episodes.find { it.id == id }?.let(::itemOf) }

    override suspend fun queue(): List<EpisodeItem> = mutex.withLock {
        queueIds.mapNotNull { id -> episodes.find { it.id == id } }.map(::itemOf)
    }

    override suspend fun enqueue(episodeId: String, position: Int?) = changeQueue {
        if (episodes.any { it.id == episodeId }) {
            remove(episodeId)
            add(position?.coerceIn(0, size) ?: size, episodeId)
        }
    }

    override suspend fun dequeue(episodeId: String) = changeQueue { remove(episodeId) }

    override suspend fun moveInQueue(episodeId: String, position: Int) = changeQueue {
        if (remove(episodeId)) add(position.coerceIn(0, size), episodeId)
    }

    override suspend fun clearQueue() = changeQueue { clear() }

    private suspend fun changeQueue(change: MutableList<String>.() -> Unit) = mutex.withLock {
        queueIds.change()
        for (index in episodes.indices) episodes[index] = episodes[index].let { it.copy(inQueue = it.id in queueIds) }
    }

    override suspend fun exportOpml(): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<opml version="2.0"><head><title>Podcast (demo)</title></head><body>""")
        subscriptions().forEach { appendLine("""  <outline type="rss" text="${it.title.escapeXml()}" xmlUrl="${it.feedUrl.escapeXml()}"/>""") }
        appendLine("</body></opml>")
    }

    override suspend fun importOpml(opml: String): OpmlImportReport {
        val urls = Regex("""xmlUrl\s*=\s*"([^"]+)"""").findAll(opml).map { it.groupValues[1] }.toSet()
        val followed = subscriptions().map { it.feedUrl }.toSet()
        urls.filterNot { it in followed }.forEach { subscribe(it) }
        return OpmlImportReport(imported = urls.count { it !in followed }, alreadySubscribed = urls.count { it in followed })
    }

    // --- AccountRepository: the demo accounts, except that a deleted account takes its shares ---

    override suspend fun deleteUser(userId: String) {
        accounts.deleteUser(userId)
        mutex.withLock { shares.removeAll { it.ownerId == userId || it.granteeId == userId } }
    }

    // --- SharingRepository ---

    override suspend fun sharedWithMe(): List<SharedItem> {
        val received = mutex.withLock { shares.filter { it.granteeId == ME }.sortedByDescending { it.createdAt } }
        return received.mapNotNull { share ->
            when (share.type) {
                ShareType.SUBSCRIPTION -> subscription(share.resourceId)?.let { SharedItem(share.toShare(), subscription = it) }
                ShareType.EPISODE -> episodeItem(share.resourceId)?.let { SharedItem(share.toShare(), episode = it) }
                ShareType.PLAYLIST -> null
            }
        }
    }

    override suspend fun sharesOf(subscriptionId: String): List<Share> {
        val mine = ownSubscription(subscriptionId)
        return mutex.withLock { shares.filter { it.type == ShareType.SUBSCRIPTION && it.resourceId == mine.subscription.id } }.map { it.toShare() }
    }

    override suspend fun shareSubscription(subscriptionId: String, userId: String, permission: SharePermission, message: String?): Share {
        ownSubscription(subscriptionId)
        return save(ShareType.SUBSCRIPTION, subscriptionId, userId, permission, message)
    }

    override suspend fun recommend(episodeId: String, userId: String, message: String?): Share {
        if (episode(episodeId) == null) throw ApiException.NotFound("episode $episodeId")
        return save(ShareType.EPISODE, episodeId, userId, SharePermission.READ, message)
    }

    override suspend fun changePermission(shareId: String, permission: SharePermission): Share {
        val share = visibleShare(shareId)
        if (share.ownerId != ME) throw forbidden("Only the owner changes what a share allows")
        if (share.type == ShareType.EPISODE) throw ApiException.Rejected(422, null, "Invalid share", listOf(FieldError("permission", "a recommendation is READ")))
        val changed = share.copy(permission = permission)
        mutex.withLock { shares[shares.indexOf(share)] = changed }
        return changed.toShare()
    }

    override suspend fun unshare(shareId: String) {
        val share = visibleShare(shareId)
        mutex.withLock { shares.remove(share) }
    }

    // --- Helpers ---

    /** The subscription if the demo user may read it, with what they may do with it. */
    private fun visibleSubscription(id: String): Pair<Followed, Access>? {
        val followed = subscriptions.find { it.subscription.id == id } ?: return null
        if (followed.ownerId == ME) return followed to Access.OWNER
        val share = shares.find { it.type == ShareType.SUBSCRIPTION && it.resourceId == id && it.granteeId == ME } ?: return null
        return followed to if (share.permission == SharePermission.WRITE) Access.WRITE else Access.READ
    }

    private suspend fun ownSubscription(id: String): Followed {
        val (followed, access) = mutex.withLock { visibleSubscription(id) } ?: throw ApiException.NotFound("subscription $id")
        if (access != Access.OWNER) throw forbidden("Only its owner can share a subscription")
        return followed
    }

    private suspend fun visibleShare(id: String): DemoShare =
        mutex.withLock { shares.find { it.id == id && (it.ownerId == ME || it.granteeId == ME) } } ?: throw ApiException.NotFound("share $id")

    /** Creates the share, or replaces the permission and the message of the existing one. */
    private suspend fun save(type: ShareType, resourceId: String, userId: String, permission: SharePermission, message: String?): Share {
        val errors = buildList {
            if (userId == ME || accounts.user(userId) == null) add(FieldError("userId", "an existing account expected"))
            if ((message?.length ?: 0) > ShareRules.MAX_MESSAGE_LENGTH) add(FieldError("message", "too long"))
        }
        if (errors.isNotEmpty()) throw ApiException.Rejected(422, null, "Invalid share", errors)
        val text = message?.trim()?.takeIf { it.isNotEmpty() }
        val saved = mutex.withLock {
            val existing = shares.find { it.type == type && it.resourceId == resourceId && it.ownerId == ME && it.granteeId == userId }
            if (existing != null) {
                existing.copy(permission = permission, message = text).also { shares[shares.indexOf(existing)] = it }
            } else {
                DemoShare("sh${nextShare++}", type, resourceId, ME, userId, permission, text, NOW).also(shares::add)
            }
        }
        return saved.toShare()
    }

    private suspend fun DemoShare.toShare(): Share =
        Share(id, type, permission, message, createdAt, owner = accounts.user(ownerId)!!, grantee = accounts.user(granteeId)!!)

    private suspend fun ownerName(followed: Followed): String? =
        if (followed.ownerId == ME) null else accounts.user(followed.ownerId)?.displayName

    private fun Followed.asItem(access: Access, ownerName: String? = null): SubscriptionItem? =
        podcasts.find { it.id == podcastId }?.let { SubscriptionItem(subscription.copy(access = access, ownerName = ownerName), it) }

    private fun itemOf(episode: Episode) = EpisodeItem(episode, podcasts.first { it.id == episode.podcastId })

    private suspend fun update(episodeId: String, change: (Episode) -> Episode) = mutex.withLock {
        val index = episodes.indexOfFirst { it.id == episodeId }
        if (index >= 0) episodes[index] = change(episodes[index])
    }

    private fun forbidden(detail: String) = ApiException.Rejected(403, null, detail)

    private fun String.escapeXml() = replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    companion object {
        /** Audio "address" of the demo episodes: only the simulated player can open it. */
        const val DEMO_AUDIO = "demo:"

        /** "Now" for the demo's new shares: the data stays the same from one run to the next. */
        private val NOW = Instant.parse("2026-10-12T09:00:00Z")
    }
}
