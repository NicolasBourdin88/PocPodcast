package fr.podcast.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import fr.podcast.shared.model.Podcast
import fr.podcast.shared.repository.PodcastRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job

/**
 * Podcast artwork from the account's server (`/podcasts/{id}/artwork`, ADR 0013): small square
 * JPEGs, downloaded and decoded once, kept for the session. Called from the UI thread only.
 */
class ArtworkImages(private val client: PodcastRepository, scope: CoroutineScope) {
    // A failed download fails alone: the others go on.
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job) + Dispatchers.Default)
    private val loads = LinkedHashMap<String, Deferred<ImageBitmap?>>()
    private val images = mutableMapOf<String, ImageBitmap>()

    /** What is already decoded: shown at once when a screen comes back. */
    fun cached(podcast: Podcast): ImageBitmap? = images[podcast.id]

    /** The podcast's artwork; null without an image, or while the server cannot be reached. */
    suspend fun image(podcast: Podcast): ImageBitmap? {
        if (podcast.artworkUrl == null) return null
        val load = loads.getOrPut(podcast.id) {
            if (loads.size >= MAX_IMAGES) loads.keys.first().let { oldest -> loads.remove(oldest); images.remove(oldest) }
            scope.async { client.artwork(podcast.id)?.toImageBitmap() }
        }
        return try {
            load.await()?.also { images[podcast.id] = it }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Asked for again the next time it is shown.
            if (loads[podcast.id] === load) loads.remove(podcast.id)
            null
        }
    }

    private companion object {
        /** About 250 KB each once decoded. */
        const val MAX_IMAGES = 200
    }
}

/** The active account's artwork; none in previews and snapshots, which show the initials. */
val LocalArtworkImages = staticCompositionLocalOf<ArtworkImages?> { null }

/** A JPEG or PNG decoded for Compose; null when it is not one. */
internal expect fun ByteArray.toImageBitmap(): ImageBitmap?
