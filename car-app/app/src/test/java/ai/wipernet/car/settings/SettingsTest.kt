package ai.wipernet.car.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val file by lazy { File(tmp.root, "settings.preferences_pb") }
    private var revoked = 0

    private fun newDataStore(): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    private suspend fun openStore(dataStore: DataStore<Preferences> = newDataStore()): SettingsStore =
        DataStoreSettingsStore.open(dataStore) { revoked++ }

    @After fun closeStores() = scopes.forEach { it.cancel() }

    @Test fun defaultsAreDemoWithoutConsent() = runTest {
        val s = openStore().settings.value
        assertEquals(Settings(consented = false, mode = Mode.DEMO, serverUrl = "http://10.0.2.2:8000"), s)
        assertEquals(true, s.prepCloseWindows && s.prepDefog && s.prepHeadlights)
    }

    @Test fun revokingConsentNotifiesOnce() = runTest {
        val store = openStore()
        store.update { it.copy(consented = true) }
        store.update { it.copy(prepDefog = false) }
        assertEquals(0, revoked)

        store.update { it.copy(consented = false) }
        assertFalse(store.settings.value.consented)
        assertEquals(1, revoked)

        store.update { it.copy(mode = Mode.LIVE) }
        assertEquals(1, revoked)

        store.update { it.copy(consented = true) }
        store.update { it.copy(consented = false) }
        assertEquals(2, revoked)
    }

    @Test fun revokeSurvivesCallerCancellation() = runTest {
        val real = newDataStore()
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var holdNextWrite = false
        val gated = object : DataStore<Preferences> by real {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (holdNextWrite) {
                    holdNextWrite = false
                    writing.complete(Unit)
                    release.await()
                }
                return real.updateData(transform)
            }
        }
        val store = openStore(gated)
        store.update { it.copy(consented = true) }
        holdNextWrite = true

        val caller = launch { store.update { it.copy(consented = false) } }
        writing.await()
        caller.cancel()
        release.complete(Unit)
        caller.join()

        assertFalse(store.settings.value.consented)
        assertEquals(1, revoked)
    }

    @Test fun settingsSurviveReopen() = runTest {
        openStore().update { it.copy(consented = true, mode = Mode.LIVE, serverUrl = "http://192.168.0.5:8000", prepHeadlights = false) }
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }

        val reopened = openStore().settings.value
        assertEquals(Settings(consented = true, mode = Mode.LIVE, serverUrl = "http://192.168.0.5:8000", prepHeadlights = false), reopened)
    }
}
