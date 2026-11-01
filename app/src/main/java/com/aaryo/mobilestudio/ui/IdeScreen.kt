package com.aaryo.mobilestudio.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable


import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aaryo.mobilestudio.data.TemplateType
import com.aaryo.mobilestudio.data.BuildRecord
import com.aaryo.mobilestudio.toolchain.ApkInspection
import com.aaryo.mobilestudio.toolchain.BuildDiagnostic
import com.aaryo.mobilestudio.toolchain.BuildState
import com.aaryo.mobilestudio.toolchain.DependencyReport
import com.aaryo.mobilestudio.toolchain.DownloadProgress
import com.aaryo.mobilestudio.toolchain.JdkInstallation
import com.aaryo.mobilestudio.toolchain.JdkVersion
import com.aaryo.mobilestudio.toolchain.ProjectBuildReport
import com.aaryo.mobilestudio.toolchain.RuntimeStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class Screen { HOME, PROJECTS, EDITOR, BUILD, MORE, TOOLCHAINS, TERMINAL, LOGCAT, GIT, PROFILER, SETTINGS, CLOUD }

private fun Screen.navTab(): NavTab = when (this) {
    Screen.HOME -> NavTab.HOME
    Screen.PROJECTS -> NavTab.PROJECTS
    Screen.EDITOR -> NavTab.EDITOR
    Screen.BUILD -> NavTab.BUILD
    else -> NavTab.MORE
}

@Composable
fun IdeScreen(vm: IdeViewModel) {
    val project by vm.project.collectAsState()
    val projects by vm.projects.collectAsState()
    val logs by vm.logs.collectAsState()
    val state by vm.state.collectAsState()
    val runtime by vm.runtime.collectAsState()
    val downloadProgress by vm.downloadProgress.collectAsState()
    val sdkReport by vm.sdkReport.collectAsState()
    val projectReport by vm.projectReport.collectAsState()
    val apkInspection by vm.apkInspection.collectAsState()
    val activeDiagnostic by vm.activeDiagnostic.collectAsState()
    val buildHistory by vm.buildHistory.collectAsState()
    val dependencyReport by vm.dependencyReport.collectAsState()
    val keystores by vm.keystores.collectAsState()
    val jdkInstallations by vm.jdkInstallations.collectAsState()
    val activeJdk by vm.activeJdk.collectAsState()
    val compilerMode by vm.compilerMode.collectAsState()

    val stack = remember { mutableStateListOf(Screen.HOME) }
    val current = stack.last()
    var showCreate by remember { mutableStateOf(false) }
    var preselected by remember { mutableStateOf<TemplateType?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var showToolchainPrompt by rememberSaveable { mutableStateOf(!runtime.readyForGradle) }
    val uriHandler = LocalUriHandler.current

    fun go(s: Screen) { if (stack.last() != s) stack.add(s) }
    fun goTab(s: Screen) {
        stack.clear(); stack.add(Screen.HOME)
        if (s != Screen.HOME) stack.add(s)
    }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { back() }

    val zipMimeTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip", "application/octet-stream", "*/*")
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { vm.importProject(uri); goTab(Screen.PROJECTS) }
    }
    val publicStoragePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::connectPublicStorage)
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.importProjectZip(uri); goTab(Screen.PROJECTS) }
    }
    val runtimePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.installRuntime(uri)
    }

    if (showCreate) {
        CreateProjectDialog(
            initialType = preselected ?: TemplateType.COMPOSE,
            onDismiss = { showCreate = false; preselected = null },
            onCreate = { type, name, pkg ->
                vm.createProjectFromTemplate(type, name, pkg)
                goTab(Screen.PROJECTS)
            }
        )
    }
    if (showAddMenu) {
        AlertDialog(
            onDismissRequest = { showAddMenu = false },
            containerColor = Ds.Card,
            title = { Text("Add project", color = Ds.OnBg, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DsSettingsRow(Icons.Default.AutoAwesome, "New from template", "Compose, Views, C++ or Quick app") { showAddMenu = false; showCreate = true }
                    DsSettingsRow(Icons.Default.FolderOpen, "Import folder", "Copy a project folder into workspace") { showAddMenu = false; folderPicker.launch(null) }
                    DsSettingsRow(Icons.Default.Archive, "Import ZIP", "Extract a .zip project archive") { showAddMenu = false; zipPicker.launch(zipMimeTypes) }
                }
            },
            confirmButton = { TextButton(onClick = { showAddMenu = false }) { Text("Close", color = Ds.PurpleSoft) } }
        )
    }
    if (showNotifications) {
        AlertDialog(
            onDismissRequest = { showNotifications = false },
            containerColor = Ds.Card,
            title = { Text("Notifications", color = Ds.OnBg, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (logs.isEmpty()) Text("Nothing new.", color = Ds.Muted)
                    logs.takeLast(8).forEach { Text(it, color = Ds.OnBg, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                }
            },
            confirmButton = { TextButton(onClick = { showNotifications = false }) { Text("OK", color = Ds.PurpleSoft) } }
        )
    }
    if (showToolchainPrompt) {
        ToolchainDownloadPromptDialog(
            runtime = runtime,
            downloadProgress = downloadProgress,
            busy = state is BuildState.Running,
            onDismiss = { showToolchainPrompt = false },
            onDownload = { vm.downloadAndInstallAll() },
            onOpenToolchains = {
                showToolchainPrompt = false
                go(Screen.TOOLCHAINS)
            }
        )
    }

    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Column(Modifier.fillMaxSize().background(Ds.Bg).statusBarsPadding().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (current) {
                Screen.HOME -> HomePage(
                    projects = projects,
                    runtime = runtime,
                    onMenu = { go(Screen.SETTINGS) },
                    onBell = { showNotifications = true },
                    onNew = { preselected = null; showCreate = true },
                    onOpen = { folderPicker.launch(null) },
                    onTile = { s -> if (s == Screen.EDITOR || s == Screen.BUILD) goTab(s) else go(s) },
                    onRecent = { vm.selectProject(it); goTab(Screen.EDITOR) },
                    onToolchainsClick = { showToolchainPrompt = true }
                )
                Screen.PROJECTS -> ProjectsPage(
                    projects = projects,
                    activeProject = project,
                    onMenu = { go(Screen.SETTINGS) },
                    onBell = { showNotifications = true },
                    onSelect = { vm.selectProject(it); goTab(Screen.EDITOR) },
                    onBuild = { vm.selectProject(it); goTab(Screen.BUILD) },
                    onDelete = vm::deleteProject,
                    onFab = { showAddMenu = true }
                )
                Screen.EDITOR -> CodeEditorScreen(
                    projectDir = project,
                    onBack = { back() },
                    onRun = { vm.buildDebug(); goTab(Screen.BUILD) },
                    onProjectSaved = vm::syncActiveProjectToPublicStorage
                )
                Screen.BUILD -> BuildPage(
                    project = project,
                    state = state,
                    logs = logs,
                    runtime = runtime,
                    projectReport = projectReport,
                    apkInspection = apkInspection,
                    activeDiagnostic = activeDiagnostic,
                    buildHistory = buildHistory,
                    dependencyReport = dependencyReport,
                    keystores = keystores,
                    jdkInstallations = jdkInstallations,
                    activeJdk = activeJdk,
                    onBack = { back() },
                    onBell = { showNotifications = true },
                    onBuild = vm::buildDebug,
                    onReleaseApk = vm::buildReleaseApk,
                    onReleaseBundle = vm::buildReleaseBundle,
                    onClean = vm::cleanProject,
                    onTests = vm::runUnitTests,
                    onLint = vm::runLint,
                    onCancel = vm::cancelBuild,
                    onInspect = vm::inspectProject,
                    onAapt = vm::buildStandaloneResources,
                    onNative = vm::buildNativeArm64,
                    onPickProject = { goTab(Screen.PROJECTS) },
                    onSetJdk = vm::setProjectJdk,
                    onInstallApk = vm::installGeneratedApk,
                    onShareApk = vm::shareGeneratedApk,
                    onCreateKeystore = vm::createKeystore,
                    onInspectDeps = vm::inspectProjectDependencies,
                    compilerMode = compilerMode,
                    onSetCompilerMode = vm::setCompilerMode
                )
                Screen.MORE -> MorePage(
                    onMenu = { go(Screen.SETTINGS) },
                    onBell = { showNotifications = true },
                    onNavigate = { go(it) }
                )
                Screen.TOOLCHAINS -> ToolchainsScreen(
                    runtime = runtime,
                    sdkReport = sdkReport,
                    progress = downloadProgress,
                    busy = state is BuildState.Running,
                    onBack = { back() },
                    onBell = { showNotifications = true },
                    onInstall = vm::downloadAndInstallAll,
                    onRefresh = vm::refreshRuntime,
                    onVerify = vm::verifyToolchains,
                    onInstallLocal = { runtimePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onChooseStorage = { publicStoragePicker.launch(null) },
                    storageLabel = vm.publicStorageLabel(),
                    onOpen = { uri -> runCatching { uriHandler.openUri(uri) } }
                )
                Screen.TERMINAL -> TerminalScreen(projectDir = project, onBack = { back() })
                Screen.LOGCAT -> LogcatScreen(onBack = { back() }, onBell = { showNotifications = true })
                Screen.GIT -> GitScreen(projectDir = project, onBack = { back() }, onBell = { showNotifications = true })
                Screen.PROFILER -> ProfilerScreen(onBack = { back() }, onBell = { showNotifications = true })
                Screen.SETTINGS -> SettingsScreen(
                    onBack = { back() },
                    onBell = { showNotifications = true },
                    onNavigate = { go(it) }
                )
                Screen.CLOUD -> CloudProductionScreen(projectDir = project, onBack = { back() })
            }
        }
        if (!imeVisible) {
            DsBottomNav(
                selected = current.navTab(),
                moreIsTools = current == Screen.TOOLCHAINS || current == Screen.GIT || current == Screen.PROFILER,
                onSelect = { tab ->
                    when (tab) {
                        NavTab.HOME -> goTab(Screen.HOME)
                        NavTab.PROJECTS -> goTab(Screen.PROJECTS)
                        NavTab.EDITOR -> goTab(Screen.EDITOR)
                        NavTab.BUILD -> goTab(Screen.BUILD)
                        NavTab.MORE -> goTab(Screen.MORE)
                    }
                }
            )
        }
    }
}

/* ───────────────────────────── HOME ───────────────────────────── */

private data class Tile(val label: String, val icon: ImageVector, val tint: Color, val screen: Screen)

@Composable
private fun HomePage(
    projects: List<File>,
    runtime: RuntimeStatus,
    onMenu: () -> Unit,
    onBell: () -> Unit,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onTile: (Screen) -> Unit,
    onRecent: (File) -> Unit,
    onToolchainsClick: () -> Unit
) {
    val tiles = listOf(
        Tile("Editor", Icons.Default.Speed, Ds.Cyan, Screen.EDITOR),
        Tile("Build", Icons.Default.Extension, Color(0xFF9B6BFF), Screen.BUILD),
        Tile("Terminal", Icons.Default.Terminal, Color(0xFFB56CFF), Screen.TERMINAL),
        Tile("SDK", Icons.Default.Android, Color(0xFF3CD9C0), Screen.TOOLCHAINS),
        Tile("Git", Icons.Default.CallSplit, Color(0xFFFF6B5E), Screen.GIT),
        Tile("Tools", Icons.Default.Build, Color(0xFF8FA3C8), Screen.MORE)
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DsTopBar(title = "Mobile Studio X", subtitle = "Android Development Studio", onLeading = onMenu, onBell = onBell)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!runtime.readyForGradle) {
                Surface(
                    onClick = onToolchainsClick,
                    color = Color(0xFF1E1735),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(Ds.Purple, Ds.Cyan))),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x337440EB)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Download, null, tint = Ds.PurpleSoft, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Setup Build Toolchains", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Tap to download JDK 17, Gradle & Android SDK build tools.", color = Ds.Muted, fontSize = 11.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Ds.PurpleSoft, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroCard("New Project", "Create Android app", Icons.Default.NoteAdd,
                    Brush.linearGradient(listOf(Color(0xFF4C7DF5), Color(0xFF6A4DF0))), onNew, Modifier.weight(1f))
                HeroCard("Open Project", "Browse existing project", Icons.Default.FolderOpen,
                    Brush.linearGradient(listOf(Color(0xFF6A3BD1), Color(0xFF4B2A9E))), onOpen, Modifier.weight(1f))
            }
            tiles.chunked(3).forEach { rowTiles ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowTiles.forEach { t ->
                        Surface(
                            onClick = { onTile(t.screen) },
                            color = Ds.Card,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Ds.Line),
                            modifier = Modifier.weight(1f).height(78.dp)
                        ) {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(t.icon, null, tint = t.tint, modifier = Modifier.size(26.dp))
                                Spacer(Modifier.height(6.dp))
                                Text(t.label, color = Ds.OnBg, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            Text("Recent Projects", color = Ds.OnBg, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            if (projects.isEmpty()) {
                DsCard { Text("No projects yet. Tap New Project to create one from a template.", color = Ds.Muted, fontSize = 13.sp) }
            }
            projects.sortedByDescending { it.lastModified() }.take(3).forEach { p ->
                Surface(
                    onClick = { onRecent(p) },
                    color = Ds.Card,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Ds.Line),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF3A2C78)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Android, null, tint = Ds.PurpleSoft, modifier = Modifier.size(20.dp)) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = Ds.OnBg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("/${p.parentFile?.name ?: "Projects"}/${p.name}", color = Ds.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Ds.Muted)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun HeroCard(title: String, subtitle: String, icon: ImageVector, brush: Brush, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.height(108.dp).clip(RoundedCornerShape(18.dp)).background(brush).clickable(onClick = onClick).padding(14.dp)
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
            Column {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color(0xCCFFFFFF), fontSize = 11.sp)
            }
        }
    }
}

/* ─────────────────────────── PROJECTS ─────────────────────────── */

private fun formatEdited(ms: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance()
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val today = same(now, then)
    now.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = same(now, then)
    return when {
        today -> "Today, $time"
        yesterday -> "Yesterday, $time"
        else -> SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ms))
    }
}

@Composable
private fun ProjectsPage(
    projects: List<File>,
    activeProject: File?,
    onMenu: () -> Unit,
    onBell: () -> Unit,
    onSelect: (File) -> Unit,
    onBuild: (File) -> Unit,
    onDelete: (File) -> Unit,
    onFab: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    val filtered = remember(projects, query) {
        projects.filter { it.name.contains(query, ignoreCase = true) }.sortedByDescending { it.lastModified() }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DsTopBar(title = "Projects", onLeading = onMenu, onBell = onBell)
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search projects...", color = Ds.Muted) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Ds.Muted) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Ds.Field, unfocusedContainerColor = Ds.Field,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Ds.OnBg, unfocusedTextColor = Ds.OnBg, cursorColor = Ds.PurpleSoft
                ),
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
            )
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (filtered.isEmpty()) {
                    item {
                        DsCard {
                            Text(if (projects.isEmpty()) "No projects yet" else "No matching projects", color = Ds.OnBg, fontWeight = FontWeight.SemiBold)
                            Text("Tap + to create a project from a template or import a folder / ZIP.", color = Ds.Muted, fontSize = 12.sp)
                        }
                    }
                }
                items(filtered, key = { it.absolutePath }) { p ->
                    val active = activeProject?.absolutePath == p.absolutePath
                    Surface(
                        onClick = { onSelect(p) },
                        color = Ds.Card,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (active) Ds.Purple else Ds.Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = Ds.Blue, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name, color = Ds.OnBg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (active) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF1B3A2A)) {
                                            Text("ACTIVE", color = Ds.Green, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                                Text("Last edited: ${formatEdited(p.lastModified())}", color = Ds.Muted, fontSize = 12.sp)
                            }
                            Box {
                                IconButton(onClick = { menuFor = p.absolutePath }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.MoreVert, "Project menu", tint = Ds.Muted)
                                }
                                DropdownMenu(expanded = menuFor == p.absolutePath, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(text = { Text("Open in editor") }, onClick = { menuFor = null; onSelect(p) })
                                    DropdownMenuItem(text = { Text("Build APK") }, onClick = { menuFor = null; onBuild(p) })
                                    DropdownMenuItem(text = { Text("Delete", color = Ds.Red) }, onClick = { menuFor = null; deleteTarget = p })
                                }
                            }
                        }
                    }
                }
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp).size(58.dp).clip(CircleShape)
                .background(Ds.Purple).clickable(onClick = onFab),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Add, "New project", tint = Color.White, modifier = Modifier.size(28.dp)) }
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = Ds.Card,
            title = { Text("Delete project?", color = Ds.OnBg, fontWeight = FontWeight.Bold) },
            text = { Text("\"${target.name}\" and all of its files will be removed from the workspace.", color = Ds.Muted) },
            confirmButton = { TextButton(onClick = { onDelete(target); deleteTarget = null }) { Text("Delete", color = Ds.Red) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = Ds.Muted) } }
        )
    }
}

/* ───────────────────────────── BUILD ───────────────────────────── */

@Composable
private fun BuildPage(
    project: File?,
    state: BuildState,
    logs: List<String>,
    runtime: RuntimeStatus,
    projectReport: ProjectBuildReport?,
    apkInspection: ApkInspection?,
    activeDiagnostic: BuildDiagnostic?,
    buildHistory: List<BuildRecord>,
    dependencyReport: DependencyReport?,
    keystores: List<File>,
    jdkInstallations: List<JdkInstallation>,
    activeJdk: JdkInstallation,
    onBack: () -> Unit,
    onBell: () -> Unit,
    onBuild: () -> Unit,
    onReleaseApk: () -> Unit,
    onReleaseBundle: () -> Unit,
    onClean: () -> Unit,
    onTests: () -> Unit,
    onLint: () -> Unit,
    onCancel: () -> Unit,
    onInspect: () -> Unit,
    onAapt: () -> Unit,
    onNative: () -> Unit,
    onPickProject: () -> Unit,
    onSetJdk: (JdkVersion) -> Unit,
    onInstallApk: (File) -> Unit,
    onShareApk: (File) -> Unit,
    onCreateKeystore: (String, String, String, (Boolean) -> Unit) -> Unit,
    onInspectDeps: () -> Unit,
    compilerMode: com.aaryo.mobilestudio.toolchain.CompilerMode = com.aaryo.mobilestudio.toolchain.CompilerMode.DIRECT,
    onSetCompilerMode: (com.aaryo.mobilestudio.toolchain.CompilerMode) -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val running = state is BuildState.Running

    var selectedTab by remember { mutableStateOf(if (state is BuildState.Failed && activeDiagnostic != null) 1 else 0) }
    var showJdkDialog by remember { mutableStateOf(false) }
    var showCreateKeystoreDialog by remember { mutableStateOf(false) }

    val tabTitles = listOf("Dashboard", "Diagnostics", "Signing", "Deps", "Artifacts", "History")
    val tabIcons = listOf(
        Icons.Default.Build,
        Icons.Default.BugReport,
        Icons.Default.Key,
        Icons.Default.Layers,
        Icons.Default.Inventory2,
        Icons.Default.History
    )

    val foundArtifacts = remember(project, state) {
        val list = mutableListOf<File>()
        project?.let { p ->
            val apkDir = File(p, "app/build/outputs/apk")
            if (apkDir.exists()) {
                apkDir.walkTopDown()
                    .filter { it.isFile && (it.name.endsWith(".apk") || it.name.endsWith(".aab")) }
                    .forEach { list.add(it) }
            }
            val bundleDir = File(p, "app/build/outputs/bundle")
            if (bundleDir.exists()) {
                bundleDir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".aab") }
                    .forEach { list.add(it) }
            }
        }
        list.distinctBy { it.absolutePath }
    }

    Column(Modifier.fillMaxSize()) {
        DsTopBar(
            title = "Build Manager",
            subtitle = project?.name ?: "No project selected",
            leadingIcon = Icons.Default.ArrowBack,
            onLeading = onBack,
            onBell = onBell
        )

        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 12.dp,
            containerColor = Ds.CardAlt,
            contentColor = Ds.PurpleSoft
        ) {
            tabTitles.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                Tab(
                    selected = isSelected,
                    onClick = { selectedTab = index },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                title,
                                color = if (isSelected) Color.White else Ds.Muted,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp
                            )
                            if (index == 1 && activeDiagnostic != null) {
                                Spacer(Modifier.width(4.dp))
                                Box(
                                    Modifier.size(7.dp).clip(CircleShape).background(Ds.Red)
                                )
                            }
                        }
                    },
                    icon = {
                        Icon(
                            tabIcons[index],
                            contentDescription = title,
                            tint = if (isSelected) Ds.PurpleSoft else Ds.Muted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                0 -> BuildDashboardTab(
                    project = project,
                    state = state,
                    running = running,
                    logs = logs,
                    runtime = runtime,
                    projectReport = projectReport,
                    activeJdk = activeJdk,
                    onPickProject = onPickProject,
                    onOpenJdkDialog = { showJdkDialog = true },
                    onBuild = onBuild,
                    onReleaseApk = onReleaseApk,
                    onReleaseBundle = onReleaseBundle,
                    onClean = onClean,
                    onTests = onTests,
                    onLint = onLint,
                    onCancel = onCancel,
                    onInspect = onInspect,
                    onAapt = onAapt,
                    onNative = onNative,
                    onViewDiagnostics = { selectedTab = 1 },
                    compilerMode = compilerMode,
                    onSetCompilerMode = onSetCompilerMode,
                    onInstallApk = onInstallApk,
                    onShareApk = onShareApk
                )
                1 -> BuildDiagnosticsTab(
                    diagnostic = activeDiagnostic,
                    state = state,
                    onCopyDiagnostic = { text -> clipboard.setText(AnnotatedString(text)) }
                )
                2 -> BuildSigningTab(
                    keystores = keystores,
                    onCreateKeystoreClick = { showCreateKeystoreDialog = true }
                )
                3 -> BuildDependenciesTab(
                    report = dependencyReport,
                    onInspectDeps = onInspectDeps
                )
                4 -> BuildArtifactsTab(
                    artifacts = foundArtifacts,
                    apkInspection = apkInspection,
                    onInstallApk = onInstallApk,
                    onShareApk = onShareApk
                )
                5 -> BuildHistoryTab(
                    history = buildHistory
                )
            }
        }
    }

    if (showJdkDialog) {
        JdkSelectionDialog(
            installations = jdkInstallations,
            activeJdk = activeJdk,
            onDismiss = { showJdkDialog = false },
            onSelect = { version ->
                onSetJdk(version)
                showJdkDialog = false
            }
        )
    }

    if (showCreateKeystoreDialog) {
        CreateKeystoreDialog(
            onDismiss = { showCreateKeystoreDialog = false },
            onCreate = { name, alias, pass, cb ->
                onCreateKeystore(name, alias, pass) { success ->
                    cb(success)
                    if (success) showCreateKeystoreDialog = false
                }
            }
        )
    }
}

/* ───────────────────────── BUILD TABS ───────────────────────── */

@Composable
private fun BuildDashboardTab(
    project: File?,
    state: BuildState,
    running: Boolean,
    logs: List<String>,
    runtime: RuntimeStatus,
    projectReport: ProjectBuildReport?,
    activeJdk: JdkInstallation,
    onPickProject: () -> Unit,
    onOpenJdkDialog: () -> Unit,
    onBuild: () -> Unit,
    onReleaseApk: () -> Unit,
    onReleaseBundle: () -> Unit,
    onClean: () -> Unit,
    onTests: () -> Unit,
    onLint: () -> Unit,
    onCancel: () -> Unit,
    onInspect: () -> Unit,
    onAapt: () -> Unit,
    onNative: () -> Unit,
    onViewDiagnostics: () -> Unit,
    compilerMode: com.aaryo.mobilestudio.toolchain.CompilerMode = com.aaryo.mobilestudio.toolchain.CompilerMode.DIRECT,
    onSetCompilerMode: (com.aaryo.mobilestudio.toolchain.CompilerMode) -> Unit = {},
    onInstallApk: (File) -> Unit = {},
    onShareApk: (File) -> Unit = {}
) {
    val clipboard = LocalClipboardManager.current
    val gradleVersion = runtime.gradleLauncher?.name
        ?.removePrefix("gradle-launcher-")?.removeSuffix(".jar")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Project & JDK row
        Surface(
            color = Ds.Card,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Ds.Line),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2250)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Folder, null, tint = Ds.PurpleSoft, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        project?.name ?: "No project selected",
                        color = Ds.OnBg,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        project?.path ?: "Select a project to enable compiler tasks",
                        color = Ds.Muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = onOpenJdkDialog,
                    color = Ds.Field,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Ds.PurpleSoft.copy(alpha = 0.5f))
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(activeJdk.version.displayName, color = Ds.PurpleSoft, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowDropDown, null, tint = Ds.PurpleSoft, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Build State Hero
        val (headline, detail, robotTint) = when (state) {
            BuildState.Idle -> Triple(
                if (project == null) "Select a Project" else "Ready to Compile",
                if (project == null) "Create or open a project to enable build tasks" else "Production-ready Android compilation engine",
                Ds.Green
            )
            is BuildState.Running -> Triple("Compiling...", state.task, Ds.Cyan)
            is BuildState.Success -> Triple("Build Succeeded", state.output, Ds.Green)
            is BuildState.Failed -> Triple("Build Failed", state.reason, Ds.Red)
        }

        Surface(
            color = Ds.Card,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, if (state is BuildState.Failed) Ds.Red else Ds.Line),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    when (state) {
                        is BuildState.Success -> Icons.Default.CheckCircle
                        is BuildState.Failed -> Icons.Default.Error
                        is BuildState.Running -> Icons.Default.HourglassTop
                        else -> Icons.Default.Android
                    },
                    contentDescription = null,
                    tint = robotTint,
                    modifier = Modifier.size(48.dp)
                )
                Text(headline, color = Ds.OnBg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    detail,
                    color = Ds.Muted,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                if (running) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = Ds.Purple,
                        trackColor = Ds.Line
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(containerColor = Ds.Red),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Cancel Build", fontSize = 12.sp)
                    }
                } else if (state is BuildState.Failed) {
                    Button(
                        onClick = onViewDiagnostics,
                        colors = ButtonDefaults.buttonColors(containerColor = Ds.Red.copy(alpha = 0.2f)),
                        border = BorderStroke(1.dp, Ds.Red),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.BugReport, null, tint = Ds.Red, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("View Root Cause & Diagnostics", color = Ds.Red, fontSize = 12.sp)
                    }
                }
            }
        }

        // Compilation Engine Selector
        DsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bolt, null, tint = Ds.PurpleSoft, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Compilation Engine", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    compilerMode.badge,
                    color = Ds.PurpleSoft,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = compilerMode == com.aaryo.mobilestudio.toolchain.CompilerMode.DIRECT,
                    onClick = { onSetCompilerMode(com.aaryo.mobilestudio.toolchain.CompilerMode.DIRECT) },
                    label = { Text("⚡ Fast Direct (Built-in)", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = compilerMode == com.aaryo.mobilestudio.toolchain.CompilerMode.GRADLE,
                    onClick = { onSetCompilerMode(com.aaryo.mobilestudio.toolchain.CompilerMode.GRADLE) },
                    label = { Text("🐘 Gradle Daemon", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Immediate APK Action upon successful compilation
        if (state is BuildState.Success && state.output.endsWith(".apk")) {
            val apkFile = File(state.output)
            DsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(Ds.Green.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = Ds.Green, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Installable APK Generated", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${apkFile.name} (${apkFile.length() / 1024} KB)", color = Ds.Muted, fontSize = 11.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onInstallApk(apkFile) },
                        colors = ButtonDefaults.buttonColors(containerColor = Ds.Green),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.InstallMobile, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Install APK", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { onShareApk(apkFile) },
                        colors = ButtonDefaults.buttonColors(containerColor = Ds.Field),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, null, tint = Ds.OnBg, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share APK", color = Ds.OnBg, fontSize = 12.sp)
                    }
                }
            }
        }

        // Primary Build Actions
        if (project == null) {
            DsButton("Choose a Project", Icons.Default.Folder, onPickProject)
        } else {
            DsButton("Build Debug APK", Icons.Default.PlayArrow, onBuild, enabled = !running)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DsButton("Build Release APK", Icons.Default.Verified, onReleaseApk, Modifier.weight(1f), primary = false, enabled = !running)
                DsButton("Build AAB Bundle", Icons.Default.Inventory2, onReleaseBundle, Modifier.weight(1f), primary = false, enabled = !running)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DsButton("Clean Project", Icons.Default.DeleteSweep, onClean, Modifier.weight(1f), primary = false, enabled = !running)
                DsButton("Run Unit Tests", Icons.Default.Science, onTests, Modifier.weight(1f), primary = false, enabled = !running)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DsButton("Lint Check", Icons.Default.Analytics, onLint, Modifier.weight(1f), primary = false, enabled = !running)
                DsButton("AAPT2 Standalone", Icons.Default.Image, onAapt, Modifier.weight(1f), primary = false, enabled = !running)
            }
            DsButton("Native ARM64 / CMake", Icons.Default.Memory, onNative, primary = false, enabled = !running)
        }

        // Build Tools summary
        DsCard {
            Text("Toolchains & Runtime", color = Ds.OnBg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            DsStatusRow("Gradle Distribution", "Gradle ${gradleVersion ?: "9.3.1"}", runtime.gradleLauncher != null)
            DsStatusRow("JDK Runtime", if (activeJdk.isInstalled) activeJdk.homeDirectory.name else "Embedded OpenJDK 17", activeJdk.isInstalled)
            DsStatusRow("AAPT2 / D8 / apksigner", runtime.aapt2?.name ?: "Build Tools 35.0.0", runtime.aapt2?.exists() == true)
        }

        // Project Configuration
        projectReport?.let { r ->
            DsCard {
                Text("Project Metadata", color = Ds.OnBg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Modules: ${r.modules.joinToString()}", color = Ds.Muted, fontSize = 11.sp)
                Text("Namespace: ${r.namespace ?: "not detected"}", color = Ds.Muted, fontSize = 11.sp)
                Text("Application ID: ${r.applicationId ?: "not detected"}", color = Ds.Muted, fontSize = 11.sp)
                Text("compileSdk ${r.compileSdk ?: "?"} · targetSdk ${r.targetSdk ?: "?"} · minSdk ${r.minSdk ?: "?"}", color = Ds.Muted, fontSize = 11.sp)
                r.warnings.forEach { Text("• $it", color = Ds.Orange, fontSize = 11.sp) }
            }
        }

        // Live Console
        val allText = logs.joinToString("\n")
        val errText = logs.filter { l ->
            l.contains("error", true) || l.contains("fail", true) || l.contains("exception", true) ||
                l.contains("caused by", true) || l.contains("e: ", true)
        }.joinToString("\n")

        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("BUILD CONSOLE", color = Ds.Muted, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(errText.ifBlank { allText })) }, enabled = logs.isNotEmpty()) {
                Text("Copy errors", color = Ds.PurpleSoft, fontSize = 12.sp)
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(allText)) }, enabled = logs.isNotEmpty()) {
                Text("Copy all", color = Ds.PurpleSoft, fontSize = 12.sp)
            }
        }

        Surface(
            color = Ds.TerminalBg,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Ds.Line),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (logs.isEmpty()) {
                    Text("No build output. Trigger a build task to view live execution logs.", color = Ds.Muted, fontSize = 11.sp)
                } else {
                    logs.takeLast(300).forEach { line ->
                        val bad = line.contains("FAILED", true) || line.contains("error", true)
                        val good = line.contains("SUCCESS", true)
                        Text(
                            line,
                            color = when { bad -> Ds.Red; good -> Ds.Green; else -> Color(0xFFC9CFE0) },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BuildDiagnosticsTab(
    diagnostic: BuildDiagnostic?,
    state: BuildState,
    onCopyDiagnostic: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (diagnostic != null) {
            // Category Badge
            Surface(
                color = Ds.Red.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, Ds.Red),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.BugReport, null, tint = Ds.Red, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("DIAGNOSTIC DETECTED", color = Ds.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(diagnostic.category.displayName, color = Ds.OnBg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Location
            if (diagnostic.filePath != null) {
                DsCard {
                    Text("File Location", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "${diagnostic.filePath}${diagnostic.lineNumber?.let { ":$it" } ?: ""}",
                        color = Ds.Cyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }

            // Problem Analysis
            DsCard {
                Text("Problem Analysis", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(diagnostic.summary, color = Ds.Muted, fontSize = 13.sp)
            }

            // Root Cause
            DsCard {
                Text("Root Cause", color = Ds.Orange, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(diagnostic.rootCause, color = Ds.OnBg, fontSize = 12.sp)
            }

            // Recommended Fix
            DsCard {
                Text("Recommended Fix Strategy", color = Ds.Green, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(diagnostic.recommendedFix, color = Ds.OnBg, fontSize = 12.sp)
            }

            // Copy Report Button
            DsButton(
                text = "Copy Diagnostic Report",
                icon = Icons.Default.ContentCopy,
                onClick = {
                    val report = buildString {
                        appendLine("### Mobile Studio X Build Diagnostic")
                        appendLine("- Category: ${diagnostic.category.displayName}")
                        diagnostic.filePath?.let { appendLine("- Location: $it:${diagnostic.lineNumber ?: 0}") }
                        appendLine("- Summary: ${diagnostic.summary}")
                        appendLine("- Root Cause: ${diagnostic.rootCause}")
                        appendLine("- Fix Strategy: ${diagnostic.recommendedFix}")
                    }
                    onCopyDiagnostic(report)
                }
            )
        } else {
            DsCard {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = Ds.Green, modifier = Modifier.size(48.dp))
                    Text("No Build Diagnostics", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        if (state is BuildState.Failed)
                            "Build failed: ${state.reason}\n(No specific syntax/linker rule triggered. Inspect Build Console logs.)"
                        else
                            "Workspace is healthy. Trigger a build task to detect real-time compiler diagnostics.",
                        color = Ds.Muted,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BuildSigningTab(
    keystores: List<File>,
    onCreateKeystoreClick: () -> Unit
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DsCard {
            Text("APK & AAB Keystore Management", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "Mobile Studio X integrates the Android SDK apksigner engine. Generated keystores use 2048-bit RSA keys with SHA-256 digests, supporting V1 (JAR), V2 (Full APK), V3 (Key Rotation), and V4 (Streaming) signatures for Google Play submission.",
                color = Ds.Muted,
                fontSize = 12.sp
            )
        }

        DsButton(
            text = "Generate New Keystore",
            icon = Icons.Default.Key,
            onClick = onCreateKeystoreClick
        )

        Text("Available Keystores (${keystores.size})", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 14.sp)

        if (keystores.isEmpty()) {
            DsCard {
                Text("No custom keystores found in workspace. Tap 'Generate New Keystore' to create a production release keystore on your device.", color = Ds.Muted, fontSize = 12.sp)
            }
        } else {
            keystores.forEach { k ->
                DsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2E264E)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Key, null, tint = Ds.PurpleSoft, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(k.name, color = Ds.OnBg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                "${Formatter.formatShortFileSize(context, k.length())} · ${k.parent ?: ""}",
                                color = Ds.Muted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            color = Ds.Green.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Ready", color = Ds.Green, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BuildDependenciesTab(
    report: DependencyReport?,
    onInspectDeps: () -> Unit
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DsButton(
            text = "Analyze Project Dependencies",
            icon = Icons.Default.Layers,
            onClick = onInspectDeps
        )

        if (report != null) {
            DsCard {
                Text("Dependencies Overview", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Declared", color = Ds.Muted, fontSize = 11.sp)
                        Text("${report.dependencies.size} packages", color = Ds.OnBg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    Column {
                        Text("Repositories", color = Ds.Muted, fontSize = 11.sp)
                        Text("${report.repositories.size} declared", color = Ds.OnBg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    Column {
                        Text("Offline Cache", color = Ds.Muted, fontSize = 11.sp)
                        Text(
                            if (report.offlineCacheSizeBytes > 0) Formatter.formatShortFileSize(context, report.offlineCacheSizeBytes) else "None",
                            color = if (report.offlineCacheSizeBytes > 0) Ds.Green else Ds.Orange,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            if (report.repositories.isNotEmpty()) {
                DsCard {
                    Text("Declared Repositories", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    report.repositories.forEach { repo ->
                        Text("• $repo", color = Ds.Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Text("Declared Dependencies (${report.dependencies.size})", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            report.dependencies.forEach { dep ->
                Surface(
                    color = Ds.Card,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Ds.Line),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${dep.group}:${dep.name}",
                                color = Ds.OnBg,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                            Text(
                                "version ${dep.version}",
                                color = Ds.Muted,
                                fontSize = 11.sp
                            )
                        }
                        Surface(
                            color = Ds.Field,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                dep.configuration,
                                color = Ds.PurpleSoft,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        } else {
            DsCard {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Layers, null, tint = Ds.PurpleSoft, modifier = Modifier.size(40.dp))
                    Text("No Dependency Report", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Tap 'Analyze Project Dependencies' to parse build.gradle.kts and libs.versions.toml.", color = Ds.Muted, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BuildArtifactsTab(
    artifacts: List<File>,
    apkInspection: ApkInspection?,
    onInstallApk: (File) -> Unit,
    onShareApk: (File) -> Unit
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Generated Artifacts (${artifacts.size})", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 15.sp)

        if (artifacts.isEmpty()) {
            DsCard {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Inventory2, null, tint = Ds.Muted, modifier = Modifier.size(40.dp))
                    Text("No Compiled Artifacts Found", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Run 'Build Debug APK' or 'Build Release APK' from the Dashboard tab to generate installable APKs.", color = Ds.Muted, fontSize = 12.sp)
                }
            }
        } else {
            artifacts.forEach { file ->
                val isApk = file.name.endsWith(".apk")
                DsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF263A28)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(if (isApk) Icons.Default.Android else Icons.Default.Inventory2, null, tint = Ds.Green, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.name, color = Ds.OnBg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                "${Formatter.formatShortFileSize(context, file.length())} · ${dateFormat.format(Date(file.lastModified()))}",
                                color = Ds.Muted,
                                fontSize = 11.sp
                            )
                        }
                    }
                    if (isApk) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onInstallApk(file) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Ds.Green),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.InstallMobile, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Install", fontSize = 12.sp)
                            }
                            Button(
                                onClick = { onShareApk(file) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Ds.Field),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Share, null, tint = Ds.OnBg, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share", color = Ds.OnBg, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        apkInspection?.let { apk ->
            DsCard {
                Text("APK Package Inspector", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("${apk.file.name} · ${Formatter.formatShortFileSize(context, apk.sizeBytes)}", color = Ds.Cyan, fontSize = 12.sp)
                DsStatusRow("AndroidManifest.xml", if (apk.hasManifest) "Valid" else "Missing", apk.hasManifest)
                DsStatusRow("APK Signature", if (apk.hasSignatureMetadata) "Signed (V1/V2/V3)" else "Unsigned", apk.hasSignatureMetadata)
                Text("DEX Files: ${apk.dexFiles.joinToString().ifBlank { "none" }}", color = Ds.Muted, fontSize = 11.sp)
                Text("Native ABIs: ${apk.nativeAbis.joinToString().ifBlank { "Universal (Java/Kotlin bytecode)" }}", color = Ds.Muted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BuildHistoryTab(
    history: List<BuildRecord>
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Build Execution History (${history.size})", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 15.sp)

        if (history.isEmpty()) {
            DsCard {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.History, null, tint = Ds.Muted, modifier = Modifier.size(40.dp))
                    Text("No History Yet", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Compilation and verification tasks will be logged here with duration and outcome.", color = Ds.Muted, fontSize = 12.sp)
                }
            }
        } else {
            history.forEach { record ->
                Surface(
                    color = Ds.Card,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, if (record.isSuccess) Ds.Line else Ds.Red.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (record.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                null,
                                tint = if (record.isSuccess) Ds.Green else Ds.Red,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(record.task, color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${record.durationMs / 1000}s",
                                color = Ds.PurpleSoft,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                dateFormat.format(Date(record.timestamp)),
                                color = Ds.Muted,
                                fontSize = 11.sp
                            )
                            if (record.errorCategory != null) {
                                Surface(
                                    color = Ds.Red.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        record.errorCategory,
                                        color = Ds.Red,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        if (record.artifactPath != null) {
                            Text(
                                "Output: ${File(record.artifactPath).name}",
                                color = Ds.Cyan,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/* ───────────────────────── DIALOGS ───────────────────────── */

@Composable
private fun JdkSelectionDialog(
    installations: List<JdkInstallation>,
    activeJdk: JdkInstallation,
    onDismiss: () -> Unit,
    onSelect: (JdkVersion) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ds.Card,
        title = { Text("Select Project JDK", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select the Java Development Kit distribution for project compilation:", color = Ds.Muted, fontSize = 12.sp)
                installations.forEach { inst ->
                    val isCurrent = inst.version == activeJdk.version
                    Surface(
                        onClick = { onSelect(inst.version) },
                        color = if (isCurrent) Ds.Field else Ds.CardAlt,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (isCurrent) Ds.Purple else Ds.Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Code,
                                null,
                                tint = if (isCurrent) Ds.PurpleSoft else Ds.Muted,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(inst.version.displayName, color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("${inst.version.minAgpRequirement} · Java ${inst.version.majorVersion}", color = Ds.Muted, fontSize = 11.sp)
                            }
                            if (isCurrent) {
                                Icon(Icons.Default.Check, null, tint = Ds.Green, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Ds.PurpleSoft)
            }
        }
    )
}

@Composable
private fun CreateKeystoreDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, (Boolean) -> Unit) -> Unit
) {
    var keystoreName by remember { mutableStateOf("release.keystore") }
    var alias by remember { mutableStateOf("mobilekey") }
    var password by remember { mutableStateOf("android123") }
    var creating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ds.Card,
        title = { Text("Generate Keystore", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Generates a 2048-bit RSA keystore on device for APK and AAB signing.", color = Ds.Muted, fontSize = 12.sp)
                TextField(
                    value = keystoreName,
                    onValueChange = { keystoreName = it },
                    label = { Text("Keystore Filename") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Ds.Field, unfocusedContainerColor = Ds.Field,
                        focusedTextColor = Ds.OnBg, unfocusedTextColor = Ds.OnBg
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Key Alias") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Ds.Field, unfocusedContainerColor = Ds.Field,
                        focusedTextColor = Ds.OnBg, unfocusedTextColor = Ds.OnBg
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Keystore Password") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Ds.Field, unfocusedContainerColor = Ds.Field,
                        focusedTextColor = Ds.OnBg, unfocusedTextColor = Ds.OnBg
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    creating = true
                    onCreate(keystoreName.trim(), alias.trim(), password.trim()) {
                        creating = false
                    }
                },
                enabled = !creating && keystoreName.isNotBlank() && alias.isNotBlank() && password.length >= 6,
                colors = ButtonDefaults.buttonColors(containerColor = Ds.Purple)
            ) {
                if (creating) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                } else {
                    Text("Generate")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !creating) {
                Text("Cancel", color = Ds.Muted)
            }
        }
    )
}

/* ────────────────────────── NEW PROJECT ────────────────────────── */

@Composable
private fun CreateProjectDialog(
    initialType: TemplateType,
    onDismiss: () -> Unit,
    onCreate: (TemplateType, String, String) -> Unit
) {
    var selectedType by remember { mutableStateOf(initialType) }
    var appName by remember { mutableStateOf(initialType.defaultAppPrefix) }
    var packageName by remember { mutableStateOf(initialType.defaultPackage) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ds.Card,
        title = { Text("New Project", color = Ds.OnBg, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose an app architecture. A complete, build-ready Android project is generated for you.", color = Ds.Muted, fontSize = 12.sp)
                TemplateType.values().forEach { template ->
                    val isSelected = selectedType == template
                    val icon = when (template) {
                        TemplateType.COMPOSE -> Icons.Default.Widgets
                        TemplateType.VIEWS -> Icons.Default.Dashboard
                        TemplateType.NATIVE_CPP -> Icons.Default.Memory
                        TemplateType.MINIMAL_AAPT -> Icons.Default.Bolt
                    }
                    Surface(
                        onClick = {
                            selectedType = template
                            if (appName.isBlank() || TemplateType.values().any { appName.startsWith(it.defaultAppPrefix) }) {
                                appName = template.defaultAppPrefix
                                packageName = template.defaultPackage
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) Ds.PurpleDeep else Ds.Field,
                        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, if (isSelected) Ds.Purple else Ds.Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if (isSelected) Ds.Purple else Ds.Line),
                                contentAlignment = Alignment.Center
                            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(template.title, color = Ds.OnBg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(template.subtitle, color = Ds.Cyan, fontSize = 11.sp)
                            }
                            if (isSelected) Icon(Icons.Default.CheckCircle, null, tint = Ds.PurpleSoft, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                OutlinedTextField(
                    value = appName,
                    onValueChange = {
                        appName = it
                        val safe = it.lowercase().filter { ch -> ch.isLetterOrDigit() }
                        packageName = "com.mobilestudio.${safe.ifBlank { "app" }}"
                    },
                    label = { Text("App name") }, singleLine = true, colors = dsFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = packageName, onValueChange = { packageName = it },
                    label = { Text("Package name") }, singleLine = true, colors = dsFieldColors(), modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (appName.isNotBlank() && packageName.isNotBlank()) {
                        onCreate(selectedType, appName, packageName)
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Ds.Purple, contentColor = Color.White),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Create Project") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Ds.Muted) } }
    )
}

@Composable
fun ToolchainDownloadPromptDialog(
    runtime: RuntimeStatus,
    downloadProgress: DownloadProgress?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onOpenToolchains: () -> Unit
) {
    val context = LocalContext.current
    val isInstalled = runtime.readyForGradle

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ds.Card,
        shape = RoundedCornerShape(22.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                if (isInstalled) listOf(Ds.Green, Color(0xFF26A69A))
                                else listOf(Ds.Purple, Ds.Cyan)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isInstalled) Icons.Default.CheckCircle else Icons.Default.Download,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isInstalled) "Toolchains Ready!" else "Download Toolchains",
                        color = Ds.OnBg,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = if (isInstalled) "Build environment configured" else "Mobile Studio X Setup",
                        color = Ds.Muted,
                        fontSize = 12.sp
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isInstalled) {
                    Text(
                        "All required toolchains (JDK 17, Gradle & Android SDK) are installed and ready. You can now build, run, and export Android applications directly on your device.",
                        color = Ds.OnBg,
                        fontSize = 13.sp
                    )
                } else if (busy || downloadProgress != null) {
                    Text(
                        "Downloading and installing build toolchains on your device...",
                        color = Ds.OnBg,
                        fontSize = 13.sp
                    )
                    downloadProgress?.let { prog ->
                        Spacer(Modifier.height(4.dp))
                        val pct = prog.fraction
                        if (pct != null) {
                            LinearProgressIndicator(
                                progress = { pct },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(CircleShape),
                                color = Ds.Purple,
                                trackColor = Ds.Line
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                val totalStr = prog.totalBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "?"
                                Text(
                                    "${Formatter.formatShortFileSize(context, prog.downloadedBytes)} / $totalStr",
                                    color = Ds.Muted,
                                    fontSize = 11.sp
                                )
                                Text(
                                    "${(pct * 100).toInt()}%",
                                    color = Ds.PurpleSoft,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape),
                                color = Ds.Purple,
                                trackColor = Ds.Line
                            )
                        }

                        if (prog.message.isNotBlank()) {
                            Text(
                                prog.message,
                                color = Ds.Cyan,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    Text(
                        "To compile and generate APKs on your device, Mobile Studio X requires the Android development toolchains:",
                        color = Ds.OnBg,
                        fontSize = 13.sp
                    )
                    Surface(
                        color = Ds.Field,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Ds.Line)
                    ) {
                        Column(
                            Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ToolchainPromptRow(Icons.Default.Code, "OpenJDK 17", "ARM64 Android-Bionic runtime")
                            ToolchainPromptRow(Icons.Default.Build, "Gradle Build Engine", "v9.3.1 automated compiler")
                            ToolchainPromptRow(Icons.Default.Android, "Android Build-Tools", "AAPT2, D8, R8, apksigner")
                            ToolchainPromptRow(Icons.Default.Layers, "Android Platform", "API 36 (android.jar)")
                        }
                    }
                    Text(
                        "• One-click setup (~280 MB compressed)\n• Verified with SHA-256 integrity checks",
                        color = Ds.Muted,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            if (isInstalled) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Ds.Purple)
                ) {
                    Text("Get Started")
                }
            } else if (busy || downloadProgress != null) {
                Button(
                    onClick = onOpenToolchains,
                    colors = ButtonDefaults.buttonColors(containerColor = Ds.Purple)
                ) {
                    Text("View in Toolchains")
                }
            } else {
                Button(
                    onClick = onDownload,
                    colors = ButtonDefaults.buttonColors(containerColor = Ds.Purple)
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Download Now")
                }
            }
        },
        dismissButton = {
            if (!isInstalled && !busy && downloadProgress == null) {
                Row {
                    TextButton(onClick = onOpenToolchains) {
                        Text("Manage", color = Ds.PurpleSoft)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onDismiss) {
                        Text("Later", color = Ds.Muted)
                    }
                }
            } else if (busy || downloadProgress != null) {
                TextButton(onClick = onDismiss) {
                    Text("Hide", color = Ds.Muted)
                }
            }
        }
    )
}

@Composable
private fun ToolchainPromptRow(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Ds.PurpleSoft, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = Ds.OnBg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        Text("· $subtitle", color = Ds.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

