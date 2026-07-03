package net.guacamaya.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * GuacaMalla design system — Compose token layer.
 *
 * Direct port of `docs/design/DESIGN.md` (yellow + black brand voltage, dark-only,
 * emergency-semantic layer). Per that doc's "Platform Mapping" section, the M3
 * [darkColorScheme] carries the roles Material models, and a [GuacamayaColors]
 * extension (exposed via [LocalGuacamayaColors] / `MaterialTheme.guacamaya`) carries
 * the semantic + surface tones Material has no role for. System font (Roboto) —
 * weights only, no bundled face, per the low-end-device decision.
 */

// ── Raw palette (single source of truth; mirrors DESIGN.md `colors:` block) ──────────
object GuacamayaPalette {
    // Brand / action
    val Primary = Color(0xFFFAFF69)        // electric yellow — brand voltage, active state
    val PrimaryActive = Color(0xFFE6EB52)
    val PrimaryDisabled = Color(0xFF3A3A1F)
    val OnPrimary = Color(0xFF0A0A0A)

    // Surfaces (near-black, no shadows — depth by contrast)
    val Canvas = Color(0xFF0A0A0A)
    val SurfaceSoft = Color(0xFF121212)
    val SurfaceCard = Color(0xFF1A1A1A)
    val SurfaceElevated = Color(0xFF242424)
    val Hairline = Color(0xFF2A2A2A)
    val HairlineStrong = Color(0xFF3A3A3A)

    // Text
    val Ink = Color(0xFFFFFFFF)
    val Body = Color(0xFFCCCCCC)
    val BodyStrong = Color(0xFFE6E6E6)
    val Muted = Color(0xFF888888)
    val MutedSoft = Color(0xFF5A5A5A)

    // Emergency-semantic layer (meaning, not decoration — always paired with icon + word)
    val Danger = Color(0xFFFF453A)         // critical SOS / distress
    val DangerSoft = Color(0xFF3A1512)
    val Warning = Color(0xFFFF9F0A)        // unconfirmed / caution
    val WarningSoft = Color(0xFF3A2710)
    val Success = Color(0xFF30D158)        // safe / delivered / relayed
    val SuccessSoft = Color(0xFF0E2E18)
    val Info = Color(0xFF0A84FF)           // official / verified / info
    val InfoSoft = Color(0xFF0A1F3A)
    val OnSemantic = Color(0xFFFFFFFF)
}

// ── M3 color scheme — only the roles Material actually models ─────────────────────────
private val GuacamayaColorScheme = darkColorScheme(
    primary = GuacamayaPalette.Primary,
    onPrimary = GuacamayaPalette.OnPrimary,
    primaryContainer = GuacamayaPalette.PrimaryDisabled,
    onPrimaryContainer = GuacamayaPalette.Primary,
    secondary = GuacamayaPalette.Info,
    onSecondary = GuacamayaPalette.OnSemantic,
    tertiary = GuacamayaPalette.Success,
    onTertiary = GuacamayaPalette.OnSemantic,
    background = GuacamayaPalette.Canvas,
    onBackground = GuacamayaPalette.Ink,
    surface = GuacamayaPalette.SurfaceCard,
    onSurface = GuacamayaPalette.Ink,
    surfaceVariant = GuacamayaPalette.SurfaceElevated,
    onSurfaceVariant = GuacamayaPalette.Muted,
    error = GuacamayaPalette.Danger,
    onError = GuacamayaPalette.OnSemantic,
    errorContainer = GuacamayaPalette.DangerSoft,
    onErrorContainer = GuacamayaPalette.Danger,
    outline = GuacamayaPalette.HairlineStrong,
    outlineVariant = GuacamayaPalette.Hairline,
    scrim = Color.Black,
)

// ── Type scale (DESIGN.md `typography:` → M3 roles; system font, weights only) ────────
private val GuacamayaTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-1).sp),     // display-lg
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.8).sp), // display-md
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-0.5).sp),  // display-sm
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),     // title-lg
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),                           // title-md
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),                            // title-sm
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),                               // body-lg
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),                              // body-md
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),                               // body-sm
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 16.sp),                            // button
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),                            // caption
    labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.2.sp),       // overline / nav
)

// ── Shapes (DESIGN.md `rounded:`) ─────────────────────────────────────────────────────
private val GuacamayaShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// ── Spacing (DESIGN.md `spacing:`; constant dp — a plain object, not theme-dependent) ──
object Space {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val section = 40.dp
}

// ── Extension palette — the semantic + surface tones M3 has no role for ────────────────
@Immutable
data class GuacamayaColors(
    val canvas: Color,
    val surfaceSoft: Color,
    val surfaceCard: Color,
    val surfaceElevated: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val ink: Color,
    val body: Color,
    val bodyStrong: Color,
    val muted: Color,
    val mutedSoft: Color,
    val brand: Color,
    val onBrand: Color,
    val danger: Color,
    val dangerSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val success: Color,
    val successSoft: Color,
    val info: Color,
    val infoSoft: Color,
    val onSemantic: Color,
)

private val DefaultGuacamayaColors = GuacamayaColors(
    canvas = GuacamayaPalette.Canvas,
    surfaceSoft = GuacamayaPalette.SurfaceSoft,
    surfaceCard = GuacamayaPalette.SurfaceCard,
    surfaceElevated = GuacamayaPalette.SurfaceElevated,
    hairline = GuacamayaPalette.Hairline,
    hairlineStrong = GuacamayaPalette.HairlineStrong,
    ink = GuacamayaPalette.Ink,
    body = GuacamayaPalette.Body,
    bodyStrong = GuacamayaPalette.BodyStrong,
    muted = GuacamayaPalette.Muted,
    mutedSoft = GuacamayaPalette.MutedSoft,
    brand = GuacamayaPalette.Primary,
    onBrand = GuacamayaPalette.OnPrimary,
    danger = GuacamayaPalette.Danger,
    dangerSoft = GuacamayaPalette.DangerSoft,
    warning = GuacamayaPalette.Warning,
    warningSoft = GuacamayaPalette.WarningSoft,
    success = GuacamayaPalette.Success,
    successSoft = GuacamayaPalette.SuccessSoft,
    info = GuacamayaPalette.Info,
    infoSoft = GuacamayaPalette.InfoSoft,
    onSemantic = GuacamayaPalette.OnSemantic,
)

val LocalGuacamayaColors = staticCompositionLocalOf { DefaultGuacamayaColors }

/** Accessor for the extension palette: `MaterialTheme.guacamaya.danger`, etc. */
val MaterialTheme.guacamaya: GuacamayaColors
    @Composable @ReadOnlyComposable get() = LocalGuacamayaColors.current

@Composable
fun GuacamayaTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGuacamayaColors provides DefaultGuacamayaColors) {
        MaterialTheme(
            colorScheme = GuacamayaColorScheme,
            typography = GuacamayaTypography,
            shapes = GuacamayaShapes,
            content = content,
        )
    }
}
