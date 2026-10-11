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
        )
    }
}
