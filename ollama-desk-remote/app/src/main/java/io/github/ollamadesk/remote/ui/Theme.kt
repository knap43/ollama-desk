package io.github.ollamadesk.remote.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The accent, and the Material 3 roles built around it. */
val Amber = Color(0xFFE1A34F)

/** Status colours tuned for the dark palette (all ≥ 4.5:1 on the surfaces). */
object StatusColors {
    val ok = Color(0xFF8FD18A)
    val warn = Color(0xFFF0C46A)
    val offline = Color(0xFFFFB4AB)
}

private val Scheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF3F2800),
    primaryContainer = Color(0xFF5C3F0A),
    onPrimaryContainer = Color(0xFFFFDDB3),
    inversePrimary = Color(0xFF7F5713),

    secondary = Color(0xFFDDC2A1),
    onSecondary = Color(0xFF3E2D16),
    secondaryContainer = Color(0xFF4A3A26),
    onSecondaryContainer = Color(0xFFFADEBC),

    tertiary = Color(0xFFB8CEA1),
    onTertiary = Color(0xFF243515),
    tertiaryContainer = Color(0xFF3A4C2A),
    onTertiaryContainer = Color(0xFFD4EABB),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF16130F),
    onBackground = Color(0xFFEBE1D6),
    surface = Color(0xFF16130F),
    onSurface = Color(0xFFEBE1D6),
    surfaceVariant = Color(0xFF4F4539),
    onSurfaceVariant = Color(0xFFD3C4B4),
    surfaceTint = Amber,
    inverseSurface = Color(0xFFEBE1D6),
    inverseOnSurface = Color(0xFF352F29),
    outline = Color(0xFF9C8F80),
    outlineVariant = Color(0xFF4F4539),
    scrim = Color(0xFF000000),

    surfaceBright = Color(0xFF3D3833),
    surfaceDim = Color(0xFF16130F),
    surfaceContainerLowest = Color(0xFF110E0A),
    surfaceContainerLow = Color(0xFF1F1B16),
    surfaceContainer = Color(0xFF231F1A),
    surfaceContainerHigh = Color(0xFF2E2924),
    surfaceContainerHighest = Color(0xFF39342E),
)

private val Base = Typography()

/** Material's type scale, with slightly firmer titles so headings read well on dark surfaces. */
private val Type = Base.copy(
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(lineHeight = 24.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

private val Shape = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun RemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, shapes = Shape, content = content)
}
