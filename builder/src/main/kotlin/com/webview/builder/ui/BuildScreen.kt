package com.webview.builder.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.webview.builder.BuilderApp
import com.webview.builder.build.BuildJob
import com.webview.builder.build.BuildStep
import com.webview.builder.build.JobStatus

//========== BUILD PROGRESS / RESULTS ==========

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BuildScreen(app: BuilderApp, onClose: () -> Unit, onShowBuilds: () -> Unit) {
    val context = LocalContext.current
    val builds = app.builds
    val jobs = builds.jobs
    val finished = jobs.count { it.status != JobStatus.WAITING && it.status != JobStatus.RUNNING }
    val done = jobs.filter { it.status == JobStatus.DONE }
    // Overall progress: finished apps plus the share of steps done in the current one.
    val current = jobs.firstOrNull { it.status == JobStatus.RUNNING }
    val stepShare = current?.step?.let { (it.ordinal + 1f) / (BuildStep.entries.size + 1f) } ?: 0f
    val progress = if (jobs.isEmpty()) 0f else (finished + stepShare) / jobs.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Build ${builds.buildNumber}") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") } },
                actions = {
                    if (builds.running) TextButton(onClick = builds::cancel) { Text("Cancel") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                val title = when {
                    builds.running -> "Building ${finished + 1} of ${jobs.size}"
                    done.size == jobs.size -> if (done.size == 1) "Your APK is ready" else "${done.size} APKs are ready"
                    else -> "${done.size} of ${jobs.size} APKs ready"
                }
                Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                VSpace(16.dp)
                if (builds.running) {
                    LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    VSpace(20.dp)
                }
            }
            itemsIndexed(jobs) { index, job -> JobCard(app, job, index, jobs.size) }
            item {
                VSpace(16.dp)
                if (!builds.running && done.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            context.startActivity(builds.shareIntent(done.mapNotNull { it.output }))
                        }) { Text(if (done.size == 1) "Share" else "Share all") }
                        Button(onClick = onShowBuilds) { Text("All builds") }
                    }
                    VSpace(12.dp)
                    Banner(
                        icon = Icons.Filled.Check,
                        text = "The first time, Android asks to allow installs from this app. Turn it on once, then tap Install again.",
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                VSpace(24.dp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun JobCard(app: BuilderApp, job: BuildJob, index: Int, count: Int) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    val container = when (job.status) {
        JobStatus.RUNNING -> colors.primaryContainer
        JobStatus.FAILED -> colors.errorContainer
        else -> colors.surfaceContainerLow
    }
    Column(
        Modifier.fillMaxWidth().clip(groupShape(index, count)).background(container).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                when (job.status) {
                    // Expressive shape-morphing loader while this app builds.
                    JobStatus.RUNNING -> LoadingIndicator(Modifier.size(48.dp))
                    JobStatus.DONE -> StatusDot(Icons.Filled.Check, status.successContainer, status.onSuccessContainer)
                    JobStatus.FAILED, JobStatus.CANCELLED -> StatusDot(Icons.Filled.Close, colors.error, colors.onError)
                    JobStatus.WAITING -> AppAvatar(
                        app.projects.iconFile(job.project.folder), job.project.iconBackground,
                        job.project.initials, AvatarShapes[2],
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(job.project.appName, style = MaterialTheme.typography.titleMedium)
                val detail = when (job.status) {
                    JobStatus.WAITING -> "Waiting"
                    JobStatus.RUNNING -> job.step?.label ?: "Starting"
                    JobStatus.DONE -> "%.1f s · %s".format(job.seconds, job.output?.let { "%.1f MB".format(it.length() / 1048576.0) } ?: "")
                    JobStatus.FAILED -> "Failed"
                    JobStatus.CANCELLED -> "Cancelled"
                }
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            if (job.status == JobStatus.DONE) {
                val file = job.output!!
                FilledTonalIconButton(onClick = { context.startActivity(app.builds.shareIntent(listOf(file))) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share ${job.project.appName}")
                }
                Button(onClick = {
                    app.builds.install(file)?.let { settings -> context.startActivity(settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Install") }
            }
        }
        if (job.status == JobStatus.FAILED) {
            Text(job.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
        }
    }
}

@Composable
private fun StatusDot(icon: androidx.compose.ui.graphics.vector.ImageVector, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content)
    }
}
