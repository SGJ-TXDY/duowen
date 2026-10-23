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

fun appColors(dark: Boolean) =
    if (dark)
        darkColorScheme(
            primary = Color(0xFF7FD6C7),
            onPrimary = Color(0xFF00382F),
            primaryContainer = Color(0xFF00504A),
            onPrimaryContainer = Color(0xFF9EF2E2),
            secondary = Color(0xFFB1CCC6),
            onSecondary = Color(0xFF1C3531),
            secondaryContainer = Color(0xFF334B47),
            onSecondaryContainer = Color(0xFFCCE8E2),
            tertiary = Color(0xFFF1C048),
            onTertiary = Color(0xFF3F2E00),
            tertiaryContainer = Color(0xFF5B4300),
            onTertiaryContainer = Color(0xFFFFDF99),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            background = Color(0xFF0F1514),
            onBackground = Color(0xFFDDE4E2),
            surface = Color(0xFF0F1514),
            onSurface = Color(0xFFDDE4E2),
            surfaceVariant = Color(0xFF3F4946),
            onSurfaceVariant = Color(0xFFBEC9C5),
            outline = Color(0xFF899390),
            outlineVariant = Color(0xFF3F4946),
            inverseSurface = Color(0xFFDDE4E2),
            inverseOnSurface = Color(0xFF2B3130),
            inversePrimary = Color(0xFF0F6B60),
            surfaceTint = Color(0xFF7FD6C7),
            surfaceContainerLowest = Color(0xFF1B2120),
            surfaceContainerLow = Color(0xFF171D1C),
            surfaceContainer = Color(0xFF1B2120),
            surfaceContainerHigh = Color(0xFF252B2A),
            surfaceContainerHighest = Color(0xFF2A3231),
        )
    else
        lightColorScheme(
            primary = Color(0xFF0F6B60),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDDEFEB),
            onPrimaryContainer = Color(0xFF00201C),
            secondary = Color(0xFF4A635F),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFCCE8E2),
            onSecondaryContainer = Color(0xFF05201C),
            tertiary = Color(0xFF7A5900),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFFFE9B0),
            onTertiaryContainer = Color(0xFF261A00),
            error = Color(0xFFB42318),
            onError = Color.White,
            errorContainer = Color(0xFFFDE8E6),
            onErrorContainer = Color(0xFF7E1B12),
            background = Color(0xFFF3F6F5),
            onBackground = Color(0xFF14201E),
            surface = Color(0xFFF3F6F5),
            onSurface = Color(0xFF14201E),
            surfaceVariant = Color(0xFFE6ECEA),
            onSurfaceVariant = Color(0xFF56635F),
            outline = Color(0xFF7D8A86),
            outlineVariant = Color(0xFFDCE3E1),
            inverseSurface = Color(0xFF29322F),
            inverseOnSurface = Color(0xFFEEF2F1),
            inversePrimary = Color(0xFF80D5C5),
            surfaceTint = Color(0xFF0F6B60),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFFAFCFB),
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
                else if (dark) 0xFF0F1514.toInt() else 0xFFF3F6F5.toInt()
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
