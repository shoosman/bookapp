package com.shoosman.bookapp.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val fontSize: Float = 19f,
    val lineHeight: Float = 1.5f,
    /** system, light, sepia, dark */
    val theme: String = "system",
    val serif: Boolean = true,
    val justify: Boolean = true,
    val skipBackSec: Int = 15,
    val skipForwardSec: Int = 30,
    val keepScreenOn: Boolean = true,
    /** true — листание страниц, false — прокрутка */
    val pageMode: Boolean = true,
    /** Яркость в читалке 0..1, -1 — как в системе */
    val brightness: Float = -1f,
    // --- внешний вид ---
    /** Акцентный цвет: pink, raspberry, lilac, lavender, mint, ocean, sunset, lemon */
    val accent: String = "pink",
    /** Шрифт интерфейса: system, serif, mono, cursive */
    val uiFont: String = "system",
    /** Скругления: sharp, normal, round */
    val corners: String = "round",
    /** Масштаб интерфейса (текст и элементы): 0.9 … 1.2 */
    val uiScale: Float = 1f,
    /** Фон плеера: plain или cover (цвета обложки) */
    val playerBg: String = "cover",
    /** Размер обложек на главной: small, normal, large */
    val tileSize: String = "normal",
    /** Конфетти, когда книга отмечена прочитанной */
    val confetti: Boolean = true,
    /** Иконка приложения */
    val appIcon: String = "headphones_book",
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val ctx: Context) {
    private object K {
        val fontSize = floatPreferencesKey("fontSize")
        val lineHeight = floatPreferencesKey("lineHeight")
        val theme = stringPreferencesKey("theme")
        val serif = booleanPreferencesKey("serif")
        val justify = booleanPreferencesKey("justify")
        val skipBack = intPreferencesKey("skipBack")
        val skipForward = intPreferencesKey("skipForward")
        val keepScreenOn = booleanPreferencesKey("keepScreenOn")
        val pageMode = booleanPreferencesKey("pageMode")
        val brightness = floatPreferencesKey("brightness")
        val accent = stringPreferencesKey("accent")
        val uiFont = stringPreferencesKey("uiFont")
        val corners = stringPreferencesKey("corners")
        val uiScale = floatPreferencesKey("uiScale")
        val playerBg = stringPreferencesKey("playerBg")
        val tileSize = stringPreferencesKey("tileSize")
        val confetti = booleanPreferencesKey("confetti")
        val appIcon = stringPreferencesKey("appIcon")
    }

    val flow: Flow<AppSettings> = ctx.dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        ctx.dataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[K.fontSize] = s.fontSize
            prefs[K.lineHeight] = s.lineHeight
            prefs[K.theme] = s.theme
            prefs[K.serif] = s.serif
            prefs[K.justify] = s.justify
            prefs[K.skipBack] = s.skipBackSec
            prefs[K.skipForward] = s.skipForwardSec
            prefs[K.keepScreenOn] = s.keepScreenOn
            prefs[K.pageMode] = s.pageMode
            prefs[K.brightness] = s.brightness
            prefs[K.accent] = s.accent
            prefs[K.uiFont] = s.uiFont
            prefs[K.corners] = s.corners
            prefs[K.uiScale] = s.uiScale
            prefs[K.playerBg] = s.playerBg
            prefs[K.tileSize] = s.tileSize
            prefs[K.confetti] = s.confetti
            prefs[K.appIcon] = s.appIcon
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            fontSize = this[K.fontSize] ?: d.fontSize,
            lineHeight = this[K.lineHeight] ?: d.lineHeight,
            theme = this[K.theme] ?: d.theme,
            serif = this[K.serif] ?: d.serif,
            justify = this[K.justify] ?: d.justify,
            skipBackSec = this[K.skipBack] ?: d.skipBackSec,
            skipForwardSec = this[K.skipForward] ?: d.skipForwardSec,
            keepScreenOn = this[K.keepScreenOn] ?: d.keepScreenOn,
            pageMode = this[K.pageMode] ?: d.pageMode,
            brightness = this[K.brightness] ?: d.brightness,
            accent = this[K.accent] ?: d.accent,
            uiFont = this[K.uiFont] ?: d.uiFont,
            corners = this[K.corners] ?: d.corners,
            uiScale = this[K.uiScale] ?: d.uiScale,
            playerBg = this[K.playerBg] ?: d.playerBg,
            tileSize = this[K.tileSize] ?: d.tileSize,
            confetti = this[K.confetti] ?: d.confetti,
            appIcon = this[K.appIcon] ?: d.appIcon,
        )
    }
}
