package com.webview.builder.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.webview.builder.BuilderApp
import com.webview.builder.data.AppProject

//========== APPS SCREEN ==========

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(
    app: BuilderApp,
    revision: Int,
    modifier: Modifier,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onBuild: (List<AppProject>) -> Unit,
) {
    val projects = remember(revision) { app.projects.list() }
    var selected by remember(revision) { mutableStateOf(setOf<String>()) }
    var query by remember { mutableStateOf("") }
    var editNumber by remember { mutableStateOf(false) }
    val hasReleaseKey = remember(revision) { app.keys.load()?.automatic == false }
    val shown = projects.filter {
        query.isBlank() || it.appName.contains(query, true) || it.folder.contains(query, true)
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Build APKs", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add app") }
                }
                VSpace(12.dp)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search ${projects.size} apps") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                VSpace(10.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val status = LocalStatusColors.current
                    AssistChip(
                        onClick = {},
                        label = { Text(if (hasReleaseKey) "Release key" else "Automatic key") },
                        leadingIcon = {
                            Icon(
                                if (hasReleaseKey) Icons.Filled.Check else Icons.Filled.Warning, null,
                                tint = if (hasReleaseKey) status.success else status.onWarningContainer,
                            )
                        },
                        colors = if (hasReleaseKey) AssistChipDefaults.assistChipColors()
                        else AssistChipDefaults.assistChipColors(containerColor = status.warningContainer, labelColor = status.onWarningContainer),
                    )
                    AssistChip(
                        onClick = { editNumber = true },
                        label = { Text("Build ${app.settings.nextBuildNumber}") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    )
                }
                VSpace(12.dp)
            }
            itemsIndexed(shown, key = { _, p -> p.folder }) { index, project ->
                val isSelected = project.folder in selected
                AppRow(
                    app = app,
                    project = project,
                    index = index,
                    selected = isSelected,
                    onToggle = { selected = if (isSelected) selected - project.folder else selected + project.folder },
                    onEdit = { onEdit(project.folder) },
                )
            }
        }

        if (selected.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = { onBuild(projects.filter { it.folder in selected }) },
                icon = { Icon(Icons.Filled.PlayArrow, null) },
                text = { Text(if (selected.size == 1) "Build 1 app" else "Build ${selected.size} apps") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (editNumber) BuildNumberDialog(app, onDismiss = { editNumber = false })
}

/** One app; tap selects it, long-press (or the edit button) opens the editor. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: BuilderApp,
    project: AppProject,
    index: Int,
    selected: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // Expressive shape change: selected rows get rounder corners.
    val corner by animateDpAsState(if (selected) 28.dp else 16.dp, label = "corner")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(corner))
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerLow)
            .combinedClickable(onClick = onToggle, onLongClick = onEdit)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppAvatar(
            iconFile = app.projects.iconFile(project.folder),
            background = project.iconBackground,
            initials = project.initials,
            shape = if (selected) CircleShape else AvatarShapes[index % AvatarShapes.size],
        )
        Column(Modifier.weight(1f)) {
            Text(project.appName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                "${project.folder} · v${project.versionName}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${project.appName}") }
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun BuildNumberDialog(app: BuilderApp, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(app.settings.nextBuildNumber.toString()) }
    val number = text.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Build number") },
        text = {
            Column {
                Text("The versionCode of the next build. Phones only install an update with a higher number than the installed app.")
                VSpace(12.dp)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(9) },
                    singleLine = true,
                    isError = number == null || number < 1,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = number != null && number >= 1, onClick = {
                app.settings.updateNextBuildNumber(number!!)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
