package com.aaryo.mobilestudio.ui

import android.content.Context
import android.net.TrafficStats
import android.os.Debug
import android.os.SystemClock
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aaryo.mobilestudio.toolchain.DownloadProgress
import com.aaryo.mobilestudio.toolchain.RuntimeStatus
import com.aaryo.mobilestudio.toolchain.SdkInventoryReport
import com.aaryo.mobilestudio.toolchain.ToolchainCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max

/** Tiny persisted editor preferences (font size) shared by Settings and the code editor. */
object EditorPrefs {
    private const val FILE = "mobilestudio_prefs"
    private const val FONT = "editor_font_size"
    fun fontSize(context: Context): Int = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(FONT, 13)
    fun setFontSize(context: Context, value: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(FONT, value).apply()
    }
}

/* ─────────────────────────── TOOLCHAINS ─────────────────────────── */

@Composable
fun ToolchainsScreen(
    runtime: RuntimeStatus,
    sdkReport: SdkInventoryReport,
    progress: DownloadProgress?,
    busy: Boolean,
    onBack: () -> Unit,
    onBell: () -> Unit,
    onInstall: () -> Unit,
    onRefresh: () -> Unit,
    onVerify: () -> Unit,
    onInstallLocal: () -> Unit,
    onChooseStorage: () -> Unit,
    storageLabel: String,
    onOpen: (String) -> Unit
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    val platforms = sdkReport.platforms.map { name ->
        val n = name.removePrefix("android-")
        if (n.all { it.isDigit() } && n.isNotEmpty()) "Android $n (API $n)" else name
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DsTopBar(title = "Toolchains", leadingIcon = Icons.Default.ArrowBack, onLeading = onBack, onBell = onBell)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DsTabs(listOf("SDK", "NDK", "JDK", "Gradle"), tab, { tab = it })

            val sdkInstalled = platforms.isNotEmpty() || sdkReport.installedCount > 0
            when (tab) {
                0 -> {
                    ToolHeader("Android SDK", "Manage platforms, build-tools and system images.", Icons.Default.Android, sdkInstalled)
                    DsCard {
                        Text("Installed Platforms", color = Ds.OnBg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        if (platforms.isEmpty()) Text("No platform installed yet.", color = Ds.Muted, fontSize = 12.sp)
                        platforms.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Icon(Icons.Default.PhoneAndroid, null, tint = Ds.Muted, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(p, color = Ds.OnBg, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.CheckCircle, null, tint = Ds.Green, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    DsCard {
                        Text("Build tools", color = Ds.OnBg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        DsStatusRow("AAPT2", runtime.aapt2?.name.orEmpty(), runtime.aapt2?.exists() == true)
                        DsStatusRow("D8", runtime.d8?.name.orEmpty(), runtime.d8?.exists() == true)
                        DsStatusRow("R8", runtime.r8?.name.orEmpty(), runtime.r8?.exists() == true)
                        DsStatusRow("APK signer", runtime.apksigner?.name.orEmpty(), runtime.apksigner?.exists() == true)
                        DsStatusRow("Zipalign", runtime.zipalign?.name.orEmpty(), runtime.zipalign?.exists() == true)
                        DsStatusRow("android.jar", runtime.androidJar?.absolutePath.orEmpty(), runtime.androidJar?.isFile == true)
                        Text("Build Tools: ${sdkReport.buildTools.joinToString().ifBlank { "none" }}", color = Ds.Muted, fontSize = 11.sp)
                    }
                    DsButton("Install / Update SDK", Icons.Default.Download, onInstall, enabled = !busy)
                    Surface(color = Ds.Card, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Ds.Line), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, null, tint = Ds.Green, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("${platforms.size} platform(s) installed", color = Ds.Muted, fontSize = 12.sp)
                        }
                    }
                }
                1 -> {
                    ToolHeader("Android NDK", "CMake, Ninja and the NDK toolchain for native ARM64 builds.", Icons.Default.Memory, runtime.readyForNativeBuild)
                    DsCard {
                        DsStatusRow("NDK toolchain file", runtime.ndkToolchainFile?.absolutePath.orEmpty(), runtime.ndkToolchainFile?.exists() == true)
                        DsStatusRow("CMake", runtime.cmake?.absolutePath.orEmpty(), runtime.cmake?.isFile == true)
                        DsStatusRow("Ninja", runtime.ninja?.absolutePath.orEmpty(), runtime.ninja?.isFile == true)
                    }
                    DsButton("Install / Update NDK", Icons.Default.Download, onInstall, enabled = !busy)
                }
                2 -> {
                    ToolHeader("Java Development Kit", "ARM64 JDK 17 used to run Gradle on the device.", Icons.Default.Coffee, runtime.javaExecutable?.isFile == true)
                    DsCard {
                        DsStatusRow("ARM64 JDK", runtime.javaExecutable?.absolutePath.orEmpty(), runtime.javaExecutable?.isFile == true)
                        DsStatusRow("Executable permission", "", runtime.javaExecutable?.canExecute() == true)
                    }
                    DsButton("Install / Update JDK", Icons.Default.Download, onInstall, enabled = !busy)
                }
                else -> {
                    val v = runtime.gradleLauncher?.name?.removePrefix("gradle-launcher-")?.removeSuffix(".jar")
                    ToolHeader("Gradle", if (v != null) "Gradle $v launcher detected." else "Gradle distribution used to build projects.", Icons.Default.Extension, runtime.gradleLauncher != null)
                    DsCard {
                        DsStatusRow("Gradle launcher", runtime.gradleLauncher?.absolutePath.orEmpty(), runtime.gradleLauncher != null)
                        DsStatusRow("Ready for Gradle builds", "", runtime.readyForGradle)
                    }
                    DsButton("Install / Update Gradle", Icons.Default.Download, onInstall, enabled = !busy)
                }
            }

            DsCard {
                Text("Project storage", color = Ds.OnBg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(storageLabel, color = Ds.Muted, fontSize = 12.sp)
                Text("Projects are built from a private working copy and synchronized to the selected shared-storage folder. Choose Internal Storage root when Android allows it.", color = Ds.Muted, fontSize = 11.sp)
                DsButton("Choose Internal Storage folder", Icons.Default.FolderOpen, onChooseStorage, enabled = !busy)
            }
            DsCard {
                Text("A–Z version catalog", color = Ds.OnBg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Side-by-side version support is being prepared. Install only Android ARM64/Bionic-compatible host tools; desktop Linux binaries are not valid Android phone toolchains.", color = Ds.Muted, fontSize = 11.sp)
                val versionRows = listOf(
                    "JDK" to "8 · 11 · 17 · 21 · 25 (17 currently catalogued)",
                    "Gradle" to "6.9.x · 7.6.x · 8.5–8.14.x · 9.x (9.3.1 currently catalogued)",
                    "Android Gradle Plugin" to "7.x · 8.x · 9.x (must match Gradle/JDK)",
                    "Kotlin / KSP" to "Project-selected versions (compiler compatibility required)",
                    "Android SDK" to "Platforms · Build Tools · Platform Tools · Command-line Tools",
                    "Native" to "NDK · CMake · Ninja · LLDB (ARM64 host packages only)",
                    "Packaging" to "AAPT2 · D8 · R8 · apksigner · zipalign · bundletool",
                    "Other" to "Git · Maven · Ant · Python · Node.js (Android-compatible builds only)"
                )
                versionRows.forEach { (name, versions) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(name, color = Ds.OnBg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(versions, color = Ds.Muted, fontSize = 11.sp)
                    }
                }
                Text("Important: the catalog is an inventory/compatibility roadmap, not a claim that every listed release can currently be downloaded or run on Android ARM64.", color = Ds.Orange, fontSize = 11.sp)
            }

            if (progress != null) {
                val isError = progress.message.contains("failed", true) || progress.message.contains("error", true)
                val isOk = progress.message.contains("ready", true) || progress.message.contains("verified", true)
                DsCard {
                    Text(progress.message, color = if (isError) Ds.Red else if (isOk) Ds.Green else Ds.OnBg, fontSize = 12.sp)
                    if (!isError) {
                        progress.fraction?.let {
                            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Ds.Purple, trackColor = Ds.Line)
                        }
                        if (progress.totalBytes != null && progress.totalBytes > 0) {
                            Text(
                                Formatter.formatShortFileSize(context, progress.downloadedBytes) + " / " + Formatter.formatShortFileSize(context, progress.totalBytes),
                                color = Ds.Muted, fontSize = 11.sp
                            )
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = Ds.Purple, trackColor = Ds.Line)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DsButton("Verify", Icons.Default.VerifiedUser, onVerify, Modifier.weight(1f), primary = false, enabled = !busy)
                DsButton("Refresh", Icons.Default.Refresh, onRefresh, Modifier.weight(1f), primary = false)
                DsButton("Local ZIP", Icons.Default.FolderOpen, onInstallLocal, Modifier.weight(1f), primary = false)
            }

            DsCard {
                Text("One-click installer contents", color = Ds.OnBg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                ToolchainCatalog.components.forEach { item ->
                    Text("• ${item.title} · ${Formatter.formatShortFileSize(context, item.sizeBytes)}", color = Ds.Muted, fontSize = 12.sp)
                }
                Text("Each file is verified with SHA-256 before it is installed.", color = Ds.Muted, fontSize = 11.sp)
            }
            DsCard {
                Text("Sources", color = Ds.OnBg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                SourceRow("AndroidIDE ARM64 releases", "https://github.com/AndroidIDEOfficial/androidide-tools/releases", onOpen)
                SourceRow("Gradle releases", "https://github.com/gradle/gradle/releases", onOpen)
                SourceRow("Temurin JDK 17 (cloud runners)", "https://github.com/adoptium/temurin17-binaries/releases", onOpen)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SourceRow(title: String, url: String, onOpen: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpen(url) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Ds.OnBg, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Default.OpenInNew, null, tint = Ds.PurpleSoft, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ToolHeader(title: String, desc: String, icon: androidx.compose.ui.graphics.vector.ImageVector, installed: Boolean) {
    DsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Ds.Green, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Ds.OnBg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(desc, color = Ds.Muted, fontSize = 12.sp)
                Surface(shape = RoundedCornerShape(8.dp), color = if (installed) Color(0xFF1B3A2A) else Color(0xFF3A2A1B)) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (installed) Icons.Default.CheckBox else Icons.Default.Warning, null, tint = if (installed) Ds.Green else Ds.Orange, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (installed) "Installed" else "Not installed", color = if (installed) Ds.Green else Ds.Orange, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/* ──────────────────────────── MORE / TOOLS ──────────────────────────── */

@Composable
fun MorePage(onMenu: () -> Unit, onBell: () -> Unit, onNavigate: (Screen) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DsTopBar(title = "Tools", onLeading = onMenu, onBell = onBell)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DsSettingsRow(Icons.Default.Memory, "Toolchains", "SDK, NDK, JDK, Gradle") { onNavigate(Screen.TOOLCHAINS) }
            DsSettingsRow(Icons.Default.Terminal, "Terminal", "Run shell commands in your project") { onNavigate(Screen.TERMINAL) }
            DsSettingsRow(Icons.Default.BugReport, "Logcat", "Live device and app logs") { onNavigate(Screen.LOGCAT) }
            DsSettingsRow(Icons.Default.CallSplit, "Git", "Status, commit, push, pull, clone") { onNavigate(Screen.GIT) }
            DsSettingsRow(Icons.Default.Speed, "Profiler", "CPU, memory and network") { onNavigate(Screen.PROFILER) }
            DsSettingsRow(Icons.Default.CloudQueue, "Cloud & Release", "Cloud builds and release checklist") { onNavigate(Screen.CLOUD) }
            DsSettingsRow(Icons.Default.Settings, "Settings", "Appearance, editor, build & run") { onNavigate(Screen.SETTINGS) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/* ───────────────────────────── SETTINGS ───────────────────────────── */

@Composable
fun SettingsScreen(onBack: () -> Unit, onBell: () -> Unit, onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var fontSize by remember { mutableStateOf(EditorPrefs.fontSize(context)) }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DsTopBar(title = "Settings", leadingIcon = Icons.Default.ArrowBack, onLeading = onBack, onBell = onBell)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DsSettingsRow(Icons.Default.Palette, "Appearance", "Dark theme, colors, editor font") { dialog = "appearance" }
            DsSettingsRow(Icons.Default.Edit, "Editor", "Font, tab size, auto-complete") { dialog = "editor" }
            DsSettingsRow(Icons.Default.Download, "Build & Run", "Gradle, JDK, signing, debug") { onNavigate(Screen.BUILD) }
            DsSettingsRow(Icons.Default.Build, "Toolchains", "SDK, NDK, JDK, build-tools") { onNavigate(Screen.TOOLCHAINS) }
            DsSettingsRow(Icons.Default.AccountCircle, "Account & Cloud", "GitHub, cloud build, backup") { onNavigate(Screen.CLOUD) }
            DsSettingsRow(Icons.Default.Info, "About", "Version $version") { dialog = "about" }
            Spacer(Modifier.height(12.dp))
        }
    }
    when (dialog) {
        "appearance" -> AlertDialog(
            onDismissRequest = { dialog = null }, containerColor = Ds.Card,
            title = { Text("Appearance", color = Ds.OnBg, fontWeight = FontWeight.Bold) },
            text = { Text("Mobile Studio X uses its dark violet theme everywhere. Editor font size can be changed from the Editor settings.", color = Ds.Muted) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK", color = Ds.PurpleSoft) } }
        )
        "editor" -> AlertDialog(
            onDismissRequest = { dialog = null }, containerColor = Ds.Card,
            title = { Text("Editor font size", color = Ds.OnBg, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Current: ${fontSize}sp", color = Ds.Muted)
                    DsTabs(listOf("11", "12", "13", "14", "16"), listOf(11, 12, 13, 14, 16).indexOf(fontSize).coerceAtLeast(0), {
                        fontSize = listOf(11, 12, 13, 14, 16)[it]
                        EditorPrefs.setFontSize(context, fontSize)
                    })
                    Text("Applies the next time the editor opens.", color = Ds.Muted, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done", color = Ds.PurpleSoft) } }
        )
        "about" -> AlertDialog(
            onDismissRequest = { dialog = null }, containerColor = Ds.Card,
            title = { Text("Mobile Studio X", color = Ds.OnBg, fontWeight = FontWeight.Bold) },
            text = { Text("Android Development Studio\nVersion $version", color = Ds.Muted) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK", color = Ds.PurpleSoft) } }
        )
    }
}

/* ───────────────────────────── PROFILER ───────────────────────────── */

private const val MAX_SAMPLES = 40

@Composable
fun ProfilerScreen(onBack: () -> Unit, onBell: () -> Unit) {
    var tab by remember { mutableStateOf(1) }
    val mem = remember { mutableStateListOf<Float>() }
    val cpu = remember { mutableStateListOf<Float>() }
    val net = remember { mutableStateListOf<Float>() }
    var native by remember { mutableStateOf(0) }
    var java by remember { mutableStateOf(0) }
    var graphics by remember { mutableStateOf(0) }
    var threads by remember { mutableStateOf(0) }
    var rxTotal by remember { mutableStateOf(0L) }
    var txTotal by remember { mutableStateOf(0L) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val cores = max(1, Runtime.getRuntime().availableProcessors())
        val uid = android.os.Process.myUid()
        var lastCpu = android.os.Process.getElapsedCpuTime()
        var lastReal = SystemClock.elapsedRealtime()
        var lastRx = TrafficStats.getUidRxBytes(uid)
        var lastTx = TrafficStats.getUidTxBytes(uid)
        while (true) {
            val snap = withContext(Dispatchers.Default) {
                val info = Debug.MemoryInfo()
                Debug.getMemoryInfo(info)
                fun stat(key: String): Int = (info.getMemoryStat(key)?.toIntOrNull() ?: 0) / 1024
                listOf(info.totalPss / 1024, stat("summary.native-heap"), stat("summary.java-heap"), stat("summary.graphics"), Thread.getAllStackTraces().size)
            }
            val nowCpu = android.os.Process.getElapsedCpuTime()
            val nowReal = SystemClock.elapsedRealtime()
            val dReal = max(1L, nowReal - lastReal)
            val cpuPct = ((nowCpu - lastCpu).toFloat() / dReal / cores * 100f).coerceIn(0f, 100f)
            lastCpu = nowCpu; lastReal = nowReal
            val rx = TrafficStats.getUidRxBytes(uid)
            val tx = TrafficStats.getUidTxBytes(uid)
            val validNet = rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong()
            val kbps = if (validNet) ((rx - lastRx) + (tx - lastTx)) / 1024f / (dReal / 1000f) else 0f
            if (validNet) { lastRx = rx; lastTx = tx; rxTotal = rx; txTotal = tx }

            mem.add(snap[0].toFloat()); if (mem.size > MAX_SAMPLES) mem.removeAt(0)
            cpu.add(cpuPct); if (cpu.size > MAX_SAMPLES) cpu.removeAt(0)
            net.add(kbps.coerceAtLeast(0f)); if (net.size > MAX_SAMPLES) net.removeAt(0)
            native = snap[1]; java = snap[2]; graphics = snap[3]; threads = snap[4]
            delay(1000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DsTopBar(title = "Profiler", leadingIcon = Icons.Default.ArrowBack, onLeading = onBack, onBell = onBell)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DsTabs(listOf("CPU", "Memory", "Network"), tab, { tab = it })
            when (tab) {
                0 -> {
                    val now = cpu.lastOrNull() ?: 0f
                    ChartCard("CPU Usage", "${now.toInt()} %", cpu.toList(), 100f, listOf("100 %", "75 %", "50 %", "25 %", "0 %"))
                    DsCard {
                        Text("Process", color = Ds.OnBg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        StatLine("Threads", "$threads")
                        StatLine("CPU cores", "${Runtime.getRuntime().availableProcessors()}")
                    }
                }
                1 -> {
                    val now = mem.lastOrNull() ?: 0f
                    val axisMax = max(64f, ceil(((mem.maxOrNull() ?: 64f) * 1.1f) / 64f) * 64f)
                    ChartCard("Memory Usage", "${now.toInt()} MB", mem.toList(), axisMax, (4 downTo 0).map { "${(axisMax * it / 4).toInt()} MB" })
                    DsCard {
                        Text("App Memory", color = Ds.OnBg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        StatLine("Native", "$native MB")
                        StatLine("Java (Dalvik)", "$java MB")
                        StatLine("Graphics", "$graphics MB")
                    }
                }
                else -> {
                    val now = net.lastOrNull() ?: 0f
                    val axisMax = max(16f, ceil(((net.maxOrNull() ?: 16f) * 1.1f) / 16f) * 16f)
                    ChartCard("Network Traffic", "${now.toInt()} KB/s", net.toList(), axisMax, (4 downTo 0).map { "${(axisMax * it / 4).toInt()} KB/s" })
                    DsCard {
                        Text("Since app start", color = Ds.OnBg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        StatLine("Received", Formatter.formatShortFileSize(context, rxTotal.coerceAtLeast(0L)))
                        StatLine("Sent", Formatter.formatShortFileSize(context, txTotal.coerceAtLeast(0L)))
                    }
                }
            }
            Text("Live metrics of the IDE's own process, sampled every second.", color = Ds.Muted, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ChartCard(title: String, value: String, values: List<Float>, axisMax: Float, labels: List<String>) {
    DsCard {
        Text(title, color = Ds.OnBg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Ds.OnBg, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
        Row(Modifier.fillMaxWidth().height(150.dp)) {
            Column(Modifier.fillMaxHeight().padding(end = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                labels.forEach { Text(it, color = Ds.Muted, fontSize = 9.sp) }
            }
            DsLineChart(values, axisMax, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Ds.OnBg, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Ds.OnBg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
