package fr.podcast.cli

import fr.podcast.shared.home.LoadHomeState
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
    fun homeTextShowsBothSections() {
        val text = renderHomeText(runBlocking { LoadHomeState(FakePodcastRepository())() })

        assertContains(text, "Reprendre l'écoute")
        assertContains(text, "Nouveaux épisodes")
        assertContains(text, "LCC 330 - Les nouveautés de Kotlin 2.4")
    }
}
