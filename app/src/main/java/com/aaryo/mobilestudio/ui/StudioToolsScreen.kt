package com.aaryo.mobilestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private val StudioBg = Color(0xFF090B12)
private val StudioCard = Color(0xFF171D29)
private val StudioAccent = Color(0xFF9B8CFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioToolsScreen(projectDir: File?, modifier: Modifier = Modifier) {
    var section by remember { mutableStateOf("Designer") }
    var component by remember { mutableStateOf("Button") }
    var title by remember { mutableStateOf("Welcome to my app") }
    var buttonText by remember { mutableStateOf("Get started") }
    var includeText by remember { mutableStateOf(true) }
    var includeButton by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf("Live designer preview. Generated XML layout can be saved directly to your project.") }
    val modules = remember(projectDir) { projectDir?.listFiles()?.filter { it.isDirectory && (File(it, "build.gradle").isFile || File(it, "build.gradle.kts").isFile) }?.map { it.name }.orEmpty() }
    val gradleFiles = remember(projectDir) { projectDir?.walkTopDown()?.maxDepth(4)?.filter { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }?.take(12)?.toList().orEmpty() }
    val xml = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\"\n    android:orientation=\"vertical\"\n    android:padding=\"24dp\">\n")
        if (includeText) append("    <TextView\n        android:layout_width=\"match_parent\"\n        android:layout_height=\"wrap_content\"\n        android:text=\"${title.replace("&", "&amp;").replace("\"", "\\\"")}\"\n        android:textSize=\"22sp\" />\n")
        if (includeButton) append("    <Button\n        android:layout_width=\"wrap_content\"\n        android:layout_height=\"wrap_content\"\n        android:text=\"${buttonText.replace("&", "&amp;").replace("\"", "\\\"")}\" />\n")
        append("</LinearLayout>")
    }
    Column(modifier.fillMaxSize().background(StudioBg).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ADVANCED STUDIO", color = StudioAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text("Design · Inspect · Manage", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        ScrollableTabRow(selectedTabIndex = listOf("Designer", "Database", "Network", "Profiler", "Modules").indexOf(section), containerColor = StudioBg, edgePadding = 0.dp) {
            listOf("Designer", "Database", "Network", "Profiler", "Modules").forEach { label -> Tab(section == label, { section = label }, text = { Text(label) }) }
        }
        when (section) {
            "Designer" -> {
                Card(colors = CardDefaults.cardColors(containerColor = StudioCard)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Component palette", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Text", "Button", "Input", "Card").forEach { FilterChip(selected = component == it, onClick = { component = it }, label = { Text(it) }) } }
                        OutlinedTextField(title, { title = it }, label = { Text("Heading text") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(buttonText, { buttonText = it }, label = { Text("Button label") }, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(includeText, { includeText = it }); Text("Include heading", color = Color.White) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(includeButton, { includeButton = it }); Text("Include button", color = Color.White) }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F6FA))) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("DEVICE PREVIEW", color = Color(0xFF60677A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        if (includeText) Text(title, color = Color(0xFF161925), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        if (includeButton) Button(onClick = { notice = "Interactive UI action triggered for: '$buttonText'" }) { Text(buttonText) }
                        if (!includeText && !includeButton) Text("Empty layout", color = Color.DarkGray)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = StudioCard)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Generated XML", color = Color.White, fontWeight = FontWeight.SemiBold); Spacer(Modifier.weight(1f)); TextButton(onClick = { notice = "XML is displayed below. Use Save XML to write it into the selected project." }) { Text("Info") } }
                        Text(xml, color = Color(0xFFB9C5D9), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Button(onClick = {
                            val dir = projectDir?.let { File(it, "app/src/main/res/layout") }
                            try { if (dir == null) notice = "Open a project first." else { dir.mkdirs(); File(dir, "mobilestudio_designer.xml").writeText(xml); notice = "Saved: app/src/main/res/layout/mobilestudio_designer.xml" } }
                            catch (t: Throwable) { notice = "Save failed: ${t.message}" }
                        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Text(" Save XML to project") }
                    }
                }
            }
            "Database" -> InfoPanel("Database Inspector", "Room database files inside this app's private sandbox are not automatically readable by another app process. Add a project-specific Room inspector/debug bridge to inspect tables, rows and queries safely.", listOf("Room database discovery", "Table and schema browser", "Read-only query console", "Export selected rows"))
            "Network" -> InfoPanel("Network Inspector", "This panel describes the integration point. Capturing another app's traffic requires explicit instrumentation in the target app; this IDE cannot transparently inspect arbitrary app traffic.", listOf("OkHttp event listener integration", "Request / response timeline", "Headers and timing", "Redaction for tokens and passwords"))
            "Profiler" -> InfoPanel("Performance Profiler", "The IDE can inspect its own process metrics. Profiling a separately installed target app requires debuggable builds and supported Android profiling APIs.", listOf("IDE memory snapshot", "CPU / process sampling", "Build duration history", "Target-app profiling requires debuggable app"))
            else -> {
                Card(colors = CardDefaults.cardColors(containerColor = StudioCard)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Detected Gradle modules", color = Color.White, fontWeight = FontWeight.Bold)
                        if (modules.isEmpty()) Text("No Gradle submodules detected at project root.", color = Color.LightGray)
                        modules.forEach { ListItem(headlineContent = { Text(it, color = Color.White) }, supportingContent = { Text("Gradle module", color = Color.LightGray) }, leadingContent = { Icon(Icons.Default.ViewModule, null, tint = StudioAccent) }, colors = ListItemDefaults.colors(containerColor = StudioCard)) }
                        Text("Gradle build files", color = Color.White, fontWeight = FontWeight.SemiBold)
                        gradleFiles.forEach { Text(it.relativeTo(projectDir!!).path, color = Color(0xFFB9C5D9), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    }
                }
            }
        }
        Text(notice, color = Color(0xFF9FAAC0), fontSize = 12.sp)
    }
}

@Composable
private fun InfoPanel(title: String, description: String, capabilities: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = StudioCard)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(description, color = Color(0xFFB9C5D9), fontSize = 13.sp)
            capabilities.forEach { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = StudioAccent, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(it, color = Color.White, fontSize = 13.sp) } }
        }
    }
}
