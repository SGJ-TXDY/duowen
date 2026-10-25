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

/** Indigo primary with warm amber accents; answer states keep their own green / amber / red. */
fun appColors(dark: Boolean) =
    if (dark)
        darkColorScheme(
            primary = Color(0xFFBAC3FF),
            onPrimary = Color(0xFF08218A),
            primaryContainer = Color(0xFF2B3DA0),
            onPrimaryContainer = Color(0xFFDEE0FF),
            secondary = Color(0xFFC3C5DD),
            onSecondary = Color(0xFF2D2F42),
            secondaryContainer = Color(0xFF434659),
            onSecondaryContainer = Color(0xFFE0E1F9),
            tertiary = Color(0xFFFFB870),
            onTertiary = Color(0xFF4A2800),
            tertiaryContainer = Color(0xFF5E3A00),
            onTertiaryContainer = Color(0xFFFFDCBE),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            background = Color(0xFF121318),
            onBackground = Color(0xFFE3E3EC),
            surface = Color(0xFF121318),
            onSurface = Color(0xFFE3E3EC),
            surfaceVariant = Color(0xFF45464F),
            onSurfaceVariant = Color(0xFFC6C6D2),
            outline = Color(0xFF90909C),
            outlineVariant = Color(0xFF3B3C46),
            inverseSurface = Color(0xFFE3E3EC),
            inverseOnSurface = Color(0xFF2F3036),
            inversePrimary = Color(0xFF4355B9),
            surfaceTint = Color(0xFFBAC3FF),
            surfaceContainerLowest = Color(0xFF1C1D24),
            surfaceContainerLow = Color(0xFF18191F),
            surfaceContainer = Color(0xFF1C1D24),
            surfaceContainerHigh = Color(0xFF26272F),
            surfaceContainerHighest = Color(0xFF2D2E36),
        )
    else
        lightColorScheme(
            primary = Color(0xFF4355B9),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE0E3FF),
            onPrimaryContainer = Color(0xFF00105C),
            secondary = Color(0xFF5A5D72),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE0E1F9),
            onSecondaryContainer = Color(0xFF181A2C),
            tertiary = Color(0xFF8B5000),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFFFE6C9),
            onTertiaryContainer = Color(0xFF2D1600),
            error = Color(0xFFB42318),
            onError = Color.White,
            errorContainer = Color(0xFFFDE8E6),
            onErrorContainer = Color(0xFF7E1B12),
            background = Color(0xFFF4F5FA),
            onBackground = Color(0xFF1A1B23),
            surface = Color(0xFFF4F5FA),
            onSurface = Color(0xFF1A1B23),
            surfaceVariant = Color(0xFFE6E7F2),
            onSurfaceVariant = Color(0xFF565A6E),
            outline = Color(0xFF7A7D8E),
            outlineVariant = Color(0xFFDDDFEA),
            inverseSurface = Color(0xFF2F3038),
            inverseOnSurface = Color(0xFFF1F0F7),
            inversePrimary = Color(0xFFBAC3FF),
            surfaceTint = Color(0xFF4355B9),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFFAFAFE),
            surfaceContainer = Color.White,
            surfaceContainerHigh = Color.White,
            surfaceContainerHighest = Color.White,
        )

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

private val AppTypography =
    Typography().let {
        it.copy(
            headlineSmall = it.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
            titleLarge = it.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
            titleMedium = it.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
            titleSmall = it.titleSmall.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
            bodyLarge = it.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp),
            bodyMedium = it.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp),
            labelLarge = it.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        )
    }

private val AppShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

@Composable
fun AppTheme(settings: LearningSettings, content: @Composable () -> Unit) {
    val dark =
        when (settings.theme) {
            "DARK" -> true
            "LIGHT" -> false
            else -> isSystemInDarkTheme()
        }
    val colors = appColors(dark)
    val density = LocalDensity.current
    val view = LocalView.current
    SideEffect {
        view.context.activity()?.let { activity ->
            activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
            activity.window.navigationBarColor =
                if (Build.VERSION.SDK_INT >= 29) android.graphics.Color.TRANSPARENT
                else if (dark) 0xFF121318.toInt() else 0xFFF4F5FA.toInt()
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
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
