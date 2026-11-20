package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sonoraDataStore: DataStore<Preferences> by preferencesDataStore(name = "sonora_settings")

data class EqSettingsState(
    val enabled: Boolean = true,
    val autoGain: Boolean = false,
    val bandsDb: List<Float> = List(10) { 0f },
    val preampDb: Float = 0f,
    val activePresetId: Long = -1L,
    val bitPerfectUsbRequested: Boolean = false
)

data class AppUpdatePrefs(
    val githubRepo: String = DEFAULT_GITHUB_REPO,
    val skippedVersion: String = ""
) {
    companion object {
        const val DEFAULT_GITHUB_REPO = "icaro81/AndAmp"

        fun normalizeRepo(raw: String?): String {
            val cleaned = raw.orEmpty()
                .trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removeSuffix(".git")
                .trim('/')
            return if (
                cleaned.isBlank() ||
                cleaned.equals("usuario/AndAmp", ignoreCase = true) ||
                cleaned.equals("tu-usuario/AndAmp", ignoreCase = true) ||
                cleaned.equals("owner/AndAmp", ignoreCase = true)
            ) {
                DEFAULT_GITHUB_REPO
            } else {
                cleaned
            }
        }
    }
}

class PreferencesStore(context: Context) {
    private val dataStore = context.applicationContext.sonoraDataStore

    private object Keys {
        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_AUTO = booleanPreferencesKey("eq_auto")
        val EQ_BANDS = stringPreferencesKey("eq_bands")
        val EQ_PREAMP = floatPreferencesKey("eq_preamp")
        val EQ_PRESET_ID = longPreferencesKey("eq_preset_id")
        val BIT_PERFECT_USB = booleanPreferencesKey("bit_perfect_usb")
        val HAS_PLAYED_DROID_INTRO = booleanPreferencesKey("has_played_droid_intro")
        val GITHUB_UPDATE_REPO = stringPreferencesKey("github_update_repo")
        val SKIPPED_UPDATE_VERSION = stringPreferencesKey("skipped_update_version")
        val CAR_MODE_LANDSCAPE = booleanPreferencesKey("car_mode_landscape")
    }

    val eqSettingsFlow: Flow<EqSettingsState> = dataStore.data.map { prefs ->
        val bands = prefs[Keys.EQ_BANDS]
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 10 }
            ?: List(10) { 0f }
        EqSettingsState(
            enabled = prefs[Keys.EQ_ENABLED] ?: true,
            autoGain = prefs[Keys.EQ_AUTO] ?: false,
            bandsDb = bands,
            preampDb = prefs[Keys.EQ_PREAMP] ?: 0f,
            activePresetId = prefs[Keys.EQ_PRESET_ID] ?: -1L,
            bitPerfectUsbRequested = prefs[Keys.BIT_PERFECT_USB] ?: false
        )
    }

    val hasPlayedDroidIntroFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.HAS_PLAYED_DROID_INTRO] ?: false
    }

    val appUpdatePrefsFlow: Flow<AppUpdatePrefs> = dataStore.data.map { prefs ->
        AppUpdatePrefs(
            githubRepo = AppUpdatePrefs.normalizeRepo(prefs[Keys.GITHUB_UPDATE_REPO]),
            skippedVersion = prefs[Keys.SKIPPED_UPDATE_VERSION].orEmpty()
        )
    }

    val carModeLandscapeFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.CAR_MODE_LANDSCAPE] ?: false
    }

    suspend fun saveEqSettings(state: EqSettingsState) {
        dataStore.edit { prefs ->
            prefs[Keys.EQ_ENABLED] = state.enabled
            prefs[Keys.EQ_AUTO] = state.autoGain
            prefs[Keys.EQ_BANDS] = state.bandsDb.joinToString(",")
            prefs[Keys.EQ_PREAMP] = state.preampDb
            prefs[Keys.EQ_PRESET_ID] = state.activePresetId
            prefs[Keys.BIT_PERFECT_USB] = state.bitPerfectUsbRequested
        }
    }

    suspend fun markDroidIntroPlayed() {
        dataStore.edit { prefs ->
            prefs[Keys.HAS_PLAYED_DROID_INTRO] = true
        }
    }

    suspend fun setGithubUpdateRepo(repo: String) {
        val normalized = AppUpdatePrefs.normalizeRepo(repo)
        dataStore.edit { prefs ->
            prefs[Keys.GITHUB_UPDATE_REPO] = normalized
        }
    }

    suspend fun setSkippedUpdateVersion(version: String) {
        dataStore.edit { prefs ->
            prefs[Keys.SKIPPED_UPDATE_VERSION] = version.trim()
        }
    }

    suspend fun setCarModeLandscape(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.CAR_MODE_LANDSCAPE] = enabled
        }
    }
}
