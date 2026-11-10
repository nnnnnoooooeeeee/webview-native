package com.webview.builder.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.webview.builder.BuilderApp
import com.webview.builder.build.BuildManager
import com.webview.builder.build.BuiltApk

//========== BUILDS (OUTPUT FOLDER) ==========

@Composable
fun OutputsScreen(app: BuilderApp, modifier: Modifier) {
    val context = LocalContext.current
    val builds = app.builds
    var saving by remember { mutableStateOf<BuiltApk?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BuildManager.APK_TYPE)) { uri ->
        val apk = saving
        if (uri != null && apk != null) builds.saveCopy(apk.file, uri)
        saving = null
    }
    LaunchedEffect(Unit) { builds.refreshOutputs() }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text("Builds", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = 4.dp))
            Text(
                "APKs built on this phone. Save a copy to keep one outside the app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 16.dp),
            )
            if (builds.outputs.isEmpty()) {
                Banner(
                    icon = Icons.Filled.Star,
                    text = "Nothing built yet. Pick apps on the Apps tab and tap Build.",
                    container = MaterialTheme.colorScheme.surfaceContainerLow,
                    content = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        itemsIndexed(builds.outputs, key = { _, apk -> apk.name }) { index, apk ->
            Column(
                Modifier.fillMaxWidth().clip(groupShape(index, builds.outputs.size))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(apk.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(apk.sizeText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { builds.delete(apk) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${apk.name}") }
                    IconButton(onClick = { context.startActivity(builds.shareIntent(listOf(apk.file))) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share ${apk.name}")
                    }
                    androidx.compose.material3.OutlinedButton(onClick = {
                        saving = apk
                        saver.launch(apk.name)
                    }) { Text("Save copy") }
                    Button(onClick = {
                        builds.install(apk.file)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("Install") }
                }
            }
        }
    }
}
