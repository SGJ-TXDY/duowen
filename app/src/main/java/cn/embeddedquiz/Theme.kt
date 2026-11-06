package cn.embeddedquiz

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private tailrec fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.takeUnless { it === this }?.activity()
        else -> null
    }

/**
 * One theme accent. [color] is the light-mode primary; dark mode derives its tones by mixing.
 * [selected] / [selectedLine] tint chosen chips and the user's chat bubbles.
 */
@Immutable
data class Accent(
    val id: String,
    val label: String,
    val color: Color,
    val container: Color,
    val onContainer: Color,
    val selected: Color,
    val selectedLine: Color,
    /** How far the dark-mode primary is mixed toward white. */
    val darkLift: Float = 0.42f,
)

/**
 * Teal and green are darker than their usual swatches so white text on them stays above 4.5:1;
 * graphite lifts further in dark mode so its primary text stays readable on dark cards.
 */
val ACCENTS =
    listOf(
        Accent("blue", "景泰蓝", Color(0xFF2563EB), Color(0xFFE3EDFF), Color(0xFF0C2D6B), Color(0xFFEDF3FE), Color(0xFFBFD5F7)),
        Accent("graphite", "石墨黑", Color(0xFF1F2937), Color(0xFFE8EAEE), Color(0xFF0B1017), Color(0xFFEDEFF2), Color(0xFFCDD2D9), darkLift = 0.5f),
        Accent("teal", "青瓷", Color(0xFF0A7A6A), Color(0xFFDDF6F1), Color(0xFF053B33), Color(0xFFE7F8F5), Color(0xFFA9E0D6)),
        Accent("green", "竹青", Color(0xFF2B7A52), Color(0xFFE2F3E9), Color(0xFF0B3823), Color(0xFFEAF7EF), Color(0xFFB7DEC7)),
        Accent("orange", "朱砂", Color(0xFFC2410C), Color(0xFFFDE9DF), Color(0xFF4A1804), Color(0xFFFDF0E9), Color(0xFFF3C4AB)),
        Accent("purple", "绛紫", Color(0xFF7C3AED), Color(0xFFEDE6FD), Color(0xFF2E1065), Color(0xFFF2EDFE), Color(0xFFCDBBF8)),
        Accent("rose", "胭脂", Color(0xFFBE185D), Color(0xFFFCE4EF), Color(0xFF4C0519), Color(0xFFFDEDF4), Color(0xFFF2B6CF)),
        Accent("amber", "焦糖", Color(0xFF92400E), Color(0xFFFBEEDA), Color(0xFF3D1B04), Color(0xFFFCF3E6), Color(0xFFEBC79A)),
    )

/** Unknown or missing ids fall back to the first accent, so a stale preference never breaks the theme. */
fun accentOf(id: String?): Accent = ACCENTS.firstOrNull { it.id == id } ?: ACCENTS.first()

private val DarkBase = Color(0xFF10131A)

private fun mix(a: Color, b: Color, ratioB: Float) =
    Color(
        red = a.red * (1 - ratioB) + b.red * ratioB,
        green = a.green * (1 - ratioB) + b.green * ratioB,
        blue = a.blue * (1 - ratioB) + b.blue * ratioB,
    )

/** Neutral gray surfaces with white cards; only the primary tones follow the chosen accent. */
fun appColors(dark: Boolean, accent: Accent = ACCENTS.first()) =
    if (dark)
        darkColorScheme(
            primary = mix(accent.color, Color.White, accent.darkLift),
            onPrimary = mix(accent.color, DarkBase, 0.85f),
            primaryContainer = mix(accent.color, DarkBase, 0.70f),
            onPrimaryContainer = mix(accent.color, Color.White, 0.84f),
            secondary = Color(0xFFC2C7CF),
            onSecondary = Color(0xFF2A2E35),
            secondaryContainer = Color(0xFF33373E),
            onSecondaryContainer = Color(0xFFE3E5E9),
            tertiary = Color(0xFFFFC069),
            onTertiary = Color(0xFF4A2800),
            tertiaryContainer = Color(0xFF5A3B00),
            onTertiaryContainer = Color(0xFFFFE0B8),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            background = Color(0xFF0F1115),
            onBackground = Color(0xFFE7E8EA),
            surface = Color(0xFF0F1115),
            onSurface = Color(0xFFE7E8EA),
            surfaceVariant = Color(0xFF2A2E35),
            onSurfaceVariant = Color(0xFFA5A9AF),
            outline = Color(0xFF7C828B),
            outlineVariant = Color(0xFF33373E),
            inverseSurface = Color(0xFFE7E8EA),
            inverseOnSurface = Color(0xFF2A2E35),
            inversePrimary = accent.color,
            surfaceTint = mix(accent.color, Color.White, accent.darkLift),
            surfaceContainerLowest = Color(0xFF1A1D23),
            surfaceContainerLow = Color(0xFF16181D),
            surfaceContainer = Color(0xFF1A1D23),
            surfaceContainerHigh = Color(0xFF23262C),
            surfaceContainerHighest = Color(0xFF2A2E35),
        )
    else
        lightColorScheme(
            primary = accent.color,
            onPrimary = Color.White,
            primaryContainer = accent.container,
            onPrimaryContainer = accent.onContainer,
            secondary = Color(0xFF4B5563),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFEFF1F4),
            onSecondaryContainer = Color(0xFF1F2937),
            tertiary = Color(0xFF8B5000),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFFFE9C7),
            onTertiaryContainer = Color(0xFF2D1600),
            error = Color(0xFFB42318),
            onError = Color.White,
            errorContainer = Color(0xFFFDECEA),
            onErrorContainer = Color(0xFF7A1B14),
            background = Color(0xFFF6F7F9),
            onBackground = Color(0xFF141414),
            surface = Color(0xFFF6F7F9),
            onSurface = Color(0xFF141414),
            surfaceVariant = Color(0xFFF1F2F4),
            onSurfaceVariant = Color(0xFF6B7075),
            outline = Color(0xFF7C828B),
            outlineVariant = Color(0xFFE4E6EA),
            inverseSurface = Color(0xFF2A2E35),
            inverseOnSurface = Color(0xFFF1F2F4),
            inversePrimary = mix(accent.color, Color.White, accent.darkLift),
            surfaceTint = accent.color,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFFAFAFB),
            surfaceContainer = Color.White,
            surfaceContainerHigh = Color.White,
            surfaceContainerHighest = Color.White,
        )

/** Accent-tinted fill and outline for selected chips and the user's side of a conversation. */
@Immutable
data class SelectionColors(val container: Color, val line: Color)

fun selectionColors(dark: Boolean, accent: Accent = ACCENTS.first()) =
    if (dark)
        SelectionColors(mix(accent.color, DarkBase, 0.78f), mix(accent.color, DarkBase, 0.54f))
    else SelectionColors(accent.selected, accent.selectedLine)

val LocalSelectionColors = staticCompositionLocalOf { selectionColors(false) }

val MaterialTheme.selection: SelectionColors
    @Composable @ReadOnlyComposable get() = LocalSelectionColors.current

/** Answer-state colors that Material's scheme has no slot for. */
@Immutable
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

fun statusColors(dark: Boolean) =
    if (dark)
        StatusColors(
            success = Color(0xFF7DDA9F),
            onSuccess = Color(0xFF00391C),
            successContainer = Color(0xFF0F4D2C),
            onSuccessContainer = Color(0xFFB7F1C8),
            warningContainer = Color(0xFF4D3800),
            onWarningContainer = Color(0xFFFFDF99),
        )
    else
        StatusColors(
            success = Color(0xFF1B7A47),
            onSuccess = Color.White,
            successContainer = Color(0xFFE1F4E8),
            onSuccessContainer = Color(0xFF0E4A2B),
            warningContainer = Color(0xFFFFF0D1),
            onWarningContainer = Color(0xFF7A4E00),
        )

val LocalStatusColors = staticCompositionLocalOf { statusColors(false) }

val MaterialTheme.status: StatusColors
    @Composable @ReadOnlyComposable get() = LocalStatusColors.current

/**
 * The 更多 page's grouped list: [page] sits a step darker than the app background so the 8dp gaps
 * between white groups read; the row icons keep one hue each in both modes.
 */
@Immutable
data class MoreColors(
    val page: Color,
    val dot: Color,
    val review: Color,
    val tag: Color,
    val verify: Color,
    val stats: Color,
    val cost: Color,
    val settings: Color,
)

fun moreColors(dark: Boolean) =
    MoreColors(
        page = if (dark) Color(0xFF0F1115) else Color(0xFFEEEFF3),
        dot = Color(0xFFF04848),
        review = Color(0xFF1E9E5A),
        tag = Color(0xFFE0850B),
        verify = Color(0xFF5363D6),
        stats = Color(0xFF1F86DB),
        cost = Color(0xFFE04A4A),
        settings = Color(0xFF6B7280),
    )

val LocalMoreColors = staticCompositionLocalOf { moreColors(false) }

val MaterialTheme.more: MoreColors
    @Composable @ReadOnlyComposable get() = LocalMoreColors.current

private val AppTypography =
    Typography().let {
        it.copy(
            headlineSmall = it.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
            titleLarge = it.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
            titleMedium = it.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
            titleSmall = it.titleSmall.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = it.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp),
            bodyMedium = it.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = it.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = it.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            labelMedium = it.labelMedium.copy(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        )
    }

private val AppShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        // Cards share one 16dp radius across every screen.
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

@Composable
fun AppTheme(
    settings: LearningSettings,
    accentId: String = ACCENTS.first().id,
    content: @Composable () -> Unit,
) {
    val dark =
        when (settings.theme) {
            "DARK" -> true
            "LIGHT" -> false
            else -> isSystemInDarkTheme()
        }
    val accent = accentOf(accentId)
    val colors = appColors(dark, accent)
    val density = LocalDensity.current
    val view = LocalView.current
    SideEffect {
        view.context.activity()?.let { activity ->
            activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
            activity.window.navigationBarColor =
                if (Build.VERSION.SDK_INT >= 29) android.graphics.Color.TRANSPARENT
                else if (dark) 0xFF0F1115.toInt() else 0xFFF6F7F9.toInt()
            if (Build.VERSION.SDK_INT >= 29) activity.window.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val scale = FontChoice.entries.firstOrNull { it.name == settings.fontSize }?.scale ?: 1f
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * scale),
        LocalStatusColors provides statusColors(dark),
        LocalSelectionColors provides selectionColors(dark, accent),
        LocalMoreColors provides moreColors(dark),
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
