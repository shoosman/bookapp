package com.shoosman.bookapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.shoosman.bookapp.data.AppSettings

/** Акцентный цвет: для светлых и тёмных тем — свои оттенки, чтобы везде хватало контраста. */
class Accent(
    val key: String,
    val title: String,
    val light: Color, val lightContainer: Color, val lightOnContainer: Color,
    val dark: Color, val darkOn: Color, val darkContainer: Color, val darkOnContainer: Color,
)

val accents = listOf(
    Accent("pink", "Розовый", Color(0xFFC4266F), Color(0xFFFFD9E7), Color(0xFF3E0020), Color(0xFFFF8CBF), Color(0xFF5E0032), Color(0xFF7D1C4C), Color(0xFFFFD9E7)),
    Accent("raspberry", "Малиновый", Color(0xFFB0124D), Color(0xFFFFD9DF), Color(0xFF400014), Color(0xFFFFB1C0), Color(0xFF66002A), Color(0xFF8E1D44), Color(0xFFFFD9DF)),
    Accent("lilac", "Сиреневый", Color(0xFF7A4FC9), Color(0xFFEBDDFF), Color(0xFF25005A), Color(0xFFD3BBFF), Color(0xFF3F1D8A), Color(0xFF5A35A8), Color(0xFFEBDDFF)),
    Accent("lavender", "Лавандовый", Color(0xFF5B5BD6), Color(0xFFE1E0FF), Color(0xFF0F0B6B), Color(0xFFC0C1FF), Color(0xFF1F1F94), Color(0xFF3E3EB8), Color(0xFFE1E0FF)),
    Accent("ocean", "Морской", Color(0xFF0B6496), Color(0xFFCDE5FF), Color(0xFF001D32), Color(0xFF93CCFF), Color(0xFF003352), Color(0xFF004B74), Color(0xFFCDE5FF)),
    Accent("mint", "Мятный", Color(0xFF2E6B5E), Color(0xFFB7EBDC), Color(0xFF00201A), Color(0xFF8FD3C1), Color(0xFF00382E), Color(0xFF1F5145), Color(0xFFB7EBDC)),
    Accent("sunset", "Закатный", Color(0xFFB3471F), Color(0xFFFFDBCF), Color(0xFF3A0B00), Color(0xFFFFB59B), Color(0xFF5E1700), Color(0xFF84300F), Color(0xFFFFDBCF)),
    Accent("lemon", "Лимонный", Color(0xFF6B5F00), Color(0xFFF7E36A), Color(0xFF201C00), Color(0xFFDAC74F), Color(0xFF383000), Color(0xFF514700), Color(0xFFF7E36A)),
)

fun accentOf(key: String) = accents.firstOrNull { it.key == key } ?: accents.first()

private fun lightScheme(a: Accent) = lightColorScheme(
    primary = a.light, onPrimary = Color.White,
    primaryContainer = a.lightContainer, onPrimaryContainer = a.lightOnContainer,
    secondary = a.light, secondaryContainer = a.lightContainer, onSecondaryContainer = a.lightOnContainer,
    tertiary = a.light,
    background = Color(0xFFFFFBFA), surface = Color(0xFFFFFBFA),
    onBackground = Color(0xFF201A1C), onSurface = Color(0xFF201A1C),
    surfaceVariant = Color(0xFFF2E6E9), onSurfaceVariant = Color(0xFF524448),
    outline = Color(0xFF857377), outlineVariant = Color(0xFFD7C2C6),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFCF3F5),
    surfaceContainer = Color(0xFFF7EDF0), surfaceContainerHigh = Color(0xFFF2E7EA), surfaceContainerHighest = Color(0xFFECE1E4),
)

private fun sepiaScheme(a: Accent) = lightColorScheme(
    primary = a.light, onPrimary = Color.White,
    primaryContainer = a.lightContainer, onPrimaryContainer = a.lightOnContainer,
    secondary = Color(0xFF6E5B45), secondaryContainer = Color(0xFFE7D7BC),
    background = Color(0xFFF4ECD8), surface = Color(0xFFF4ECD8),
    surfaceVariant = Color(0xFFE6DAC0), onSurfaceVariant = Color(0xFF6B5A47),
    surfaceContainerLowest = Color(0xFFF8F1E1), surfaceContainerLow = Color(0xFFF1E7D1),
    surfaceContainer = Color(0xFFEDE2CA), surfaceContainerHigh = Color(0xFFE9DDC4), surfaceContainerHighest = Color(0xFFE4D7BC),
    onBackground = Color(0xFF4A3A2A), onSurface = Color(0xFF4A3A2A),
    outline = Color(0xFF8E7D68), outlineVariant = Color(0xFFD2C3A6),
)

private fun darkScheme(a: Accent, black: Boolean) = darkColorScheme(
    primary = a.dark, onPrimary = a.darkOn,
    primaryContainer = a.darkContainer, onPrimaryContainer = a.darkOnContainer,
    secondary = a.dark, onSecondary = a.darkOn, secondaryContainer = a.darkContainer, onSecondaryContainer = a.darkOnContainer,
    tertiary = a.dark,
    background = if (black) Color.Black else Color(0xFF141213),
    surface = if (black) Color.Black else Color(0xFF141213),
    onBackground = Color(0xFFEDE0E3), onSurface = Color(0xFFEDE0E3),
    surfaceVariant = if (black) Color(0xFF1C1A1B) else Color(0xFF2D292A), onSurfaceVariant = Color(0xFFD2C2C6),
    outline = Color(0xFF9B8C90), outlineVariant = Color(0xFF4A4245),
    surfaceContainerLowest = if (black) Color.Black else Color(0xFF0F0D0E),
    surfaceContainerLow = if (black) Color(0xFF0B0A0A) else Color(0xFF1C1A1B),
    surfaceContainer = if (black) Color(0xFF121011) else Color(0xFF211E1F),
    surfaceContainerHigh = if (black) Color(0xFF1A1718) else Color(0xFF2B2829),
    surfaceContainerHighest = if (black) Color(0xFF232021) else Color(0xFF363233),
)

private fun Typography.withFamily(f: FontFamily): Typography {
    fun TextStyle.w() = copy(fontFamily = f)
    return copy(
        displayLarge = displayLarge.w(), displayMedium = displayMedium.w(), displaySmall = displaySmall.w(),
        headlineLarge = headlineLarge.w(), headlineMedium = headlineMedium.w(), headlineSmall = headlineSmall.w(),
        titleLarge = titleLarge.w(), titleMedium = titleMedium.w(), titleSmall = titleSmall.w(),
        bodyLarge = bodyLarge.w(), bodyMedium = bodyMedium.w(), bodySmall = bodySmall.w(),
        labelLarge = labelLarge.w(), labelMedium = labelMedium.w(), labelSmall = labelSmall.w(),
    )
}

fun fontOf(key: String): FontFamily = when (key) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> FontFamily.Default
}

private fun shapesOf(key: String) = when (key) {
    "sharp" -> Shapes(RoundedCornerShape(2.dp), RoundedCornerShape(4.dp), RoundedCornerShape(6.dp), RoundedCornerShape(8.dp), RoundedCornerShape(12.dp))
    "normal" -> Shapes()
    else -> Shapes(RoundedCornerShape(8.dp), RoundedCornerShape(14.dp), RoundedCornerShape(20.dp), RoundedCornerShape(28.dp), RoundedCornerShape(36.dp))
}

/** Цветовая схема по настройкам — нужна и вне темы (например, для превью в настройках). */
@Composable
fun schemeFor(theme: String, accent: String): ColorScheme {
    val a = accentOf(accent)
    return when (theme) {
        "light" -> lightScheme(a)
        "sepia" -> sepiaScheme(a)
        "dark" -> darkScheme(a, black = false)
        "black" -> darkScheme(a, black = true)
        else -> if (isSystemInDarkTheme()) darkScheme(a, black = false) else lightScheme(a)
    }
}

@Composable
fun AppTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val scheme = schemeFor(settings.theme, settings.accent)
    val density = LocalDensity.current
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography().withFamily(fontOf(settings.uiFont)),
        shapes = shapesOf(settings.corners),
    ) {
        // Цвет текста и иконок по умолчанию — под фон темы (иначе экраны без Scaffold рисуют чёрным).
        // Масштаб интерфейса — через плотность: крупнее становится всё, не только текст.
        CompositionLocalProvider(
            LocalContentColor provides scheme.onBackground,
            LocalDensity provides Density(density.density * settings.uiScale, density.fontScale),
            content = content,
        )
    }
}

/** Старый вызов — только тема. */
@Composable
fun AppTheme(theme: String, content: @Composable () -> Unit) = AppTheme(AppSettings(theme = theme), content)

fun isDarkTheme(theme: String, systemDark: Boolean) = theme == "dark" || theme == "black" || (theme == "system" && systemDark)
