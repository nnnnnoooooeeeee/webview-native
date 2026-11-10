package com.webview.builder.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.webview.builder.BuilderApp
import com.webview.builder.sign.SigningKey
import com.webview.builder.sign.SigningKeys
import com.webview.builder.sign.WrongPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat

//========== SIGNING KEY SCREEN ==========

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyScreen(app: BuilderApp, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val key: SigningKey? = remember(revision) { runCatching { app.keys.load() }.getOrNull() }
    var creating by remember { mutableStateOf(false) }
    var importBytes by remember { mutableStateOf<ByteArray?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pkcs12")) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(app.keys.exportBytes()) }
            message = "Backup saved. Keep the password with it: tap Show password."
        }
    }
    val status = LocalStatusColors.current

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Signing key", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = 4.dp))
        Text(
            "Every APK is signed with this key. Android only installs an update when it is signed with the same key, so create it once and keep a backup.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            key == null -> Banner(
                Icons.Filled.Warning, title = "No key yet",
                text = "The first build creates an automatic key. Create or import your own key first if you will share these apps.",
                container = status.warningContainer, content = status.onWarningContainer,
            )
            key.automatic -> Banner(
                Icons.Filled.Warning, title = "Automatic key",
                text = "Made on the first build, only on this phone. Export a backup, or replace it with your own key before you share APKs.",
                container = status.warningContainer, content = status.onWarningContainer,
            )
            else -> Banner(
                Icons.Filled.Check, title = "Release key: ${key.alias}",
                text = "Valid until ${DateFormat.getDateInstance().format(key.certificate.notAfter)}",
                container = status.successContainer, content = status.onSuccessContainer,
            )
        }

        if (key != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SectionLabel("Certificate")
                Text(key.certificate.subjectX500Principal.name, style = MaterialTheme.typography.bodyMedium)
                SectionLabel("SHA-256 fingerprint")
                Text(key.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (showPassword) {
                    SectionLabel("Password")
                    Text(app.keys.exportPassword(), fontFamily = FontFamily.Monospace)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = true }, modifier = Modifier.weight(1f)) { Text("Create new") }
            FilledTonalButton(onClick = { importer.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("Import .jks / .p12") }
        }
        if (key != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exporter.launch("signing-key.p12") }) { Text("Export backup") }
                TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide password" else "Show password") }
            }
            TextButton(
                onClick = { confirmRemove = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Remove key") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }

    //========== DIALOGS ==========

    if (creating) {
        CreateKeySheet(
            replacing = key != null,
            onDismiss = { creating = false },
            onCreate = { alias, password, name, org, country, years ->
                scope.launch {
                    withContext(Dispatchers.Default) { app.keys.create(alias, password, name, org, country, years) }
                    creating = false
                    message = "Key created. Export a backup now."
                    revision++
                }
            },
        )
    }

    importBytes?.let { bytes ->
        ImportKeyDialog(
            onDismiss = { importBytes = null },
            onImport = { storePassword, keyPassword, setError ->
                scope.launch {
                    try {
                        withContext(Dispatchers.Default) { app.keys.import(bytes, storePassword, keyPassword) }
                        importBytes = null
                        message = "Key imported."
                        revision++
                    } catch (e: WrongPasswordException) {
                        setError("Wrong password")
                    } catch (e: Exception) {
                        setError(e.message ?: "Not a keystore file")
                    }
                }
            },
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            icon = { androidx.compose.material3.Icon(Icons.Filled.Lock, null) },
            title = { Text("Remove the signing key?") },
            text = { Text("Apps signed with it can only be updated by this key. Without a backup they must be uninstalled before the next update.") },
            confirmButton = {
                Button(
                    onClick = { app.keys.delete(); confirmRemove = false; revision++ },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateKeySheet(
    replacing: Boolean,
    onDismiss: () -> Unit,
    onCreate: (alias: String, password: String, name: String, org: String, country: String, years: Int) -> Unit,
) {
    var alias by remember { mutableStateOf("upload") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var years by remember { mutableStateOf(25f) }
    val valid = alias.isNotBlank() && password.length >= 6 && password == confirm && name.isNotBlank()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Create signing key", style = MaterialTheme.typography.headlineSmall)
            if (replacing) {
                Text(
                    "This replaces the current key. Apps signed with the old key can't be updated with the new one.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedTextField(alias, { alias = it.trim() }, label = { Text("Key alias") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                password, { password = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text(if (password.length in 1..5) "At least 6 characters" else "Write it down: you need it to restore a backup") },
                trailingIcon = {
                    TextButton(onClick = { SigningKeys.randomPassword().let { password = it; confirm = it } }) { Text("Generate") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                confirm, { confirm = it }, label = { Text("Confirm password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                isError = confirm.isNotEmpty() && confirm != password, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(name, { name = it }, label = { Text("Your name or app maker") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(org, { org = it }, label = { Text("Organization (optional)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    country, { country = it.take(2).uppercase() }, label = { Text("Country") },
                    placeholder = { Text("ID") }, singleLine = true, modifier = Modifier.weight(0.45f),
                )
            }
            Text("Valid for ${years.toInt()} years", style = MaterialTheme.typography.titleSmall)
            Slider(value = years, onValueChange = { years = it }, valueRange = 10f..50f, steps = 7)
            Button(
                onClick = { onCreate(alias, password, name.trim(), org.trim(), country, years.toInt()) },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create key") }
        }
    }
}

@Composable
private fun ImportKeyDialog(onDismiss: () -> Unit, onImport: (String, String?, (String) -> Unit) -> Unit) {
    var storePassword by remember { mutableStateOf("") }
    var keyPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The first key in the file is used.")
                OutlinedTextField(
                    storePassword, { storePassword = it; error = null }, label = { Text("Keystore password") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
                OutlinedTextField(
                    keyPassword, { keyPassword = it }, label = { Text("Key password (if different)") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(enabled = storePassword.isNotEmpty(), onClick = { onImport(storePassword, keyPassword, { error = it }) }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
