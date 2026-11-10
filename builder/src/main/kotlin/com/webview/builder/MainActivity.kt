package com.webview.builder

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.webview.builder.ui.AppsScreen
import com.webview.builder.ui.BuildScreen
import com.webview.builder.ui.BuilderTheme
import com.webview.builder.ui.EditAppScreen
import com.webview.builder.ui.KeyScreen
import com.webview.builder.ui.OutputsScreen
import com.webview.builder.ui.SettingsScreen
import com.webview.builder.ui.isDarkTheme

//========== MAIN ACTIVITY ==========

/** Screens shown over the tabs. */
private sealed interface Overlay {
    data object None : Overlay
    data class Edit(val folder: String?) : Overlay
    data object Build : Overlay
}

private enum class Tab(val label: String, val icon: ImageVector) {
    APPS("Apps", Icons.Filled.Home),
    BUILDS("Builds", Icons.Filled.List),
    KEY("Key", Icons.Filled.Lock),
    SETTINGS("Settings", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BuilderApp
        setContent {
            val dark = isDarkTheme(app.settings)
            LaunchedEffect(dark) {
                // Transparent bars with icons that match the theme.
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            BuilderTheme(app.settings) { BuilderNavigation(app) }
        }
    }
}

@Composable
private fun BuilderNavigation(app: BuilderApp) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<Overlay>(if (app.builds.running) Overlay.Build else Overlay.None) }
    // Bumped after an edit so the apps list reloads from storage.
    var revision by remember { mutableIntStateOf(0) }

    BackHandler(enabled = overlay != Overlay.None) { overlay = Overlay.None }

    AnimatedContent(targetState = overlay, label = "overlay") { current ->
        when (current) {
            is Overlay.Edit -> EditAppScreen(
                app = app,
                folder = current.folder,
                onClose = { changed ->
                    if (changed) revision++
                    overlay = Overlay.None
                },
            )
            Overlay.Build -> BuildScreen(
                app = app,
                onClose = { overlay = Overlay.None },
                onShowBuilds = {
                    tab = Tab.BUILDS.ordinal
                    overlay = Overlay.None
                },
            )
            Overlay.None -> Scaffold(
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t.ordinal,
                                onClick = { tab = t.ordinal },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                val modifier = Modifier.padding(padding)
                when (Tab.entries[tab]) {
                    Tab.APPS -> AppsScreen(
                        app = app,
                        revision = revision,
                        modifier = modifier,
                        onAdd = { overlay = Overlay.Edit(null) },
                        onEdit = { overlay = Overlay.Edit(it) },
                        onBuild = { selected ->
                            app.builds.start(selected)
                            overlay = Overlay.Build
                        },
                    )
                    Tab.BUILDS -> OutputsScreen(app, modifier)
                    Tab.KEY -> KeyScreen(app, modifier)
                    Tab.SETTINGS -> SettingsScreen(app, modifier)
                }
            }
        }
    }
}
