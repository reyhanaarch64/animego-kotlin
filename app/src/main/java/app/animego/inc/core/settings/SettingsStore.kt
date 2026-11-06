package app.animego.inc.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "animego_settings")

class SettingsStore(private val context: Context) {
    private val darkKey = booleanPreferencesKey("dark_theme")
    private val dynamicKey = booleanPreferencesKey("dynamic_color")

    val darkTheme: Flow<Boolean> = context.settingsDataStore.data.map { it[darkKey] ?: true }
    val dynamicColor: Flow<Boolean> = context.settingsDataStore.data.map { it[dynamicKey] ?: false }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.settingsDataStore.edit { it[darkKey] = enabled }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[dynamicKey] = enabled }
    }
}
