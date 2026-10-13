package fr.podcast.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.intl.Locale
import fr.podcast.shared.design.ColorTokens
import fr.podcast.shared.design.DesignTokens
import fr.podcast.shared.i18n.Language
import fr.podcast.shared.i18n.Strings

/** Strings of the current language, provided by [PodcastTheme]. */
val LocalStrings = staticCompositionLocalOf<Strings> { Language.Default.strings }

/** Language matching the platform locale (Android, iOS or desktop). */
fun platformLanguage(): Language = Language.fromTag(Locale.current.toLanguageTag()) ?: Language.Default

/** 0xRRGGBB from the shared tokens to an opaque Compose color. */
fun rgb(value: Int) = Color(0xFF000000 or value.toLong())

private fun ColorTokens.toColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = rgb(primary),
        background = rgb(background),
        surface = rgb(background),
        surfaceContainer = rgb(surfaceContainer),
        secondaryContainer = rgb(secondaryContainer),
        onSurface = rgb(onSurface),
        onBackground = rgb(onSurface),
        onSurfaceVariant = rgb(onSurfaceVariant),
    )
}

@Composable
fun PodcastTheme(language: Language = platformLanguage(), content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val tokens = if (dark) DesignTokens.dark else DesignTokens.light
    CompositionLocalProvider(LocalStrings provides language.strings) {
        MaterialTheme(colorScheme = tokens.toColorScheme(dark), content = content)
    }
}
