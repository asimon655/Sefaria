package org.asimon.sefaria

import androidx.compose.runtime.getValue
import org.asimon.sefaria.ui.AppLanguage
import android.content.Context
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import org.asimon.sefaria.ui.theme.isDark
import org.asimon.sefaria.feature.onboarding.Onboarding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.asimon.sefaria.data.Settings
import org.asimon.sefaria.ui.theme.SefariaTheme
import javax.inject.Inject
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.asimon.sefaria.ui.DeviceFonts
import org.asimon.sefaria.ui.LocalTextFonts
import org.asimon.sefaria.ui.TextFonts

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: Settings

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs by settings.prefs.collectAsStateWithLifecycle()
            val fonts by produceState(TextFonts(), prefs.heFont, prefs.enFont) {
                value = withContext(Dispatchers.IO) { TextFonts(DeviceFonts.family(prefs.heFont), DeviceFonts.family(prefs.enFont)) }
            }
            // system bar icons follow the app's theme, not the system's
            val dark = prefs.theme.isDark(isSystemInDarkTheme())
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            SefariaTheme(prefs.theme) {
                CompositionLocalProvider(LocalTextFonts provides fonts) {
                if (prefs.onboarded) AppNav(prefs.navLabels)
                else Onboarding(
                    prefs.layout,
                    onLayout = { l -> lifecycleScope.launch { settings.setLayout(l) } },
                    onDone = { lifecycleScope.launch { settings.setOnboarded() } },
                )
                }
            }
        }
        // deferred startup work (App) waits for the first frame
        val view = window.decorView
        view.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                Startup.firstFrameDrawn()
                view.post { view.viewTreeObserver.removeOnDrawListener(this) } // not while drawing
            }
        })
    }
}
