package com.aaryo.mobilestudio.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

private data class ToolOutput(val title: String, val text: String, val ok: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperToolsScreen(projectDir: File?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("pwd && ls -la") }
    var output by remember { mutableStateOf(listOf(ToolOutput("Ready", "Select a tool. Commands run in the selected project directory.", true))) }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("Terminal") }
    var gitRemote by remember { mutableStateOf("") }

    fun run(title: String, args: List<String>, cwd: File?, timeoutSeconds: Long = 20L) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val pb = ProcessBuilder(args).redirectErrorStream(true)
                    if (cwd != null && cwd.isDirectory) pb.directory(cwd)
                    val logFile = File(context.cacheDir, "devtools-${System.nanoTime()}.log")
                    pb.redirectOutput(logFile).redirectErrorStream(true)
                    val process = pb.start()
                    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                    if (!finished) { process.destroyForcibly(); ToolOutput(title, "Timed out after ${timeoutSeconds}s", false) }
                    else {
                        val text = logFile.takeIf { it.isFile }?.readText()?.take(24000).orEmpty()
                        logFile.delete()
                        ToolOutput(title, text.ifBlank { "Command completed with exit code ${process.exitValue()}" }, process.exitValue() == 0)
                    }
                } catch (t: Throwable) { ToolOutput(title, "${t.javaClass.simpleName}: ${t.message}", false) }
            }
            output = listOf(result) + output.take(29)
            busy = false
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Developer Tools", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text(projectDir?.absolutePath ?: "No project selected", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        ScrollableTabRow(selectedTabIndex = listOf("Terminal", "Git", "Logcat", "Device").indexOf(selected).coerceAtLeast(0)) {
            listOf("Terminal", "Git", "Logcat", "Device").forEachIndexed { index, name ->
                Tab(selected == name, { selected = name }, text = { Text(name) })
            }
        }
        when (selected) {
            "Terminal" -> {
                Text("Runs Android shell commands from the app process. This is not a full Linux environment.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(command, { command = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Command") }, singleLine = false)
                Button(onClick = { run("Terminal", listOf("/system/bin/sh", "-c", command), projectDir) }, enabled = !busy && command.isNotBlank()) { Icon(Icons.Default.PlayArrow, null); Text(" Run") }
            }
            "Git" -> {
                Text("Git requires a compatible git executable in the app PATH. Android does not include Git by default.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(gitRemote, { gitRemote = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Remote URL (for clone)") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { run("Git status", listOf("git", "status", "--short", "--branch"), projectDir) }, enabled = !busy && projectDir != null) { Text("Status") }
                    OutlinedButton(onClick = { run("Git diff", listOf("git", "diff", "--stat"), projectDir) }, enabled = !busy && projectDir != null) { Text("Diff") }
                }
                OutlinedButton(onClick = { run("Git version", listOf("git", "--version"), context.filesDir) }, enabled = !busy) { Text("Check Git availability") }
                Text("Clone/commit/push are intentionally not enabled until a managed Git binary and credential-safe authentication are configured.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
            "Logcat" -> {
                Text("Reads recent device logs visible to this app process. Some system logs are restricted by Android.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { run("Recent Logcat", listOf("logcat", "-d", "-t", "300", "-v", "time"), context.filesDir, 15) }, enabled = !busy) { Icon(Icons.Default.Refresh, null); Text(" Capture recent logs") }
                OutlinedButton(onClick = { run("Clear app-visible log buffer", listOf("logcat", "-c"), context.filesDir, 10) }, enabled = !busy) { Text("Clear log buffer") }
            }
            else -> {
                Text("Device information", color = Color.White, style = MaterialTheme.typography.titleMedium)
                listOf("Manufacturer" to Build.MANUFACTURER, "Model" to Build.MODEL, "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", "ABI" to Build.SUPPORTED_ABIS.joinToString(), "App process" to "${android.os.Process.myPid()}").forEach { (k,v) ->
                    ListItem(headlineContent = { Text(k, color = Color.White) }, supportingContent = { Text(v, color = Color.LightGray) }, colors = ListItemDefaults.colors(containerColor = Color(0xFF191F2D)))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Output", color = Color.White, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { output = emptyList() }) { Text("Clear") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(output) { item ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171D29))) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.title, color = if (item.ok) Color(0xFF65D6A4) else Color(0xFFFF8292), style = MaterialTheme.typography.titleSmall)
                        Text(item.text, color = Color(0xFFD5D9E3), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
