package fr.podcast.ui.library

import androidx.compose.runtime.Composable

/** Opens and saves OPML files with the platform's own dialogs. */
interface OpmlFiles {
    /** Content of the file the user picked, or null if they cancelled. */
    suspend fun open(): String?

    /** Writes [content] where the user chooses; the chosen file name, or null if they cancelled. */
    suspend fun save(suggestedName: String, content: String): String?
}

/** Null where the app has no file dialogs yet (iOS): the web client and the CLI do OPML there. */
@Composable
expect fun rememberOpmlFiles(): OpmlFiles?
