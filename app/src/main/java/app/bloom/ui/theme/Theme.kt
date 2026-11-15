package app.bloom.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils

/** The three selectable looks. Stored in core settings under "theme". */
enum class Look(val key: String, val label: String, val blurb: String) {
    MATERIAL("material", "Material You", "Wallpaper colours, living shapes, glass and glow"),
    DISCORD("discord", "Discord style", "Familiar dark greys, round icons, flat and dense"),
    ROOT("root", "Root style", "Deep navy, soft squares, calm accents");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: MATERIAL
    }
}

/** How server/app icons behave for a look. */
enum class IconMode {
    /** Expressive shapes that slowly rotate and morph; static rounded square when selected. */
    LIVING,
    /** Circle that morphs to a rounded square on selection (Discord-like). */
    CIRCLE_TO_SQUARE,
    /** Soft squircle that tightens on selection, with an accent ring. */
    SQUIRCLE,
}

/** Everything the UI needs to know about the active look, beyond colours. */
@Immutable
data class LookSpec(
    val look: Look,
    val iconMode: IconMode,
    /** Backdrop gaussian blur behind top bar / composer. */
    val glass: Boolean,
    /** Contour glow + soft glows. */
    val glow: Boolean,
    /** Corner radius for rows and cards. */
    val rowRadius: Dp,
    val cardRadius: Dp,
    /** Selected icon corner, as fraction of size (0.5 = circle). */
    val selectedCorner: Float,
    /** Springy (bouncy) motion vs. crisp motion. */
    val bouncy: Boolean,
    /** How much avatar hues get pulled toward the accent colour. */
    val hueHarmonize: Float,
)

val LocalLook = staticCompositionLocalOf { specFor(Look.MATERIAL) }
val LocalDark = staticCompositionLocalOf { true }

fun specFor(look: Look) = when (look) {
    Look.MATERIAL -> LookSpec(look, IconMode.LIVING, glass = true, glow = true, rowRadius = 18.dp, cardRadius = 28.dp, selectedCorner = 0.3f, bouncy = true, hueHarmonize = 0.25f)
    Look.DISCORD -> LookSpec(look, IconMode.CIRCLE_TO_SQUARE, glass = false, glow = false, rowRadius = 6.dp, cardRadius = 8.dp, selectedCorner = 0.33f, bouncy = false, hueHarmonize = 0f)
    Look.ROOT -> LookSpec(look, IconMode.SQUIRCLE, glass = true, glow = false, rowRadius = 10.dp, cardRadius = 16.dp, selectedCorner = 0.28f, bouncy = false, hueHarmonize = 0.1f)
}

/**
 * App-wide look & motion preferences (Settings → Customize). Plain Compose state, so
 * changing one re-styles every screen live.
 */
object Ui {
    /** false: animations jump straight to their end */
    var animations by androidx.compose.runtime.mutableStateOf(true)
    /** 0.5x … 2x */
    var speed by androidx.compose.runtime.mutableFloatStateOf(1f)
    /** things fade in from a soft blur (messages, sheets, pages) */
    var elementBlur by androidx.compose.runtime.mutableStateOf(true)
    /** frosted glass behind bars and panels */
    var glass by androidx.compose.runtime.mutableStateOf(true)
    /** glass blur strength, dp */
    var blurStrength by androidx.compose.runtime.mutableFloatStateOf(24f)
    /** corner roundness multiplier for rows, cards, media */
    var rounding by androidx.compose.runtime.mutableFloatStateOf(1f)
    /** "rise" (fade + slide up) · "blur" (fade from blur) · "fade" · "none" */
    var messageEnter by androidx.compose.runtime.mutableStateOf("rise")
    /** hide the status bar for a full-screen look */
    var hideStatusBar by androidx.compose.runtime.mutableStateOf(false)
    /** server icons morph: "always" · "selected" (only the open one) · "off" */
    var livingIcons by androidx.compose.runtime.mutableStateOf("selected")
    /** emoji artwork: system · apple · fluent · google · twitter · facebook */
    var emojiPack by androidx.compose.runtime.mutableStateOf("system")
    /** font id (see Fonts), where it applies, and tweaks */
    var fontId by androidx.compose.runtime.mutableStateOf("system")
    var fontScope by androidx.compose.runtime.mutableStateOf("all") // all · messages
    var fontBold by androidx.compose.runtime.mutableIntStateOf(0) // +0 … +200 weight
    var letterSpacing by androidx.compose.runtime.mutableFloatStateOf(0f) // em
    /** loaded font family for [fontId] (null = system) */
    var fontFamily by androidx.compose.runtime.mutableStateOf<androidx.compose.ui.text.font.FontFamily?>(null)
    /** Android battery saver is on: effects step down, refresh rate isn't forced */
    var powerSave by androidx.compose.runtime.mutableStateOf(false)
    /** what effects actually run (power saver wins) */
    val effectsOn get() = animations && !powerSave
}

/** Motion tokens. Expressive looks bounce; flat looks are crisp. Every spec is interruptible and follows [Ui.speed]. */
object Motion {
    @Volatile var bouncy = true
    // speed x → springs settle x times faster (stiffness scales with the square)
    private fun k(stiffness: Float) = stiffness * Ui.speed * Ui.speed
    fun <T> spatial(): FiniteAnimationSpec<T> = when {
        !Ui.animations -> snap()
        bouncy -> spring(dampingRatio = 0.78f, stiffness = k(420f))
        else -> spring(dampingRatio = 1f, stiffness = k(700f))
    }
    fun <T> spatialFast(): FiniteAnimationSpec<T> = when {
        !Ui.animations -> snap()
        bouncy -> spring(dampingRatio = 0.6f, stiffness = k(900f))
        else -> spring(dampingRatio = 1f, stiffness = k(1400f))
    }
    fun <T> effects(): FiniteAnimationSpec<T> = if (!Ui.animations) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = k(380f))
    /** smooth, no overshoot: for content appearing (messages, reactions) */
    fun <T> smooth(): FiniteAnimationSpec<T> = if (!Ui.animations) snap() else spring(dampingRatio = 1f, stiffness = k(260f))
    fun <T> quick(): FiniteAnimationSpec<T> = if (!Ui.animations) snap() else tween((140 / Ui.speed).toInt())
}

// Discord-style palette (dark). Values are the widely documented client greys/accent.
private val DiscordDark = darkColorScheme(
    primary = Color(0xFF5865F2), onPrimary = Color.White,
    primaryContainer = Color(0xFF4752C4), onPrimaryContainer = Color.White,
    secondary = Color(0xFFB5BAC1), onSecondary = Color(0xFF1E1F22),
    secondaryContainer = Color(0xFF404249), onSecondaryContainer = Color(0xFFF2F3F5),
    tertiary = Color(0xFF00A8FC), onTertiary = Color.White,
    tertiaryContainer = Color(0xFF3C45A5), onTertiaryContainer = Color.White,
    background = Color(0xFF313338), onBackground = Color(0xFFDBDEE1),
    surface = Color(0xFF313338), onSurface = Color(0xFFDBDEE1),
    surfaceVariant = Color(0xFF2B2D31), onSurfaceVariant = Color(0xFFB5BAC1),
    surfaceContainerLowest = Color(0xFF1E1F22),
    surfaceContainerLow = Color(0xFF2B2D31),
    surfaceContainer = Color(0xFF2B2D31),
    surfaceContainerHigh = Color(0xFF383A40),
    surfaceContainerHighest = Color(0xFF404249),
    outline = Color(0xFF949BA4), outlineVariant = Color(0xFF3F4147),
    error = Color(0xFFF23F43), onError = Color.White, errorContainer = Color(0xFF5C2427), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFDBDEE1), inverseOnSurface = Color(0xFF313338),
)

// Root-style palette. Best guess (not verified against the real app): deep navy, calm blue accent.
// Send screenshots of Root and these values can be matched exactly — they're all in one place.
private val RootDark = darkColorScheme(
    primary = Color(0xFF6C8CFF), onPrimary = Color(0xFF0B1230),
    primaryContainer = Color(0xFF2A3A78), onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFFA9B4D0), onSecondary = Color(0xFF151B2C),
    secondaryContainer = Color(0xFF242C42), onSecondaryContainer = Color(0xFFE6EBFA),
    tertiary = Color(0xFF55D6BE), onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF1F4B49), onTertiaryContainer = Color(0xFFCFF7EE),
    background = Color(0xFF12161F), onBackground = Color(0xFFE4E8F2),
    surface = Color(0xFF12161F), onSurface = Color(0xFFE4E8F2),
    surfaceVariant = Color(0xFF1A2030), onSurfaceVariant = Color(0xFFAEB6CA),
    surfaceContainerLowest = Color(0xFF0B0E15),
    surfaceContainerLow = Color(0xFF151A25),
    surfaceContainer = Color(0xFF181E2B),
    surfaceContainerHigh = Color(0xFF202737),
    surfaceContainerHighest = Color(0xFF283044),
    outline = Color(0xFF8891A8), outlineVariant = Color(0xFF2C3446),
    error = Color(0xFFFF6B6B), onError = Color(0xFF3B0000), errorContainer = Color(0xFF5A1E22), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE4E8F2), inverseOnSurface = Color(0xFF12161F),
)

/** Pure-black variant for OLED screens; keeps the layering by lifting containers slightly. */
private fun ColorScheme.amoled() = copy(
    background = Color.Black, surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF070708),
    surfaceContainer = Color(0xFF0E0E10),
    surfaceContainerHigh = Color(0xFF17171A),
    surfaceContainerHighest = Color(0xFF202024),
)

private fun typography(look: Look) = Typography().run {
    val heavy = if (look == Look.MATERIAL) FontWeight.Black else FontWeight.ExtraBold
    copy(
        displaySmall = displaySmall.copy(fontWeight = heavy, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    )
}

/** Every colour role the user can override, grouped for the Settings UI. */
enum class ColorRole(val label: String, val group: String) {
    PRIMARY("Primary", "Accent"), ON_PRIMARY("On primary", "Accent"),
    PRIMARY_CONTAINER("Primary container", "Accent"), ON_PRIMARY_CONTAINER("On primary container", "Accent"),
    SECONDARY("Secondary", "Accent"), SECONDARY_CONTAINER("Secondary container (selection)", "Accent"),
    ON_SECONDARY_CONTAINER("On secondary container", "Accent"),
    TERTIARY("Tertiary", "Accent"), TERTIARY_CONTAINER("Tertiary container", "Accent"),
    BACKGROUND("Chat background", "Surfaces"),
    SURFACE_LOWEST("Server rail", "Surfaces"), SURFACE_LOW("Channel list", "Surfaces"),
    SURFACE("Cards & sheets", "Surfaces"), SURFACE_HIGH("Raised / composer", "Surfaces"),
    SURFACE_HIGHEST("Highest (chips, inputs)", "Surfaces"),
    ON_SURFACE("Text", "Text"), ON_SURFACE_VARIANT("Secondary text & icons", "Text"),
    OUTLINE("Outlines", "Text"), OUTLINE_VARIANT("Dividers", "Text"),
    ERROR("Error / deleted", "Status"), ON_ERROR("On error", "Status"),
}

private fun ColorScheme.with(role: ColorRole, c: Color): ColorScheme = when (role) {
    ColorRole.PRIMARY -> copy(primary = c)
    ColorRole.ON_PRIMARY -> copy(onPrimary = c)
    ColorRole.PRIMARY_CONTAINER -> copy(primaryContainer = c)
    ColorRole.ON_PRIMARY_CONTAINER -> copy(onPrimaryContainer = c)
    ColorRole.SECONDARY -> copy(secondary = c)
    ColorRole.SECONDARY_CONTAINER -> copy(secondaryContainer = c)
    ColorRole.ON_SECONDARY_CONTAINER -> copy(onSecondaryContainer = c)
    ColorRole.TERTIARY -> copy(tertiary = c)
    ColorRole.TERTIARY_CONTAINER -> copy(tertiaryContainer = c)
    ColorRole.BACKGROUND -> copy(background = c, surface = c)
    ColorRole.SURFACE_LOWEST -> copy(surfaceContainerLowest = c)
    ColorRole.SURFACE_LOW -> copy(surfaceContainerLow = c)
    ColorRole.SURFACE -> copy(surfaceContainer = c)
    ColorRole.SURFACE_HIGH -> copy(surfaceContainerHigh = c)
    ColorRole.SURFACE_HIGHEST -> copy(surfaceContainerHighest = c)
    ColorRole.ON_SURFACE -> copy(onSurface = c, onBackground = c)
    ColorRole.ON_SURFACE_VARIANT -> copy(onSurfaceVariant = c)
    ColorRole.OUTLINE -> copy(outline = c)
    ColorRole.OUTLINE_VARIANT -> copy(outlineVariant = c)
    ColorRole.ERROR -> copy(error = c)
    ColorRole.ON_ERROR -> copy(onError = c)
}

fun ColorScheme.get(role: ColorRole): Color = when (role) {
    ColorRole.PRIMARY -> primary
    ColorRole.ON_PRIMARY -> onPrimary
    ColorRole.PRIMARY_CONTAINER -> primaryContainer
    ColorRole.ON_PRIMARY_CONTAINER -> onPrimaryContainer
    ColorRole.SECONDARY -> secondary
    ColorRole.SECONDARY_CONTAINER -> secondaryContainer
    ColorRole.ON_SECONDARY_CONTAINER -> onSecondaryContainer
    ColorRole.TERTIARY -> tertiary
    ColorRole.TERTIARY_CONTAINER -> tertiaryContainer
    ColorRole.BACKGROUND -> surface
    ColorRole.SURFACE_LOWEST -> surfaceContainerLowest
    ColorRole.SURFACE_LOW -> surfaceContainerLow
    ColorRole.SURFACE -> surfaceContainer
    ColorRole.SURFACE_HIGH -> surfaceContainerHigh
    ColorRole.SURFACE_HIGHEST -> surfaceContainerHighest
    ColorRole.ON_SURFACE -> onSurface
    ColorRole.ON_SURFACE_VARIANT -> onSurfaceVariant
    ColorRole.OUTLINE -> outline
    ColorRole.OUTLINE_VARIANT -> outlineVariant
    ColorRole.ERROR -> error
    ColorRole.ON_ERROR -> onError
}

/** Floating background shapes (Material You). */
@Immutable
data class BgShapes(
    val enabled: Boolean = true,
    val count: Int = 6,
    /** degrees per second */
    val rotateSpeed: Float = 10f,
    /** shape changes per minute */
    val morphSpeed: Float = 12f,
    val blur: Float = 24f,
    val opacity: Float = 0.35f,
)

val LocalBgShapes = staticCompositionLocalOf { BgShapes(enabled = false) }

@Composable
fun BloomTheme(
    look: Look,
    amoled: Boolean,
    overrides: Map<ColorRole, Color> = emptyMap(),
    bgShapes: BgShapes = BgShapes(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val dark = look != Look.MATERIAL || systemDark
    var scheme = when (look) {
        Look.MATERIAL -> if (systemDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        Look.DISCORD -> DiscordDark
        Look.ROOT -> RootDark
    }
    if (amoled && dark) scheme = scheme.amoled()
    overrides.forEach { (role, c) -> scheme = scheme.with(role, c) }
    val spec = specFor(look).let { b -> b.copy(rowRadius = b.rowRadius * Ui.rounding, cardRadius = b.cardRadius * Ui.rounding, glass = b.glass && Ui.glass) }
    Motion.bouncy = spec.bouncy
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(spec.rowRadius / 2),
        small = RoundedCornerShape(spec.rowRadius),
        medium = RoundedCornerShape(spec.cardRadius * 0.7f),
        large = RoundedCornerShape(spec.cardRadius),
        extraLarge = RoundedCornerShape(spec.cardRadius * 1.3f),
    )
    CompositionLocalProvider(
        LocalDark provides dark,
        LocalLook provides spec,
        LocalBgShapes provides if (look == Look.MATERIAL) bgShapes else bgShapes.copy(enabled = false),
    ) {
        val baseType = typography(look)
        val fam = Ui.fontFamily
        val type = if (fam != null && Ui.fontScope == "all") baseType.withFont(fam, Ui.letterSpacing) else baseType.withSpacing(Ui.letterSpacing)
        MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes) {
            // Default text/icon colour everywhere (otherwise un-coloured Text falls back to black).
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onSurface, content = content)
        }
    }
}

private fun androidx.compose.ui.text.TextStyle.f(fam: androidx.compose.ui.text.font.FontFamily?, ls: Float) =
    copy(fontFamily = fam ?: fontFamily, letterSpacing = if (ls != 0f) androidx.compose.ui.unit.TextUnit(ls, androidx.compose.ui.unit.TextUnitType.Em) else letterSpacing)

private fun Typography.withFont(fam: androidx.compose.ui.text.font.FontFamily?, ls: Float) = Typography(
    displayLarge.f(fam, ls), displayMedium.f(fam, ls), displaySmall.f(fam, ls),
    headlineLarge.f(fam, ls), headlineMedium.f(fam, ls), headlineSmall.f(fam, ls),
    titleLarge.f(fam, ls), titleMedium.f(fam, ls), titleSmall.f(fam, ls),
    bodyLarge.f(fam, ls), bodyMedium.f(fam, ls), bodySmall.f(fam, ls),
    labelLarge.f(fam, ls), labelMedium.f(fam, ls), labelSmall.f(fam, ls),
)

private fun Typography.withSpacing(ls: Float) = if (ls == 0f) this else withFont(null, ls)

/**
 * Turns a hue from the core into a readable colour for the current look; in
 * Material You it's blended toward the primary so avatars sit in the palette.
 */
@Composable
fun hueColor(hue: Int, tone: Float = if (LocalDark.current) 0.62f else 0.5f): Color {
    val raw = Color(ColorUtils.HSLToColor(floatArrayOf(((hue % 360) + 360) % 360f, 0.6f, tone)))
    return lerp(raw, MaterialTheme.colorScheme.primary, LocalLook.current.hueHarmonize)
}

/** Text colour with guaranteed contrast on [bg] (WCAG-ish luminance test). */
fun contentOn(bg: Color): Color =
    if (ColorUtils.calculateContrast(Color.White.toArgb(), bg.copy(alpha = 1f).toArgb()) >= 3.0) Color.White else Color(0xFF15161A)

@Composable
fun onHueColor(hue: Int): Color = contentOn(hueColor(hue))
