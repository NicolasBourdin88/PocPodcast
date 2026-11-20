package fr.podcast.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

internal actual fun ByteArray.toImageBitmap(): ImageBitmap? = runCatching { Image.makeFromEncoded(this).toComposeImageBitmap() }.getOrNull()
