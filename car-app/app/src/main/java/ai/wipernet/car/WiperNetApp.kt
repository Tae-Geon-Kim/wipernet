package ai.wipernet.car

import ai.wipernet.car.settings.DataStoreSettingsStore
import ai.wipernet.car.settings.SettingsStore
import android.app.Application
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class WiperNetApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settingsStore: Deferred<SettingsStore>
        private set

    override fun onCreate() {
        super.onCreate()
        val dataStore = PreferenceDataStoreFactory.create { preferencesDataStoreFile("settings") }
        settingsStore = scope.async { DataStoreSettingsStore.open(dataStore) { Log.i("WiperNet", "consent revoked") } }
    }
}
