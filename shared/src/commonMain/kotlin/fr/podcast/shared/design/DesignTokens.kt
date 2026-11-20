package fr.podcast.shared.design

import fr.podcast.shared.model.Podcast
import kotlin.math.absoluteValue

/**
 * Colors shared by every client, as 0xRRGGBB values: Compose builds its
 * color scheme from them, the server its CSS variables, the TUI its accent.
 * Values are the Material 3 baseline until the design system exists.
 */
data class ColorTokens(
    /** Page background, also used as the Compose surface. */
    val background: Int,
    /** Navigation bars, rail, mini player. */
    val surfaceContainer: Int,
    /** Cards ("continue listening"), selected navigation item, buttons. */
    val secondaryContainer: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    /** Refusals, failures and destructive actions (unsubscribe). */
    val error: Int,
)

/**
 * An accent in Apple's way (ADR 0013): one color per appearance, legible as text and icons on the
 * system's backgrounds, with a white symbol legible on it when it fills a button. Increased
 * contrast takes its own pair, as Apple's system colors do.
 */
data class AccentTokens(val light: Int, val dark: Int, val lightHighContrast: Int, val darkHighContrast: Int)

object DesignTokens {
    val light = ColorTokens(
        background = 0xFDF7FF,
        surfaceContainer = 0xF3EDF7,
        secondaryContainer = 0xE8DEF8,
        onSurface = 0x1D1B20,
        onSurfaceVariant = 0x625B71,
        primary = 0x6750A4,
        error = 0xB3261E,
    )

    val dark = ColorTokens(
        background = 0x141218,
        surfaceContainer = 0x211F26,
        secondaryContainer = 0x4A4458,
        onSurface = 0xE6E0E9,
        onSurfaceVariant = 0xCAC4D0,
        primary = 0xD0BCFF,
        error = 0xF2B8B5,
    )

    /**
     * The accent of the SwiftUI client, the asset catalog's AccentColor. The light one is the brand;
     * the Material primary of [dark] is made for dark text on it, so the dark accent is its own.
     */
    val appleAccent = AccentTokens(light = 0x6750A4, dark = 0x9678E8, lightHighContrast = 0x4D3486, darkHighContrast = 0xB497FF)

    /** Placeholder artwork backgrounds, until real images are loaded. */
    val artworkColors = listOf(0x6750A4, 0x0B6E4F, 0xB3261E, 0x1F5FA8, 0x8A5A00)
}

/** Same color for a podcast on every client. */
val Podcast.artworkColor: Int
    get() = DesignTokens.artworkColors[id.hashCode().absoluteValue % DesignTokens.artworkColors.size]

/** Up to two initials shown on the placeholder artwork. */
val Podcast.initials: String
    get() = title.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

/** "#RRGGBB" for CSS. */
fun Int.toCssHex(): String = "#" + toString(16).padStart(6, '0')
