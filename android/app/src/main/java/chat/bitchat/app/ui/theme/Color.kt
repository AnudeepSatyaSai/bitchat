// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * BitChat color system — dark mesh aesthetic.
 * Port of Swift's ColorPalette asset catalog.
 */
object BitchatColors {
    // Primary
    val Primary = Color(0xFF6366F1)         // Indigo-500
    val PrimaryVariant = Color(0xFF4F46E5)  // Indigo-600
    val PrimaryLight = Color(0xFF818CF8)    // Indigo-400

    // Accent
    val Accent = Color(0xFF22D3EE)          // Cyan-400
    val AccentDark = Color(0xFF06B6D4)      // Cyan-500

    // Background
    val Background = Color(0xFF0F172A)       // Slate-900
    val Surface = Color(0xFF1E293B)          // Slate-800
    val SurfaceLight = Color(0xFF334155)     // Slate-700
    val SurfaceElevated = Color(0xFF1A2547)  // Custom elevated

    // Text
    val TextPrimary = Color(0xFFF1F5F9)     // Slate-100
    val TextSecondary = Color(0xFF94A3B8)   // Slate-400
    val TextMuted = Color(0xFF64748B)       // Slate-500

    // Status
    val Online = Color(0xFF34D399)          // Emerald-400
    val Offline = Color(0xFF6B7280)         // Gray-500
    val Warning = Color(0xFFFBBF24)         // Amber-400
    val Error = Color(0xFFEF4444)           // Red-500
    val Success = Color(0xFF10B981)         // Emerald-500

    // Message bubbles
    val BubbleSent = Color(0xFF4338CA)      // Indigo-700
    val BubbleReceived = Color(0xFF1E293B)  // Slate-800
    val BubblePrivateSent = Color(0xFF7C3AED) // Violet-600
    val BubblePrivateReceived = Color(0xFF2D2255) // Custom violet dark

    // Transport indicators
    val TransportBLE = Color(0xFF3B82F6)    // Blue-500
    val TransportWiFiAware = Color(0xFF8B5CF6) // Violet-500
    val TransportMulti = Color(0xFFA78BFA)  // Violet-400

    // Glass/overlay
    val GlassOverlay = Color(0x1AFFFFFF)    // 10% white
    val GlassBorder = Color(0x33FFFFFF)     // 20% white
}
