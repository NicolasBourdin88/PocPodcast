package fr.podcast.shared.account

import fr.podcast.shared.api.ApiException
import fr.podcast.shared.model.Avatar
import fr.podcast.shared.repository.FakePodcastRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The profile pictures (ADR 0013). */
class AvatarTest {

    @Test
    fun theCatalogIsStable() {
        assertEquals(Avatar.entries.size, Avatar.ids.toSet().size)
        assertEquals(Avatar.Owl, Avatar.of("owl"))
        assertEquals(null, Avatar.of("dragon"))
        // Before any choice, the same picture on every platform: from the username alone.
        assertEquals(Avatar.Fox, Avatar.default("alice"))
        assertEquals(Avatar.Panda, Avatar.default("bob"))
        assertEquals(Avatar.Rocket, Avatar.of("rocket", "alice"))
    }

    @Test
    fun theProfileChangesWhatIsGiven() = runTest {
        val repository = FakePodcastRepository()
        val before = repository.me()

        val owl = repository.updateProfile(avatar = "owl")
        assertEquals(Avatar.Owl, owl.picture)
        assertEquals(before.displayName, owl.displayName)
        assertEquals("owl", repository.updateProfile(displayName = "Démo").avatar)
        assertFailsWith<ApiException.Rejected> { repository.updateProfile(avatar = "dragon") }
    }
}
