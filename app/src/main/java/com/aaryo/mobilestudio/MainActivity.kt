package com.aaryo.mobilestudio

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aaryo.mobilestudio.ui.Ds
import com.aaryo.mobilestudio.ui.IdeScreen
import com.aaryo.mobilestudio.ui.IdeViewModel
import com.aaryo.mobilestudio.ui.SplashScreen

private val MobileStudioXDarkColors = darkColorScheme(
    primary = Ds.Purple,
    onPrimary = Ds.OnBg,
    secondary = Ds.PurpleSoft,
    onSecondary = Ds.Bg,
    tertiary = Ds.Green,
    background = Ds.Bg,
    onBackground = Ds.OnBg,
    surface = Ds.Card,
    onSurface = Ds.OnBg,
    surfaceVariant = Ds.Field,
    onSurfaceVariant = Ds.Muted,
    surfaceContainer = Ds.CardAlt,
    surfaceContainerHigh = Ds.CardAlt,
    outline = Ds.Line,
    error = Ds.Red
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        setContent {
            MaterialTheme(colorScheme = MobileStudioXDarkColors) {
                var splashDone by rememberSaveable { mutableStateOf(false) }
                if (!splashDone) {
                    SplashScreen { splashDone = true }
                } else {
                    val vm: IdeViewModel = viewModel()
                    IdeScreen(vm)
                }
            }
        }
    }
}
