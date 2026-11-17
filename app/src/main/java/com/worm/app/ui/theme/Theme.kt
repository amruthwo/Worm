package com.worm.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.worm.app.R

/** The four colours of the Worm palette, plus a few tones derived from the icon. */
object Worm {
    val Plum = Color(0xFF542177)
    val Violet = Color(0xFF8E1EA2)
    val Pink = Color(0xFFD552A3)
    val Mint = Color(0xFF98E8DE)

    val Bubblegum = Color(0xFFE46FB4)   // the worm's body
    val Blush = Color(0xFFF7A6D4)
    val Ink = Color(0xFF3B1052)          // outlines in the icon
    val Night = Color(0xFF160A21)
    val Lilac = Color(0xFFB57BD6)

    /** Palette used by the drifting "page dust" particles. */
    val Dust = listOf(Pink, Mint, Violet, Bubblegum, Blush, Lilac)
}

/** App-wide looks. Reader pages have their own themes (see ReaderTheme). */
enum class AppTheme(val label: String, val dark: Boolean) {
    Candy("Candy", true),
    Dusk("Dusk", true),
    Amoled("AMOLED", true),
    Daylight("Daylight", false),
    Parchment("Parchment", false),
    /** Black on white, no motion: for e-ink readers like Boox and Kobo. */
    Eink("E-ink", false),
}

/** Extra colours that Material's scheme has no slot for: backdrops, particles, hero gradients. */
@Immutable
data class WormColors(
    val backdrop: Color,
    val glowA: Color,
    val glowB: Color,
    val glowC: Color,
    val dust: List<Color>,
    val particles: Boolean,
    val isDark: Boolean,
    /** E-ink: no ambient animation, no transitions, nothing that makes the panel refresh. */
    val calm: Boolean = false,
)

val LocalWormColors = staticCompositionLocalOf {
    WormColors(Worm.Night, Worm.Violet, Worm.Pink, Worm.Mint, Worm.Dust, true, true)
}

private val BurrowScheme = darkColorScheme(
    primary = Color(0xFFFF8AC8),
    onPrimary = Color(0xFF5A0B3C),
    primaryContainer = Color(0xFFA62A7C),
    onPrimaryContainer = Color(0xFFFFD8EC),
    inversePrimary = Worm.Pink,
    secondary = Worm.Mint,
    onSecondary = Color(0xFF003732),
    secondaryContainer = Color(0xFF1F5F58),
    onSecondaryContainer = Color(0xFFB7FFF5),
    tertiary = Color(0xFFE2B6FF),
    onTertiary = Color(0xFF45106A),
    tertiaryContainer = Color(0xFF6A2D93),
    onTertiaryContainer = Color(0xFFF4DAFF),
    background = Worm.Night,
    onBackground = Color(0xFFF4E2F4),
    surface = Worm.Night,
    onSurface = Color(0xFFF4E2F4),
    surfaceVariant = Color(0xFF4A3256),
    onSurfaceVariant = Color(0xFFD9C2DC),
    surfaceTint = Color(0xFFFF8AC8),
    inverseSurface = Color(0xFFF4E2F4),
    inverseOnSurface = Color(0xFF2E1B37),
    outline = Color(0xFFA28BA6),
    outlineVariant = Color(0xFF52395C),
    scrim = Color.Black,
    surfaceBright = Color(0xFF41284F),
    surfaceDim = Worm.Night,
    surfaceContainerLowest = Color(0xFF0E0616),
    surfaceContainerLow = Color(0xFF1E0F2A),
    surfaceContainer = Color(0xFF261433),
    surfaceContainerHigh = Color(0xFF311B40),
    surfaceContainerHighest = Color(0xFF3C234D),
)

private val DuskScheme = darkColorScheme(
    primary = Color(0xFFFFB0A0),
    onPrimary = Color(0xFF5C1A10),
    primaryContainer = Color(0xFF8C3B4E),
    onPrimaryContainer = Color(0xFFFFDAD4),
    inversePrimary = Color(0xFFB04A5A),
    secondary = Color(0xFFC6BFFF),
    onSecondary = Color(0xFF2C2268),
    secondaryContainer = Color(0xFF433A85),
    onSecondaryContainer = Color(0xFFE4DFFF),
    tertiary = Worm.Mint,
    onTertiary = Color(0xFF003732),
    tertiaryContainer = Color(0xFF1F5F58),
    onTertiaryContainer = Color(0xFFB7FFF5),
    background = Color(0xFF151327),
    onBackground = Color(0xFFE8E2F4),
    surface = Color(0xFF151327),
    onSurface = Color(0xFFE8E2F4),
    surfaceVariant = Color(0xFF45405C),
    onSurfaceVariant = Color(0xFFCBC4DE),
    outline = Color(0xFF948EA8),
    outlineVariant = Color(0xFF45405C),
    surfaceContainerLowest = Color(0xFF0F0D1E),
    surfaceContainerLow = Color(0xFF1D1A33),
    surfaceContainer = Color(0xFF231F3B),
    surfaceContainerHigh = Color(0xFF2D2847),
    surfaceContainerHighest = Color(0xFF383254),
)

private val AmoledScheme = BurrowScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B060E),
    surfaceContainer = Color(0xFF120A16),
    surfaceContainerHigh = Color(0xFF1A1020),
    surfaceContainerHighest = Color(0xFF23162A),
)

private val CandyScheme = BurrowScheme.copy(
    primary = Worm.Mint,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF13756A),
    onPrimaryContainer = Color(0xFFC6FFF6),
    secondary = Color(0xFFFF8AC8),
    onSecondary = Color(0xFF5A0B3C),
    secondaryContainer = Color(0xFF9C2A75),
    onSecondaryContainer = Color(0xFFFFD8EC),
    tertiary = Color(0xFFD9B8FF),
    onTertiary = Color(0xFF3E1466),
    tertiaryContainer = Color(0xFF7B3FB0),
    onTertiaryContainer = Color(0xFFF3E2FF),
    background = Color(0xFF1A0B2E),
    surface = Color(0xFF1A0B2E),
    surfaceContainerLow = Color(0xFF241339),
    surfaceContainer = Color(0xFF2D1A45),
    surfaceContainerHigh = Color(0xFF382152),
    surfaceContainerHighest = Color(0xFF43295E),
)

private val DaylightScheme = lightColorScheme(
    primary = Color(0xFF8E1E82),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD7EE),
    onPrimaryContainer = Color(0xFF3B0532),
    inversePrimary = Color(0xFFFF8AC8),
    secondary = Color(0xFF006A60),
    onSecondary = Color.White,
    secondaryContainer = Worm.Mint,
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Worm.Plum,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF1DBFF),
    onTertiaryContainer = Color(0xFF2B0A40),
    background = Color(0xFFFFF7FC),
    onBackground = Color(0xFF221824),
    surface = Color(0xFFFFF7FC),
    onSurface = Color(0xFF221824),
    surfaceVariant = Color(0xFFF0DDEB),
    onSurfaceVariant = Color(0xFF4F4350),
    outline = Color(0xFF817381),
    outlineVariant = Color(0xFFD3C2D2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCF0F8),
    surfaceContainer = Color(0xFFF7EAF3),
    surfaceContainerHigh = Color(0xFFF1E4ED),
    surfaceContainerHighest = Color(0xFFEBDEE8),
)

private val ParchmentScheme = lightColorScheme(
    primary = Color(0xFF7A3B5E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF2D8C2),
    onPrimaryContainer = Color(0xFF3A1A0E),
    secondary = Color(0xFF52634F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E5CF),
    onSecondaryContainer = Color(0xFF111F10),
    tertiary = Color(0xFF8A5A2B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCB9),
    onTertiaryContainer = Color(0xFF2E1500),
    background = Color(0xFFF6EEDC),
    onBackground = Color(0xFF2B231A),
    surface = Color(0xFFF6EEDC),
    onSurface = Color(0xFF2B231A),
    surfaceVariant = Color(0xFFEADFC8),
    onSurfaceVariant = Color(0xFF55493A),
    outline = Color(0xFF86796A),
    outlineVariant = Color(0xFFD6C8B2),
    surfaceContainerLowest = Color(0xFFFFFAF0),
    surfaceContainerLow = Color(0xFFF9F1E1),
    surfaceContainer = Color(0xFFF1E7D3),
    surfaceContainerHigh = Color(0xFFEBE0CB),
    surfaceContainerHighest = Color(0xFFE4D8C2),
)

private val EinkScheme = lightColorScheme(
    primary = Color.Black, onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E6E6), onPrimaryContainer = Color.Black,
    secondary = Color.Black, onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E6E6), onSecondaryContainer = Color.Black,
    tertiary = Color.Black, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6E6E6), onTertiaryContainer = Color.Black,
    background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F0F0), onSurfaceVariant = Color(0xFF222222),
    outline = Color.Black, outlineVariant = Color(0xFF888888),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF4F4F4), surfaceContainerHigh = Color(0xFFEEEEEE), surfaceContainerHighest = Color(0xFFE6E6E6),
    error = Color.Black, onError = Color.White,
)

/** Every Material motion snaps instantly: e-ink panels smear anything that moves. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
private object SnapMotion : MotionScheme {
    override fun <T> defaultSpatialSpec(): androidx.compose.animation.core.FiniteAnimationSpec<T> = androidx.compose.animation.core.snap()
    override fun <T> fastSpatialSpec(): androidx.compose.animation.core.FiniteAnimationSpec<T> = androidx.compose.animation.core.snap()
    override fun <T> slowSpatialSpec(): androidx.compose.animation.core.FiniteAnimationSpec<T> = androidx.compose.animation.core.snap()
    override fun <T> defaultEffectsSpec(): androidx.compose.animation.core.FiniteAnimationSpec<T> = androidx.compose.animation.core.snap()
    override fun <T> fastEffectsSpec(): androidx.compose.animation.core.FiniteAnimationSpec<T> = androidx.compose.animation.core.snap()
    override fun <T> slowEffectsSpec(): androidx.compose.animation.core.FiniteAnimationSpec<T> = androidx.compose.animation.core.snap()
}

fun AppTheme.scheme(): ColorScheme = when (this) {
    AppTheme.Eink -> EinkScheme
    AppTheme.Dusk -> DuskScheme
    AppTheme.Amoled -> AmoledScheme
    AppTheme.Daylight -> DaylightScheme
    AppTheme.Parchment -> ParchmentScheme
    AppTheme.Candy -> CandyScheme
}

fun AppTheme.extras(): WormColors = when (this) {
    AppTheme.Eink -> WormColors(Color.White, Color.White, Color.White, Color.White, listOf(Color.Black), particles = false, isDark = false, calm = true)
    AppTheme.Dusk -> WormColors(Color(0xFF151327), Color(0xFF5B3F9E), Color(0xFFB04A5A), Color(0xFF2E4A8C), listOf(Color(0xFFFFB0A0), Color(0xFFC6BFFF), Worm.Mint, Color(0xFFFFD6E0)), true, true)
    AppTheme.Amoled -> WormColors(Color.Black, Worm.Plum, Color(0xFF6B1F55), Color(0xFF123F3B), Worm.Dust, false, true)
    AppTheme.Candy -> WormColors(Color(0xFF1A0B2E), Worm.Pink, Worm.Mint, Worm.Violet, Worm.Dust, true, true)
    AppTheme.Daylight -> WormColors(Color(0xFFFFF7FC), Color(0xFFFFC3E4), Worm.Mint, Color(0xFFE6CCFF), Worm.Dust, true, false)
    AppTheme.Parchment -> WormColors(Color(0xFFF6EEDC), Color(0xFFF0D2B4), Color(0xFFD9E6C9), Color(0xFFEBC8C8),
        listOf(Color(0xFFB08560), Color(0xFF8A6B4E), Color(0xFFC9A27E)), false, false)
}

private val Bricolage = FontFamily(
    Font(R.font.bricolage, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.bricolage, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.bricolage, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.bricolage, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.bricolage, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

private fun TextStyle.display(weight: FontWeight) = copy(fontFamily = Bricolage, fontWeight = weight)

private val base = Typography()
private val WormType = Typography(
    displayLarge = base.displayLarge.display(FontWeight.ExtraBold),
    displayMedium = base.displayMedium.display(FontWeight.ExtraBold),
    displaySmall = base.displaySmall.display(FontWeight.Bold),
    headlineLarge = base.headlineLarge.display(FontWeight.Bold),
    headlineMedium = base.headlineMedium.display(FontWeight.Bold),
    headlineSmall = base.headlineSmall.display(FontWeight.SemiBold),
    titleLarge = base.titleLarge.display(FontWeight.SemiBold),
    titleMedium = base.titleMedium.display(FontWeight.SemiBold),
    titleSmall = base.titleSmall.display(FontWeight.Medium),
    labelLarge = base.labelLarge.display(FontWeight.SemiBold),
)

@Composable
fun WormTheme(theme: AppTheme = AppTheme.Candy, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWormColors provides theme.extras()) {
        MaterialExpressiveTheme(
            colorScheme = theme.scheme(),
            typography = WormType,
            motionScheme = if (theme == AppTheme.Eink) SnapMotion else MotionScheme.expressive(),
        ) {
            // Text on see-through surfaces (aurora backdrops, translucent cards) must never fall back to black.
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides theme.scheme().onBackground, content = content)
        }
    }
}
