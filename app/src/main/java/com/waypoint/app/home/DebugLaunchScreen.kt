package com.waypoint.app.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.waypoint.app.InitStep
import java.io.File

@Composable
fun DebugLaunchScreen(
    initSteps: List<InitStep>,
    startupCrash: Throwable?,
    onReady: () -> Unit
) {
    val context = LocalContext.current

    // Auto-proceed if everything initialized fine
    LaunchedEffect(startupCrash) {
        if (startupCrash == null) onReady()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Spacer(Modifier.height(56.dp))

        Text(
            text = "Waypoint",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (startupCrash == null) "Starting…" else "Startup failed",
            style = MaterialTheme.typography.bodyMedium,
            color = if (startupCrash != null)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

        initSteps.forEach { step ->
            StepRow(step)
        }

        if (startupCrash != null) {
            Spacer(Modifier.height(24.dp))
            CrashDetail(crash = startupCrash, context = context)
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun StepRow(step: InitStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (step.ok) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (step.ok)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(step.name, style = MaterialTheme.typography.bodyMedium)
            if (!step.ok && step.error != null) {
                Text(
                    text = "${step.error.javaClass.simpleName}: ${step.error.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun CrashDetail(crash: Throwable, context: Context) {
    var expanded by remember { mutableStateOf(false) }
    val stackTrace = remember(crash) { crash.stackTraceToString() }
    val logText = remember {
        runCatching {
            File(context.filesDir, "waypoint_last_session.log").readText()
        }.getOrElse { "(log not available: ${it.message})" }
    }
    val fullReport = "=== Exception ===\n$stackTrace\n\n=== Last session log ===\n$logText"

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val logFile = File(context.filesDir, "waypoint_last_session.log")
            if (logFile.exists()) {
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", logFile
                )
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "Waypoint crash log")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share crash log"
                    )
                )
            }
        }) { Text("Share Log") }

        OutlinedButton(onClick = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("crash", fullReport))
        }) { Text("Copy") }

        OutlinedButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide trace" else "Show trace")
        }
    }

    if (expanded) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stackTrace,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.shapes.small
                )
                .padding(8.dp)
        )
    }
}
