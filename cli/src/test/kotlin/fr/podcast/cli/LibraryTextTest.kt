package fr.podcast.cli

import fr.podcast.shared.i18n.Language
import fr.podcast.shared.repository.FakePodcastRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class LibraryTextTest {

    private val strings = Language.French.strings

    @Test
    fun searchResultsAreNumberedAndShowTheFeed() = runBlocking<Unit> {
        val text = renderSearchText(FakePodcastRepository().search("kotlin"), strings)

        assertEquals(
            listOf(
                " 1. Talking Kotlin — JetBrains  ✓ Abonné",
                "    https://talkingkotlin.com/feed.xml (demo)",
                " 2. Kotlin Internals — crashcasts.com",
                "    https://feeds.example.org/kotlin-internals.xml (demo)",
            ).joinToString("\n"),
            text,
        )
    }

    @Test
    fun theQueueIsNumbered() = runBlocking<Unit> {
        val repository = FakePodcastRepository()
        assertEquals(strings.emptyQueue, renderQueueText(repository.queue(), strings))

        repository.enqueue("e3")
        repository.enqueue("e1", position = 0)
        assertEquals(
            listOf(
                " 1. Compose Multiplatform for iOS is stable — Talking Kotlin · 52 min",
                " 2. KMP in production — Fragmented · 47 min",
            ).joinToString("\n"),
            renderQueueText(repository.queue(), strings),
        )
    }

    @Test
    fun subscriptionsShowNotesAndAutomaticDownload() = runBlocking<Unit> {
        val repository = FakePodcastRepository()
        val first = repository.subscriptionItems().first()
        repository.updateSubscription(first.subscription.id, "Pour le train", autoDownload = true)

        val text = renderSubscriptionsText(repository.subscriptionItems(), strings)
        assertContains(text, " 1. Fragmented — Donn Felker & Kaushik Gopal\n    # english · tech\n    ✎ Pour le train\n    ↓ Télécharger automatiquement")
        assertContains(text, " 4. Talking Kotlin — JetBrains")
        assertEquals(strings.emptyLibrary, renderSubscriptionsText(emptyList(), strings))
    }
}
