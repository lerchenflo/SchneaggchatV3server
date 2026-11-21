package com.lerchenflo.schneaggchatv3server.util

object ColorGenerator {

    // Predefined visually distinct colors (hex values)
    private val distinctColors = listOf(
        0xFF2196F3.toInt(), // Blue
        0xFFF44336.toInt(), // Red
        0xFF4CAF50.toInt(), // Green
        0xFFFF9800.toInt(), // Orange
        0xFF9C27B0.toInt(), // Purple
        0xFF00BCD4.toInt(), // Cyan
        0xFF795548.toInt(), // Brown
        0xFF607D8B.toInt(), // Blue Grey
        0xFFE91E63.toInt(), // Pink
        0xFF3F51B5.toInt(), // Indigo
        0xFF009688.toInt(), // Teal
        0xFFFFEB3B.toInt(), // Yellow
        0xFF9E9E9E.toInt(), // Grey
        0xFFCDDC39.toInt(), // Lime
        0xFF8BC34A.toInt(), // Light Green
    )

    fun generateUniqueColorsForGroup(existingColors: Set<Int>, count: Int): List<Int> {
        val availableColors = distinctColors.filter { it !in existingColors }

        return if (availableColors.size >= count) {
            availableColors.shuffled().take(count)
        } else {
            // Mix available colors with generated ones
            val generated = (count - availableColors.size).let { needed ->
                (1..needed).map { generateRandomColor(existingColors) }
            }
            availableColors + generated
        }
    }

    private fun generateRandomColor(excludedColors: Set<Int>): Int {
        var color: Int
        do {
            // Generate vibrant colors by avoiding too dark/light values
            val hue = (0..360).random()
            val saturation = (60..100).random()
            val lightness = (40..70).random()
            color = hslToRgb(hue, saturation, lightness)
        } while (color in excludedColors)

        return color
    }

    private fun hslToRgb(h: Int, s: Int, l: Int): Int {
        // HSL to RGB conversion algorithm
        val hNorm = h / 360.0
        val sNorm = s / 100.0
        val lNorm = l / 100.0

        val c = (1 - kotlin.math.abs(2 * lNorm - 1)) * sNorm
        val x = c * (1 - kotlin.math.abs((hNorm * 6) % 2 - 1))
        val m = lNorm - c / 2

        // Double bounds: with Int division (1/6) every bound was 0 and every hue came out pink
        val (r, g, b) = when {
            hNorm < 1.0 / 6 -> Triple(c, x, 0.0)
            hNorm < 2.0 / 6 -> Triple(x, c, 0.0)
            hNorm < 3.0 / 6 -> Triple(0.0, c, x)
            hNorm < 4.0 / 6 -> Triple(0.0, x, c)
            hNorm < 5.0 / 6 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }

        // ARGB like the palette: without the alpha byte the client draws the name fully transparent
        return (0xFF shl 24) or
                (kotlin.math.round((r + m) * 255).toInt() shl 16) or
                (kotlin.math.round((g + m) * 255).toInt() shl 8) or
                kotlin.math.round((b + m) * 255).toInt()
    }

}