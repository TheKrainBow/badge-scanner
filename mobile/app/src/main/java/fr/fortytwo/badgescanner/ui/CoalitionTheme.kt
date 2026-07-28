package fr.fortytwo.badgescanner.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Parses a coalition color like "#4180DB", or null when absent/invalid. */
fun parseCoalitionColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return runCatching { Color(android.graphics.Color.parseColor(hex.trim())) }.getOrNull()
}

/** A readable foreground color (white or near-black) for [background]. */
fun onColorFor(background: Color): Color =
    if (background.luminance() < 0.5f) Color.White else Color(0xFF10151F)
