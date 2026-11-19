package fr.podcast.cli

import fr.podcast.shared.i18n.Language
import fr.podcast.shared.repository.FakePodcastRepository
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountTextTest {

    private val strings = Language.French.strings

    @Test
    fun devicesAndUsersAreNumbered() = runBlocking<Unit> {
        val repository = FakePodcastRepository()

        // Before any choice, the avatar taken from the username (ADR 0013).
        assertEquals("🐳 Demo — demo · Administrateur · Baleine", renderProfileText(repository.me(), strings))
        assertEquals(
            listOf(
                " 1. MacBook Air — Cet appareil",
                " 2. Pixel 9 Pro — Utilisé le 11 oct.",
                " 3. Terminal — Jamais utilisé",
            ).joinToString("\n"),
            renderDevicesText(repository.devices(), strings, TimeZone.UTC),
        )
        assertEquals(
            listOf(
                " 1. 🦊 Alice — alice · Utilisateur",
                " 2. 🐼 Bob — bob · Utilisateur",
                " 3. 🐳 Demo (vous) — demo · Administrateur",
            ).joinToString("\n"),
            renderUsersText(repository.users(), repository.me(), strings),
        )
        assertEquals("🦉 Demo — demo · Administrateur · Hibou", renderProfileText(repository.updateProfile(avatar = "owl"), strings))
    }

    @Test
    fun sharesShowWhoAndWhat() = runBlocking<Unit> {
        val repository = FakePodcastRepository()

        assertEquals(
            listOf(
                " 1. La Méthode scientifique — Partagé par Alice · Lecture seule",
                "    ✉ Pour tes trajets",
                " 2. KMP in production — Fragmented · Recommandé par Bob",
                "    ✉ À écouter à partir de 12 min",
                " 3. Android — Partagée par Bob · Lecture seule · Statique",
                "    ✉ Pour découvrir",
            ).joinToString("\n"),
            renderSharedText(repository.sharedWithMe(), strings),
        )
        assertEquals("  ⇄ Bob — Lecture et modification", renderSharesText(repository.sharesOf("s2"), strings))
        assertEquals(strings.notShared, renderSharesText(repository.sharesOf("s1"), strings))
        assertEquals(strings.playlistNotShared, renderSharesText(repository.sharesOfPlaylist("pl2"), strings, strings.playlistNotShared))
    }
}
