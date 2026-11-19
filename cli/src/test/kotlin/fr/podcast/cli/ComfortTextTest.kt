package fr.podcast.cli

import fr.podcast.shared.i18n.Language
import fr.podcast.shared.model.EpisodeQuery
import fr.podcast.shared.model.ListeningStats
import fr.podcast.shared.repository.FakePodcastRepository
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** Search, tags and statistics in the terminal (ADR 0012). */
class ComfortTextTest {

    private val strings = Language.French.strings

    @Test
    fun foundEpisodesAreNumberedWithTheirStatusAndMarks() = runBlocking<Unit> {
        val repository = FakePodcastRepository()

        assertEquals(
            listOf(
                "3 épisodes",
                " 1. Compose Multiplatform for iOS is stable — Talking Kotlin · 12 oct.",
                "    52 min · Nouveau · ★★★★☆",
                " 2. LCC 330 - Les nouveautés de Kotlin 2.4 — Les Cast Codeurs · 10 oct.",
                "    1 h 35 · 57 min restantes · Favori",
                " 3. Ktor 3 and server-side Kotlin — Talking Kotlin · 28 sept.",
                "    1 h 01 · Écouté",
            ).joinToString("\n"),
            renderEpisodeSearchText(repository.searchEpisodes(EpisodeQuery("kotlin")), strings, TimeZone.UTC),
        )
        // More match than shown.
        val firstOnly = renderEpisodeSearchText(repository.searchEpisodes(EpisodeQuery("kotlin"), limit = 1), strings, TimeZone.UTC)
        assertEquals("3 épisodes", firstOnly.lines().first())
        assertEquals("  …", firstOnly.lines().last())
        assertEquals(strings.noEpisodeFound, renderEpisodeSearchText(repository.searchEpisodes(EpisodeQuery("cobol")), strings))
    }

    @Test
    fun subscriptionsShowTheirTagsAndKeepTheirNumbersWhenFiltered() = runBlocking<Unit> {
        val repository = FakePodcastRepository()
        val items = repository.subscriptionItems()

        assertEquals(" 2. Le Code a changé — France Inter\n    # culture", renderSubscriptionsText(items, strings) { it.matches("", "CULTURE") })
        assertEquals(strings.noSubscriptionFound, renderSubscriptionsText(items, strings) { it.matches("cobol", null) })

        assertEquals("Le Code a changé — Étiquettes · culture", renderTagsText(items[1], strings))
        val cleared = repository.updateSubscription(items[1].subscription.id, null, autoDownload = false, tags = emptyList())
        assertEquals("Le Code a changé — Étiquettes · ∅", renderTagsText(cleared, strings))
    }

    @Test
    fun statisticsShowTheTotalsTheMonthsAndTheMostListened() = runBlocking<Unit> {
        val text = renderStatsText(FakePodcastRepository().listeningStats(), strings)

        assertEquals(
            listOf(
                "Mon écoute",
                "  Temps d'écoute     2 h 04",
                "  7 derniers jours   38 min",
                "  30 derniers jours  2 h 04",
                "  1 épisode terminé · Depuis le 28 sept. 2026",
            ).joinToString("\n"),
            text.lines().take(5).joinToString("\n"),
        )
        // Twelve months, the longest as a full bar, those without listening without a bar.
        assertContains(text, "\n  nov.\n")
        assertContains(text, "\n  oct.   ${"█".repeat(BAR_WIDTH)}  1 h 03\n")
        assertContains(text, "Les plus écoutés\n  Talking Kotlin    1 h 01\n  Les Cast Codeurs  38 min\n  Le Code a changé  25 min")

        assertEquals(strings.noListeningYet, renderStatsText(ListeningStats(0, 0, 0, 0), strings))
    }

    @Test
    fun barsAreMeasuredToTheEighth() {
        assertEquals("", bar(0, 100))
        assertEquals("█".repeat(BAR_WIDTH), bar(100, 100))
        assertEquals("█".repeat(BAR_WIDTH / 2), bar(50, 100))
        assertEquals("███▌", bar(35, 240, width = 24))
        // Something heard, however little, shows.
        assertEquals("▏", bar(1, 100_000))
    }
}
