package fr.podcast.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import fr.podcast.ui.theme.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberOpmlFiles(): OpmlFiles? {
    val strings = LocalStrings.current
    return remember(strings) { DesktopOpmlFiles(strings.opmlImport, strings.opmlExport) }
}

/** AWT dialogs: native on macOS and Windows. */
private class DesktopOpmlFiles(private val openTitle: String, private val saveTitle: String) : OpmlFiles {

    override suspend fun open(): String? {
        val file = choose(openTitle, FileDialog.LOAD, suggestedName = null) ?: return null
        return withContext(Dispatchers.IO) { file.readText() }
    }

    override suspend fun save(suggestedName: String, content: String): String? {
        val file = choose(saveTitle, FileDialog.SAVE, suggestedName) ?: return null
        withContext(Dispatchers.IO) { file.writeText(content) }
        return file.name
    }

    private suspend fun choose(title: String, mode: Int, suggestedName: String?): File? = withContext(Dispatchers.Swing) {
        val dialog = FileDialog(null as Frame?, title, mode)
        suggestedName?.let { dialog.file = it }
        dialog.isVisible = true // modal: returns once the user has chosen
        dialog.file?.let { File(dialog.directory, it) }
    }
}
