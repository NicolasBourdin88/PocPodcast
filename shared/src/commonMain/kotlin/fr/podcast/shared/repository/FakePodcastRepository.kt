package fr.podcast.shared.repository

import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.EpisodeStatus
import fr.podcast.shared.model.FileStatus
import fr.podcast.shared.model.OpmlImportReport
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.model.SearchResult
import fr.podcast.shared.model.Subscription
import fr.podcast.shared.model.SubscriptionItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/** In-memory data, used by the demo mode and the tests. Nothing leaves the device. */
class FakePodcastRepository : PodcastClient {

    private val mutex = Mutex()
    private val subscribedAt = Instant.parse("2026-09-01T08:00:00Z")

    private val podcasts = mutableListOf(
        Podcast("p1", "Les Cast Codeurs", "Emmanuel Bernard & co", "https://lescastcodeurs.libsyn.com/rss"),
        Podcast("p2", "Talking Kotlin", "JetBrains", "https://talkingkotlin.com/feed.xml"),
        Podcast("p3", "Le Code a changé", "France Inter", "https://radiofrance-podcast.net/podcast09/rss_20786.xml"),
        Podcast("p4", "Fragmented", "Donn Felker & Kaushik Gopal", "https://feeds.simplecast.com/LpAGSLnY"),
    )

    private val subscriptions = podcasts.mapIndexed { index, podcast ->
        Subscription("s${index + 1}", note = null, subscribedAt = subscribedAt) to podcast.id
    }.toMutableList()

    // Ordered from most recent to oldest.
    private val episodes = mutableListOf(
        Episode("e1", "p2", "Compose Multiplatform for iOS is stable", Instant.parse("2026-10-12T06:00:00Z"), durationSeconds = 52 * 60),
        Episode(
            "e2", "p1", "LCC 330 - Les nouveautés de Kotlin 2.4", Instant.parse("2026-10-10T05:00:00Z"),
            durationSeconds = 95 * 60, positionSeconds = 38 * 60, status = EpisodeStatus.IN_PROGRESS,
        ),
        Episode("e3", "p4", "KMP in production", Instant.parse("2026-10-08T12:00:00Z"), durationSeconds = 47 * 60),
        Episode(
            "e4", "p3", "Qui a inventé le copier-coller ?", Instant.parse("2026-10-05T04:30:00Z"),
            durationSeconds = 34 * 60, positionSeconds = 25 * 60, status = EpisodeStatus.IN_PROGRESS,
        ),
        Episode(
            "e5", "p2", "Ktor 3 and server-side Kotlin", Instant.parse("2026-09-28T06:00:00Z"),
            durationSeconds = 61 * 60, positionSeconds = 61 * 60, status = EpisodeStatus.PLAYED,
        ),
    )

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

    override suspend fun latestEpisodes(limit: Int): List<Episode> = mutex.withLock { episodes.take(limit) }

    override suspend fun inProgress(): List<Episode> =
        mutex.withLock { episodes.filter { it.status == EpisodeStatus.IN_PROGRESS } }

    override suspend fun updateProgress(episodeId: String, positionSeconds: Int) =
        update(episodeId) { it.copy(positionSeconds = positionSeconds, status = EpisodeStatus.IN_PROGRESS) }

    override suspend fun markPlayed(episodeId: String) =
        update(episodeId) { it.copy(positionSeconds = it.durationSeconds, status = EpisodeStatus.PLAYED) }

    override suspend fun markUnplayed(episodeId: String) =
        update(episodeId) { it.copy(positionSeconds = 0, status = EpisodeStatus.NEW) }

    // --- LibraryRepository ---

    override suspend fun subscriptionItems(): List<SubscriptionItem> = mutex.withLock {
        subscriptions.mapNotNull { (subscription, podcastId) ->
            podcasts.find { it.id == podcastId }?.let { SubscriptionItem(subscription, it) }
        }.sortedBy { it.podcast.title.lowercase() }
    }

    override suspend fun subscribe(feedUrl: String): SubscriptionItem = mutex.withLock {
        val podcast = podcasts.find { it.feedUrl == feedUrl }
            ?: Podcast(
                id = "p${podcasts.size + 1}",
                title = directory.find { it.feedUrl == feedUrl }?.title ?: feedUrl.substringAfter("://").substringBefore('/'),
                author = directory.find { it.feedUrl == feedUrl }?.author.orEmpty(),
                feedUrl = feedUrl,
            ).also(podcasts::add)
        val existing = subscriptions.find { it.second == podcast.id }
        val subscription = existing?.first
            ?: Subscription("s${subscriptions.size + 1}", subscribedAt = subscribedAt).also { subscriptions += it to podcast.id }
        SubscriptionItem(subscription, podcast)
    }

    override suspend fun updateSubscription(subscriptionId: String, note: String?, autoDownload: Boolean): SubscriptionItem = mutex.withLock {
        val index = subscriptions.indexOfFirst { it.first.id == subscriptionId }
        val (subscription, podcastId) = subscriptions[index]
        val updated = subscription.copy(note = note, autoDownload = autoDownload)
        subscriptions[index] = updated to podcastId
        SubscriptionItem(updated, podcasts.first { it.id == podcastId })
    }

    override suspend fun unsubscribe(subscriptionId: String) {
        mutex.withLock { subscriptions.removeAll { it.first.id == subscriptionId } }
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

    private suspend fun update(episodeId: String, change: (Episode) -> Episode) = mutex.withLock {
        val index = episodes.indexOfFirst { it.id == episodeId }
        if (index >= 0) episodes[index] = change(episodes[index])
    }

    private fun String.escapeXml() = replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
}
