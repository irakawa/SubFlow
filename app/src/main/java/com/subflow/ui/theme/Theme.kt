package com.subflow.ui.theme

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subflow.R

// every palette tints its surfaces toward the accent, never flat gray
data class SubFlowPalette(
    val id: String,
    val displayName: String,     // gem name, shown in Cinzel
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val error: Color,
    val success: Color
)

object Palettes {

    val Aurum = SubFlowPalette(
        id = "aurum", displayName = "AURUM",
        background = Color(0xFF0A0A0A),
        surface = Color(0xFF141414),
        surfaceAlt = Color(0xFF1C1C1C),
        border = Color(0xFF2A2A2A),
        textPrimary = Color(0xFFF2F2F2),
        textSecondary = Color(0xFF888888),
        accent = Color(0xFFC8A96E),
        error = Color(0xFFE05252),
        success = Color(0xFF52C97A)
    )

    val Amethyst = SubFlowPalette(
        id = "amethyst", displayName = "AMETHYST",
        background = Color(0xFF0B0913),
        surface = Color(0xFF151022),
        surfaceAlt = Color(0xFF1D1730),
        border = Color(0xFF2E2547),
        textPrimary = Color(0xFFF1EEFA),
        textSecondary = Color(0xFF9188AD),
        accent = Color(0xFFA78BFA),
        error = Color(0xFFF87171),
        success = Color(0xFF6EE7B7)
    )

    val Saffron = SubFlowPalette(
        id = "saffron", displayName = "SAFFRON",
        background = Color(0xFF0C0A05),
        surface = Color(0xFF16130A),
        surfaceAlt = Color(0xFF1F1B10),
        border = Color(0xFF332C18),
        textPrimary = Color(0xFFF5F2E9),
        textSecondary = Color(0xFF948D77),
        accent = Color(0xFFF2C14E),
        error = Color(0xFFE85D5D),
        success = Color(0xFF86D96C)
    )

    // error is orange here so it stays distinct from the red accent
    val Crimson = SubFlowPalette(
        id = "crimson", displayName = "CRIMSON",
        background = Color(0xFF0E0709),
        surface = Color(0xFF180D11),
        surfaceAlt = Color(0xFF221319),
        border = Color(0xFF3A1F28),
        textPrimary = Color(0xFFF5EDEF),
        textSecondary = Color(0xFFA08890),
        accent = Color(0xFFE24E63),
        error = Color(0xFFFF9E5E),
        success = Color(0xFF58C98F)
    )

    val Abyss = SubFlowPalette(
        id = "abyss", displayName = "ABYSS",
        background = Color(0xFF060B10),
        surface = Color(0xFF0D141C),
        surfaceAlt = Color(0xFF131E28),
        border = Color(0xFF1F3140),
        textPrimary = Color(0xFFEAF2F7),
        textSecondary = Color(0xFF7C93A3),
        accent = Color(0xFF4CC9F0),
        error = Color(0xFFF0605E),
        success = Color(0xFF57D9A3)
    )

    val Verdant = SubFlowPalette(
        id = "verdant", displayName = "VERDANT",
        background = Color(0xFF070C08),
        surface = Color(0xFF0F1711),
        surfaceAlt = Color(0xFF16221A),
        border = Color(0xFF24382B),
        textPrimary = Color(0xFFEDF5EF),
        textSecondary = Color(0xFF85A08C),
        accent = Color(0xFF58D68D),
        error = Color(0xFFE36262),
        success = Color(0xFFA3E635)
    )

    val Sakura = SubFlowPalette(
        id = "sakura", displayName = "SAKURA",
        background = Color(0xFF100A0E),
        surface = Color(0xFF1A1218),
        surfaceAlt = Color(0xFF241922),
        border = Color(0xFF3B2833),
        textPrimary = Color(0xFFF7EEF3),
        textSecondary = Color(0xFFA48E9A),
        accent = Color(0xFFF286B0),
        error = Color(0xFFFF8A6B),
        success = Color(0xFF7BD88F)
    )

    val all: List<SubFlowPalette> = listOf(Aurum, Amethyst, Saffron, Crimson, Abyss, Verdant, Sakura)

    fun byId(id: String): SubFlowPalette = all.firstOrNull { it.id == id } ?: Aurum
}

// active palette in Compose state. reading SubFlowColors.X recomposes on theme change.
object SubFlowColors {
    var palette by mutableStateOf(Palettes.Aurum)
        private set

    val Background: Color get() = palette.background
    val Surface: Color get() = palette.surface
    val SurfaceAlt: Color get() = palette.surfaceAlt
    val Border: Color get() = palette.border
    val TextPrimary: Color get() = palette.textPrimary
    val TextSecondary: Color get() = palette.textSecondary
    val Accent: Color get() = palette.accent
    val Error: Color get() = palette.error
    val Success: Color get() = palette.success

    private const val PREFS = "subflow_prefs"
    private const val KEY = "theme_id"

    fun load(context: Context) {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, Palettes.Aurum.id) ?: Palettes.Aurum.id
        palette = Palettes.byId(id)
    }

    fun apply(context: Context, newPalette: SubFlowPalette) {
        palette = newPalette
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, newPalette.id).apply()
    }
}

// cinzel for display, inter for UI, jetbrains mono for logs
val CinzelFamily = FontFamily(
    Font(R.font.cinzel, weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.cinzel, weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

val InterFamily = FontFamily(
    Font(R.font.inter, weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter, weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter, weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter, weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono, weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)))
)

// cards 12dp radius, buttons pill
private val subFlowShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(50)
)

@Composable
fun SubFlowTheme(content: @Composable () -> Unit) {
    val p = SubFlowColors.palette

    val colorScheme = remember(p) {
        darkColorScheme(
            primary = p.accent,
            onPrimary = p.background,
            secondary = p.accent,
            onSecondary = p.background,
            background = p.background,
            onBackground = p.textPrimary,
            surface = p.surface,
            onSurface = p.textPrimary,
            surfaceVariant = p.surfaceAlt,
            onSurfaceVariant = p.textSecondary,
            outline = p.border,
            error = p.error,
            onError = p.background
        )
    }

    val typography = remember(p) {
        Typography(
            displayLarge = TextStyle(
                fontFamily = CinzelFamily, fontWeight = FontWeight.Bold,
                fontSize = 40.sp, letterSpacing = 4.sp, color = p.textPrimary
            ),
            displayMedium = TextStyle(
                fontFamily = CinzelFamily, fontWeight = FontWeight.Bold,
                fontSize = 28.sp, letterSpacing = 3.sp, color = p.textPrimary
            ),
            headlineMedium = TextStyle(
                fontFamily = InterFamily, fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp, color = p.textPrimary
            ),
            titleMedium = TextStyle(
                fontFamily = InterFamily, fontWeight = FontWeight.Medium,
                fontSize = 16.sp, color = p.textPrimary
            ),
            bodyLarge = TextStyle(
                fontFamily = InterFamily, fontWeight = FontWeight.Normal,
                fontSize = 15.sp, color = p.textPrimary
            ),
            bodyMedium = TextStyle(
                fontFamily = InterFamily, fontWeight = FontWeight.Normal,
                fontSize = 14.sp, color = p.textPrimary
            ),
            bodySmall = TextStyle(
                fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Normal,
                fontSize = 12.sp, color = p.textSecondary
            ),
            labelLarge = TextStyle(
                fontFamily = InterFamily, fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp, letterSpacing = 0.5.sp
            ),
            labelSmall = TextStyle(
                fontFamily = InterFamily, fontWeight = FontWeight.Medium,
                fontSize = 11.sp, color = p.textSecondary
            )
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = subFlowShapes,
        content = content
    )
}
