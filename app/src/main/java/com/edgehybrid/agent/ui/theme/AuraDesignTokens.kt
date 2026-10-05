package com.edgehybrid.agent.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Single source of truth for the visual language.
 *
 * Direction: ChatGPT's message-first layout with Apple's type scale and material
 * restraint. The rules that produce that look:
 *
 *  - **Content is the interface.** Chrome recedes; there is no heavy card stack.
 *  - **One accent, used sparingly.** It marks the primary action or the active
 *    selection, never decoration.
 *  - **Type does the hierarchy.** Size and weight, not colour and not boxes.
 *  - **Separators over borders.** Hairlines and surface shifts, not outlined boxes.
 *
 * This replaces the previous `AuraTokens` object. That palette was a second,
 * competing colour system: it was referenced from exactly one screen
 * (`LibraryMemoryScreen.kt`) while the theme that actually rendered was the
 * scheme in `Theme.kt`. Two palettes meant one screen looked like a different
 * app from the rest. Everything now resolves through [MaterialTheme.colorScheme].
 */
object AuraTokens {

    // ---- Accent ----------------------------------------------------------
    // A single restrained blue-violet. Used for the primary action, the active
    // nav item, focus rings, and selection. Never for large fills.
    val Accent = Color(0xFF5B5BD6)
    val AccentHover = Color(0xFF4A4AC4)
    val AccentPressed = Color(0xFF3D3DAE)
    val OnAccent = Color(0xFFFFFFFF)

    /** Low-emphasis accent wash for selected rows and chips. */
    val AccentSubtle = Color(0xFFEDEDFB)
    val AccentSubtleDark = Color(0xFF262655)

    // ---- Light surfaces --------------------------------------------------
    // Near-white with a faint cool cast: paper, not plastic.
    val CanvasLight = Color(0xFFFFFFFF)
    val SurfaceLight = Color(0xFFFAFAFC)
    val SurfaceRaisedLight = Color(0xFFF2F2F7)
    val SurfaceSunkenLight = Color(0xFFEFEFF4)

    // ---- Dark surfaces ---------------------------------------------------
    // Apple's dark greys are slightly blue, never pure black — pure black makes
    // elevation unreadable and causes halation on OLED.
    val CanvasDark = Color(0xFF0E0E11)
    val SurfaceDark = Color(0xFF16161A)
    val SurfaceRaisedDark = Color(0xFF1E1E24)
    val SurfaceSunkenDark = Color(0xFF121216)

    // ---- Text ------------------------------------------------------------
    val TextPrimaryLight = Color(0xFF15151A)
    val TextSecondaryLight = Color(0xFF63636E)
    val TextTertiaryLight = Color(0xFF8E8E99)
    val TextPrimaryDark = Color(0xFFF2F2F7)
    val TextSecondaryDark = Color(0xFFA0A0AB)
    val TextTertiaryDark = Color(0xFF6E6E79)

    // ---- Lines -----------------------------------------------------------
    val SeparatorLight = Color(0x1A000000)
    val SeparatorDark = Color(0x1FFFFFFF)
    val BorderLight = Color(0xFFE0E0E6)
    val BorderDark = Color(0xFF2A2A32)

    // ---- Semantic --------------------------------------------------------
    val Success = Color(0xFF1F9D55)
    val SuccessDark = Color(0xFF32D074)
    val Warning = Color(0xFFB7791F)
    val WarningDark = Color(0xFFE3A008)
    val Danger = Color(0xFFD0342C)
    val DangerDark = Color(0xFFFF453A)

    /** Soft danger wash for destructive chips and inline errors. */
    val ErrorContainer = Color(0xFFFCE8E6)
    val ErrorContainerDark = Color(0xFF3A1512)

    // ---- Spacing scale (4pt grid) ----------------------------------------
    // Named so layout code stops inventing one-off dp values.
    object Space {
        val xs = 4
        val sm = 8
        val md = 12
        val lg = 16
        val xl = 24
        val xxl = 32
    }

    // ---- Corner radii ----------------------------------------------------
    // Continuous-feeling radii: small for controls, larger for message bubbles.
    object Radius {
        val control = 12
        val bubble = 20
        val sheet = 28
        val pill = 999
    }
}