package com.webview.builder.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.webview.builder.BuilderApp
import com.webview.builder.data.AppProject
import com.webview.builder.data.Validation
import java.io.File

//========== ADD / EDIT APP ==========

private val Swatches = listOf("#FFFFFF", "#E0E0FF", "#B3F1C6", "#FFDDB4", "#FFD8E8", "#1B1B21")

/** Full-screen form for a new app ([folder] = null) or an existing one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditAppScreen(app: BuilderApp, folder: String?, onClose: (changed: Boolean) -> Unit) {
    val context = LocalContext.current
    val original = remember(folder) { folder?.let { app.projects.load(it) } }
    val others = remember { app.projects.list().filter { it.folder != folder } }

    var name by remember { mutableStateOf(original?.appName ?: "") }
    var url by remember { mutableStateOf(original?.appUrl ?: "https://") }
    var folderName by remember { mutableStateOf(original?.folder ?: "") }
    var folderTouched by remember { mutableStateOf(original != null) }
    var version by remember { mutableStateOf(original?.versionName ?: "1.0.0") }
    var appId by remember { mutableStateOf(original?.applicationId ?: "") }
    var appIdTouched by remember { mutableStateOf(original != null) }
    var appIdLocked by remember { mutableStateOf(original != null) }
    var background by remember { mutableStateOf(original?.iconBackground ?: "#FFFFFF") }
    var pull by remember { mutableStateOf(original?.pullToRefresh ?: true) }
    var newIcon by remember { mutableStateOf<ByteArray?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Folder and ID follow the name until the user edits them.
    val folderValue = if (folderTouched) folderName else Validation.slug(name)
    val appIdValue = if (appIdTouched) appId else Validation.suggestAppId(name)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) newIcon = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    //========== VALIDATION ==========
    val urlError = if (Validation.isUrl(url)) null else "Start with http:// or https://"
    val versionError = if (Validation.isVersion(version)) null else "Use numbers like 1.0.0"
    val folderError = when {
        !Validation.isFolder(folderValue) -> "Letters, digits, - and _ only"
        others.any { it.folder == folderValue } -> "Another app uses this folder"
        else -> null
    }
    val idOwner = others.firstOrNull { it.applicationId == appIdValue }
    val appIdError = when {
        !Validation.isAppId(appIdValue) -> "Use the form com.company.app"
        idOwner != null -> "Already used by ${idOwner.appName}. Two apps with one ID replace each other."
        else -> null
    }
    val valid = name.isNotBlank() && Validation.isColor(background) && listOf(urlError, versionError, folderError, appIdError).all { it == null }

    fun save() {
        val project = AppProject(
            folder = folderValue,
            appName = name.trim(),
            appUrl = url.trim(),
            applicationId = appIdValue,
            versionName = version,
            iconBackground = background,
            pullToRefresh = pull,
        )
        app.projects.save(project, oldFolder = folder, icon = newIcon)
        onClose(true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (original == null) "Add app" else "Edit ${original.appName}") },
                navigationIcon = {
                    IconButton(onClick = { onClose(false) }) { Icon(Icons.Filled.Close, contentDescription = "Close without saving") }
                },
                actions = {
                    Button(onClick = ::save, enabled = valid, modifier = Modifier.padding(end = 12.dp)) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            //========== ICON ==========
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val preview = remember(newIcon) {
                    newIcon?.let { bytes -> File(context.cacheDir, "icon-preview.png").apply { writeBytes(bytes) } }
                }
                AppAvatar(
                    iconFile = preview ?: folder?.let { app.projects.iconFile(it) } ?: File(context.cacheDir, "none"),
                    background = background,
                    initials = AppProject(folderValue, name, url, appIdValue, version).initials,
                    shape = CircleShape,
                    size = 72.dp,
                    stamp = System.identityHashCode(newIcon).toLong(),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (original == null && newIcon == null) "Pick icon" else "Replace icon") }
                    Text(
                        "Square image, 1024 × 1024 recommended",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            //========== FIELDS ==========
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("App name") },
                supportingText = { Text("Shown below the icon on the phone") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it.trim() }, label = { Text("Website URL") },
                isError = urlError != null, supportingText = { Text(urlError ?: "The page the app opens") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = folderValue, onValueChange = { folderName = it.trim(); folderTouched = true },
                    label = { Text("Folder") }, isError = folderError != null,
                    supportingText = { Text(folderError ?: "Short name, also used for file names") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = version, onValueChange = { version = it.trim() }, label = { Text("Version") },
                    isError = versionError != null, supportingText = versionError?.let { { Text(it) } },
                    singleLine = true, modifier = Modifier.weight(0.6f),
                )
            }
            if (original != null) {
                // Quick version bumps: patch, minor, major.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 1, 0).forEach { part ->
                        val next = Validation.bump(original.versionName, part)
                        OutlinedButton(onClick = { version = next }) { Text(next) }
                    }
                }
            }

            OutlinedTextField(
                value = appIdValue,
                onValueChange = { appId = it.trim(); appIdTouched = true },
                label = { Text("Application ID") },
                enabled = !appIdLocked,
                isError = appIdError != null,
                supportingText = {
                    Text(
                        appIdError ?: if (appIdLocked) "Locked: changing it makes phones treat this as a different app"
                        else "Unique. Do not change it after the app is released."
                    )
                },
                leadingIcon = if (appIdLocked) ({ Icon(Icons.Filled.Lock, null) }) else null,
                trailingIcon = if (appIdLocked) ({ TextButton(onClick = { appIdLocked = false }) { Text("Unlock") } }) else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            //========== ICON BACKGROUND ==========
            Text("Icon background", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Swatches.forEach { hex ->
                    val chosen = hex.equals(background, ignoreCase = true)
                    Row(
                        Modifier.size(40.dp).clip(CircleShape)
                            .border(
                                if (chosen) BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                                else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                CircleShape,
                            )
                            .padding(if (chosen) 5.dp else 0.dp)
                            .clip(CircleShape)
                            .background(Color(android.graphics.Color.parseColor(hex)))
                            .clickable { background = hex },
                    ) {}
                }
            }
            OutlinedTextField(
                value = background,
                onValueChange = { background = it.trim().take(7) },
                label = { Text("Custom color") },
                isError = !Validation.isColor(background),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(0.5f),
            )

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable { pull = !pull }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Pull to refresh", style = MaterialTheme.typography.titleSmall)
                    Text("Swipe down at the top of the page to reload it", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = pull, onCheckedChange = { pull = it })
            }

            if (original != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Delete, null)
                    Text("  Delete app…")
                }
            }
            VSpace(24.dp)
        }
    }

    if (confirmDelete && original != null) {
        DeleteDialog(
            project = original,
            onDismiss = { confirmDelete = false },
            onDelete = { alsoApks ->
                app.projects.delete(original.folder)
                if (alsoApks) app.builds.deleteOutputsOf(original.folder)
                onClose(true)
            },
        )
    }
}

/** Asks to type the folder name before deleting, as the action can't be undone. */
@Composable
private fun DeleteDialog(project: AppProject, onDismiss: () -> Unit, onDelete: (alsoApks: Boolean) -> Unit) {
    var typed by remember { mutableStateOf("") }
    var alsoApks by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Delete ${project.appName}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Removes its settings and icon from this builder. Phones that already have the app keep it.")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { alsoApks = !alsoApks }) {
                    Checkbox(checked = alsoApks, onCheckedChange = { alsoApks = it })
                    Text("Also delete its built APKs")
                }
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it },
                    label = { Text("Type ${project.folder} to confirm") }, singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onDelete(alsoApks) },
                enabled = typed.trim() == project.folder,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete app") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
