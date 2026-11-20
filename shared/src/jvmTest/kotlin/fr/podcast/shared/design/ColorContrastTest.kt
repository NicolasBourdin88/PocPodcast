package fr.podcast.shared.design

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Colors legible in every appearance (ADR 0013), measured as WCAG contrast ratios. */
class ColorContrastTest {

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(rgb: Int): Double =
        0.2126 * channel(rgb shr 16 and 0xFF) + 0.7152 * channel(rgb shr 8 and 0xFF) + 0.0722 * channel(rgb and 0xFF)

    private fun contrast(a: Int, b: Int): Double {
        val (high, low) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (high + 0.05) / (low + 0.05)
    }

    private fun assertContrast(minimum: Double, color: Int, background: Int, what: String) {
        val ratio = contrast(color, background)
        assertTrue(ratio >= minimum, "$what: ${"%.2f".format(ratio)}:1, at least $minimum:1 expected")
    }

    @Test
    fun theMaterialPrimaryIsLegibleOnItsBackground() {
        // Text and icons in the primary color (AA for text: 4.5:1).
        assertContrast(4.5, DesignTokens.light.primary, DesignTokens.light.background, "light primary")
        assertContrast(4.5, DesignTokens.dark.primary, DesignTokens.dark.background, "dark primary")
    }

    @Test
    fun theAppleAccentIsLegibleAsTextAndAsAFill() {
        val accent = DesignTokens.appleAccent
        val white = 0xFFFFFF
        // The system's backgrounds: white and grouped gray in light, black and its gray in dark.
        for (background in listOf(0xFFFFFF, 0xF2F2F7)) {
            assertContrast(4.5, accent.light, background, "light accent as text")
            assertContrast(7.0, accent.lightHighContrast, background, "light high-contrast accent as text")
        }
        for (background in listOf(0x000000, 0x1C1C1E)) {
            assertContrast(4.5, accent.dark, background, "dark accent as text")
            assertContrast(7.0, accent.darkHighContrast, background, "dark high-contrast accent as text")
        }
        // A white symbol on a filled button: 3:1, as for icons and large text.
        assertContrast(3.0, white, accent.light, "white on the light accent")
        assertContrast(3.0, white, accent.dark, "white on the dark accent")
        assertContrast(3.0, white, accent.lightHighContrast, "white on the light high-contrast accent")
    }

    @Test
    fun theAppleAssetCatalogHoldsTheSharedAccent() {
        val file = File("../appleApp/Podcast/Assets.xcassets/AccentColor.colorset/Contents.json")
        val colors = Json.parseToJsonElement(file.readText()).jsonObject["colors"]!!.jsonArray.associate { entry ->
            val appearances = entry.jsonObject["appearances"]?.jsonArray.orEmpty()
                .map { it.jsonObject["value"]!!.jsonPrimitive.content }.toSet()
            val components = entry.jsonObject["color"]!!.jsonObject["components"]!!.jsonObject
            fun part(name: String) = components[name]!!.jsonPrimitive.content.removePrefix("0x").toInt(16)
            appearances to (part("red") shl 16 or (part("green") shl 8) or part("blue"))
        }
        val accent = DesignTokens.appleAccent
        assertEquals(
            mapOf(
                emptySet<String>() to accent.light,
                setOf("dark") to accent.dark,
                setOf("high") to accent.lightHighContrast,
                setOf("dark", "high") to accent.darkHighContrast,
            ),
            colors,
        )
    }
}
