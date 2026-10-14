package fr.podcast.cli

import fr.podcast.shared.home.LoadHomeState
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.repository.FakePodcastRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class HomeTextTest {

    @Test
    fun progressBarIsFilledProportionally() {
        assertEquals("████░░░░░░", progressBar(0.4f, width = 10))
        assertEquals("░░░░░", progressBar(0f, width = 5))
        assertEquals("█████", progressBar(1f, width = 5))
    }

    @Test
    fun homeTextShowsBothSectionsInTheRequestedLanguage() {
        val home = runBlocking { LoadHomeState(FakePodcastRepository())() }

        val french = renderHomeText(home, Language.French.strings)
        assertContains(french, "Reprendre l'écoute")
        assertContains(french, "Nouveaux épisodes")
        assertContains(french, "LCC 330 - Les nouveautés de Kotlin 2.4")

        // Numbered in the order shown, as used by `play` and `queue add`.
        assertContains(french, " 1. LCC 330 - Les nouveautés de Kotlin 2.4 — Les Cast Codeurs")
        assertContains(french, " 3. 12 oct.  Compose Multiplatform for iOS is stable")
        assertEquals("e1", home.numbered[2].episode.id)

        val english = renderHomeText(home, Language.English.strings)
        assertContains(english, "Continue listening")
        assertContains(english, "57 min left")
    }
}
