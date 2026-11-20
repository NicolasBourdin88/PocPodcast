package fr.podcast.shared.design

import fr.podcast.shared.model.Podcast
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesignTokensTest {

    @Test
    fun initialsSkipWordsWithoutLetters() {
        fun initials(title: String) = Podcast(id = "1", title = title, author = "", feedUrl = "").initials
        assertEquals("FA", initials("Fragmented - AI Developer Podcast"))
        assertEquals("LC", initials("Les Cast Codeurs Podcast"))
        assertEquals("9I", initials("99% Invisible"))
        assertEquals("LP", initials("(Les) Pieds sur terre"))
        assertEquals("T", initials("Tech"))
        assertEquals("", initials("— · —"))
    }

    /** WCAG 2 AA for normal text: every text color on every background, in both themes. */
    @Test
    fun textColorsAreReadableInBothThemes() {
        for ((theme, tokens) in listOf("light" to DesignTokens.light, "dark" to DesignTokens.dark)) {
            val backgrounds = mapOf(
                "background" to tokens.background,
                "surfaceContainer" to tokens.surfaceContainer,
                "secondaryContainer" to tokens.secondaryContainer,
            )
            val texts = mapOf(
                "onSurface" to tokens.onSurface,
                "onSurfaceVariant" to tokens.onSurfaceVariant,
                "primary" to tokens.primary,
                "error" to tokens.error,
            )
            for ((textName, text) in texts) for ((backgroundName, background) in backgrounds) {
                val ratio = contrast(text, background)
                assertTrue(ratio >= 4.5, "$theme: $textName on $backgroundName has a contrast of $ratio")
            }
        }
    }

    private fun contrast(a: Int, b: Int): Double {
        val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun luminance(rgb: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((rgb shr shift) and 0xFF) / 255.0
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
