package com.webview.builder.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.webview.builder.BuildConfig
import com.webview.builder.BuilderApp
import com.webview.builder.data.ThemeMode

//========== SETTINGS SCREEN ==========

@Composable
fun SettingsScreen(app: BuilderApp, modifier: Modifier) {
    val settings = app.settings
    val darkPossible = settings.themeMode != ThemeMode.LIGHT
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))

        SectionLabel("Theme")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val modes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
            modes.forEachIndexed { i, (mode, label) ->
                SegmentedButton(
                    selected = settings.themeMode == mode,
                    onClick = { settings.updateTheme(mode) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(label) }
            }
        }
        VSpace(8.dp)
        ToggleRow(
            title = "AMOLED black",
            text = if (darkPossible) "Pure black backgrounds in dark mode. Saves power on OLED screens."
            else "Available when the theme is System or Dark",
            checked = settings.amoled,
            enabled = darkPossible,
            index = 0, count = 2,
            onChange = settings::updateAmoled,
        )
        ToggleRow(
            title = "Wallpaper colors",
            text = if (Build.VERSION.SDK_INT >= 31) "Use colors from your wallpaper (Material You)" else "Needs Android 12 or newer",
            checked = settings.dynamicColor,
            enabled = Build.VERSION.SDK_INT >= 31,
            index = 1, count = 2,
            onChange = settings::updateDynamicColor,
        )

        SectionLabel("About", Modifier.padding(top = 12.dp))
        Column(
            Modifier.fillMaxWidth().clip(groupShape(0, 1)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("APK Builder ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Builds WebView apps right on this phone: no computer, no internet connection, no account needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    text: String,
    checked: Boolean,
    enabled: Boolean,
    index: Int,
    count: Int,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(groupShape(index, count)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(enabled = enabled) { onChange(!checked) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
}
