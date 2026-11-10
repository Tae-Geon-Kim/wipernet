package ai.wipernet.car

import ai.wipernet.car.settings.SettingsStore
import ai.wipernet.car.ui.BrandSplash
import ai.wipernet.car.ui.ConsentScreen
import ai.wipernet.car.ui.HomeScreen
import ai.wipernet.car.ui.SettingsScreen
import ai.wipernet.car.ui.WiperNetTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val BRAND_SPLASH_MS = 1200L

@OptIn(ExperimentalCoroutinesApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val app = application as WiperNetApp
        setContent {
            WiperNetTheme {
                val pending = app.settingsStore
                val store by produceState(if (pending.isCompleted) pending.getCompleted() else null) { value = pending.await() }
                var splashDone by rememberSaveable { mutableStateOf(savedInstanceState != null) }
                LaunchedEffect(Unit) { delay(BRAND_SPLASH_MS); splashDone = true }
                val loaded = store
                if (loaded == null || !splashDone) BrandSplash() else WiperNetRoot(loaded)
            }
        }
    }
}

private enum class Screen { HOME, SETTINGS }

@Composable
private fun WiperNetRoot(store: SettingsStore) {
    val settings by store.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

    if (!settings.consented) {
        ConsentScreen(onAgree = {
            screen = Screen.HOME
            scope.launch { store.update { it.copy(consented = true) } }
        })
        return
    }
    when (screen) {
        Screen.HOME -> HomeScreen(mode = settings.mode, onOpenSettings = { screen = Screen.SETTINGS })
        Screen.SETTINGS -> {
            BackHandler { screen = Screen.HOME }
            SettingsScreen(
                settings = settings,
                onChange = { f -> scope.launch { store.update(f) } },
                onBack = { screen = Screen.HOME },
            )
        }
    }
}
