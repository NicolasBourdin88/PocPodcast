package fr.podcast.ui.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberOpmlFiles(): OpmlFiles? {
    val resolver = LocalContext.current.contentResolver
    val files = remember(resolver) { AndroidOpmlFiles(resolver) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), files::answer)
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml"), files::answer)
    SideEffect {
        files.openLauncher = open
        files.createLauncher = create
    }
    return files
}

/** Storage Access Framework: the system picker, then the content resolver. */
private class AndroidOpmlFiles(private val resolver: ContentResolver) : OpmlFiles {
    var openLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>? = null
    var createLauncher: ManagedActivityResultLauncher<String, Uri?>? = null
    private var pending: CompletableDeferred<Uri?>? = null

    fun answer(uri: Uri?) {
        pending?.complete(uri)
    }

    override suspend fun open(): String? {
        val uri = pick { openLauncher?.launch(arrayOf("text/x-opml", "text/xml", "application/xml", "application/octet-stream")) } ?: return null
        return withContext(Dispatchers.IO) { resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
    }

    override suspend fun save(suggestedName: String, content: String): String? {
        val uri = pick { createLauncher?.launch(suggestedName) } ?: return null
        withContext(Dispatchers.IO) { resolver.openOutputStream(uri)?.use { it.write(content.encodeToByteArray()) } }
        return displayName(uri) ?: suggestedName
    }

    private suspend fun pick(launch: () -> Unit): Uri? {
        val answer = CompletableDeferred<Uri?>().also { pending = it }
        launch()
        return answer.await()
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
