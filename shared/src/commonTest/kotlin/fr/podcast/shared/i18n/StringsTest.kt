package fr.podcast.shared.i18n

import fr.podcast.shared.model.Episode
import fr.podcast.shared.model.EpisodeStatus
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class StringsTest {

    @Test
    fun languageIsMatchedOnItsPrimarySubtag() {
        assertEquals(Language.French, Language.fromTag("fr"))
        assertEquals(Language.French, Language.fromTag("fr-CA"))
        assertEquals(Language.French, Language.fromTag("fr_FR.UTF-8"))
        assertEquals(Language.English, Language.fromTag("EN-us"))
        assertEquals(null, Language.fromTag("de"))
    }

    @Test
    fun negotiationKeepsPreferenceOrderAndFallsBackToDefault() {
        assertEquals(Language.French, Language.negotiate(listOf("de", "fr", "en")))
        assertEquals(Language.Default, Language.negotiate(listOf("de", "it")))
        assertEquals(Language.Default, Language.negotiate(emptyList()))
    }

    @Test
    fun durationsAndDates() {
        assertEquals("1 h 05", FrenchStrings.duration(65 * 60))
        assertEquals("42 min", FrenchStrings.duration(42 * 60))
        assertEquals("1 hr 5 min", EnglishStrings.duration(65 * 60))

        val date = LocalDate(2026, 9, 28)
        assertEquals("28 sept.", FrenchStrings.shortDate(date))
        assertEquals("Sep 28", EnglishStrings.shortDate(date))
    }

    @Test
    fun statusDependsOnTheEpisode() {
        val episode = Episode("e", "p", "t", LocalDate(2026, 1, 1), durationSeconds = 600, positionSeconds = 540,
            status = EpisodeStatus.IN_PROGRESS)

        assertEquals("1 min restante", FrenchStrings.status(episode))
        assertEquals("1 min left", EnglishStrings.status(episode))
        assertEquals("Écouté", FrenchStrings.status(episode.copy(status = EpisodeStatus.PLAYED)))
    }
}
