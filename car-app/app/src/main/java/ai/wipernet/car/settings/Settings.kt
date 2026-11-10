package ai.wipernet.car.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Mode { LIVE, DEMO }

data class Settings(
    val consented: Boolean = false,
    val mode: Mode = Mode.DEMO,
    val serverUrl: String = DEFAULT_SERVER_URL,
    val prepCloseWindows: Boolean = true,
    val prepDefog: Boolean = true,
    val prepHeadlights: Boolean = true,
) {
    companion object {
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8000"
    }
}

interface SettingsStore {
    val settings: StateFlow<Settings>
    suspend fun update(f: (Settings) -> Settings)
}

class DataStoreSettingsStore private constructor(
    private val dataStore: DataStore<Preferences>,
    initial: Settings,
    private val onConsentRevoked: () -> Unit,
) : SettingsStore {
    private val state = MutableStateFlow(initial)
    private val lock = Mutex()
    override val settings: StateFlow<Settings> = state.asStateFlow()

    // NonCancellable: DataStore finishes a write even if the caller is cancelled,
    // so memory and the revoke callback must follow it or they drift from disk.
    override suspend fun update(f: (Settings) -> Settings) = withContext(NonCancellable) {
        val (before, after) = lock.withLock {
            val before = state.value
            val saved = dataStore.edit { it.write(f(before)) }.toSettings()
            state.value = saved
            before to saved
        }
        if (before.consented && !after.consented) onConsentRevoked()
    }

    companion object {
        suspend fun open(dataStore: DataStore<Preferences>, onConsentRevoked: () -> Unit): DataStoreSettingsStore =
            DataStoreSettingsStore(dataStore, dataStore.data.first().toSettings(), onConsentRevoked)
    }
}

private object Keys {
    val consented = booleanPreferencesKey("consented")
    val mode = stringPreferencesKey("mode")
    val serverUrl = stringPreferencesKey("server_url")
    val prepCloseWindows = booleanPreferencesKey("prep_close_windows")
    val prepDefog = booleanPreferencesKey("prep_defog")
    val prepHeadlights = booleanPreferencesKey("prep_headlights")
}

private fun Preferences.toSettings(): Settings {
    val d = Settings()
    return Settings(
        consented = this[Keys.consented] ?: d.consented,
        mode = Mode.entries.firstOrNull { it.name == this[Keys.mode] } ?: d.mode,
        serverUrl = this[Keys.serverUrl] ?: d.serverUrl,
        prepCloseWindows = this[Keys.prepCloseWindows] ?: d.prepCloseWindows,
        prepDefog = this[Keys.prepDefog] ?: d.prepDefog,
        prepHeadlights = this[Keys.prepHeadlights] ?: d.prepHeadlights,
    )
}

private fun MutablePreferences.write(s: Settings) {
    this[Keys.consented] = s.consented
    this[Keys.mode] = s.mode.name
    this[Keys.serverUrl] = s.serverUrl
    this[Keys.prepCloseWindows] = s.prepCloseWindows
    this[Keys.prepDefog] = s.prepDefog
    this[Keys.prepHeadlights] = s.prepHeadlights
}
