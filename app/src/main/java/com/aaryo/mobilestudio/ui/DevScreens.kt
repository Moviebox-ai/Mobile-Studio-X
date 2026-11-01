package com.aaryo.mobilestudio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/* ───────────────────────────── TERMINAL ───────────────────────────── */

private enum class TermKind { CMD, OUT, ERR, OK }
private data class TermLine(val text: String, val kind: TermKind)

private fun classifyLine(l: String): TermKind = when {
    l.contains("BUILD SUCCESSFUL", true) -> TermKind.OK
    l.contains("error", true) || l.contains("FAILED") || l.contains("denied", true) ||
        l.contains("not found", true) || l.contains("No such file", true) -> TermKind.ERR
    else -> TermKind.OUT
}

@Composable
fun TerminalScreen(projectDir: File?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val home = remember { context.filesDir }
    var cwd by remember(projectDir) { mutableStateOf(projectDir ?: home) }
    val lines = remember { mutableStateListOf<TermLine>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var process by remember { mutableStateOf<Process?>(null) }
    var menu by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (lines.isEmpty()) {
            lines.add(TermLine("Mobile Studio X shell · /system/bin/sh", TermKind.OK))
            lines.add(TermLine("Commands run inside your project folder. This is not a full Linux environment.", TermKind.OUT))
        }
    }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }

    fun prompt(): String = cwd.absolutePath.replace(home.absolutePath, "~")

    fun submit() {
        val cmd = input.trim()
        if (cmd.isEmpty() || busy) return
        input = ""
        history.add(cmd)
        lines.add(TermLine("$ $cmd", TermKind.CMD))
        when {
            cmd == "clear" -> lines.clear()
            cmd == "cd" || cmd.startsWith("cd ") -> {
                val arg = cmd.removePrefix("cd").trim()
                val target = when {
                    arg.isEmpty() || arg == "~" -> projectDir ?: home
                    arg.startsWith("~/") -> File(home, arg.removePrefix("~/"))
                    arg.startsWith("/") -> File(arg)
                    else -> File(cwd, arg)
                }
                val canon = runCatching { target.canonicalFile }.getOrNull()
                if (canon != null && canon.isDirectory) cwd = canon
                else lines.add(TermLine("cd: no such directory: $arg", TermKind.ERR))
            }
            else -> {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd).directory(cwd).redirectErrorStream(true)
                            pb.environment()["HOME"] = home.absolutePath
                            val proc = pb.start()
                            process = proc
                            val killer = launch { delay(120_000); proc.destroyForcibly() }
                            try {
                                proc.inputStream.bufferedReader().use { r ->
                                    while (true) {
                                        val l = r.readLine() ?: break
                                        withContext(Dispatchers.Main) {
                                            lines.add(TermLine(l, classifyLine(l)))
                                            if (lines.size > 3000) repeat(500) { lines.removeAt(0) }
                                        }
                                    }
                                }
                                val code = proc.waitFor()
                                if (code != 0) withContext(Dispatchers.Main) { lines.add(TermLine("exit code $code", TermKind.ERR)) }
                            } finally {
                                killer.cancel()
                                proc.destroy()
                            }
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        lines.add(TermLine("${t.javaClass.simpleName}: ${t.message}", TermKind.ERR))
                    } finally {
                        process = null
                        busy = false
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        DsTopBar(
            title = "Terminal", leadingIcon = Icons.Default.ArrowBack, onLeading = onBack, showBell = false,
            actions = {
                if (busy) IconButton(onClick = { process?.destroyForcibly() }) { Icon(Icons.Default.Stop, "Stop", tint = Ds.Red) }
                IconButton(onClick = { lines.clear(); cwd = projectDir ?: home }) { Icon(Icons.Default.Add, "New session", tint = Ds.OnBg) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = Ds.OnBg) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Clear") }, onClick = { menu = false; lines.clear() })
                        DropdownMenuItem(text = { Text("Copy output") }, onClick = {
                            menu = false
                            clipboard.setText(AnnotatedString(lines.joinToString("\n") { it.text }))
                        })
                    }
                }
            }
        )
        Surface(color = Ds.Card, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Ds.Line), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Text("${prompt()} $", color = Ds.Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        }
        Spacer(Modifier.height(10.dp))
        Surface(color = Ds.TerminalBg, modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(lines.size) { i ->
                        val l = lines[i]
                        Text(
                            l.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                            fontWeight = if (l.kind == TermKind.CMD) FontWeight.Bold else FontWeight.Normal,
                            color = when (l.kind) {
                                TermKind.CMD -> Color.White
                                TermKind.OUT -> Color(0xFFD6DBEA)
                                TermKind.ERR -> Ds.Red
                                TermKind.OK -> Ds.Green
                            }
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$ ", color = Ds.Green, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    BasicTextField(
                        value = input, onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !busy,
                        textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                        cursorBrush = SolidColor(Ds.PurpleSoft),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        decorationBox = { inner ->
                            Box { if (input.isEmpty()) Text(if (busy) "running…" else "type a command", color = Ds.Muted, fontFamily = FontFamily.Monospace, fontSize = 14.sp); inner() }
                        }
                    )
                    IconButton(onClick = { history.lastOrNull()?.let { input = it } }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.ArrowUpward, "Last command", tint = Ds.Muted, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { submit() }, enabled = !busy && input.isNotBlank(), modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.PlayArrow, "Run", tint = Ds.PurpleSoft)
                    }
                }
            }
        }
    }
}

/* ───────────────────────────── LOGCAT ───────────────────────────── */

private data class LogLine(val time: String, val level: Char, val tag: String, val msg: String, val raw: String)

private val logRegex = Regex("""^(?:\d\d-\d\d\s+)?(\d\d:\d\d:\d\d\.\d+)\s+([VDIWEF])/(.*?)\(\s*\d+\):\s?(.*)$""")

private fun levelRank(c: Char): Int = when (c) { 'V' -> 0; 'D' -> 1; 'I' -> 2; 'W' -> 3; 'E', 'F' -> 4; else -> -1 }

private fun readLogcat(): List<LogLine> = runCatching {
    val proc = ProcessBuilder("logcat", "-d", "-t", "400", "-v", "time").redirectErrorStream(true).start()
    val text = proc.inputStream.bufferedReader().readText()
    proc.waitFor(5, TimeUnit.SECONDS)
    text.lines().filter { it.isNotBlank() && !it.startsWith("---------") }.map { raw ->
        val m = logRegex.find(raw)
        if (m != null) LogLine(m.groupValues[1], m.groupValues[2][0], m.groupValues[3].trim(), m.groupValues[4], raw)
        else LogLine("", ' ', "", raw, raw)
    }
}.getOrDefault(emptyList())

@Composable
fun LogcatScreen(onBack: () -> Unit, onBell: () -> Unit) {
    var paused by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(0) }
    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(paused) {
        while (!paused) {
            lines = withContext(Dispatchers.IO) { readLogcat() }
            delay(2000)
        }
    }
    val shown = remember(lines, filter) { lines.filter { filter == 0 || levelRank(it.level) >= filter } }
    LaunchedEffect(shown.size, paused) { if (!paused && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) }

    Column(Modifier.fillMaxSize()) {
        DsTopBar(title = "Logcat", leadingIcon = Icons.Default.ArrowBack, onLeading = onBack, onBell = onBell)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Debug", "Info", "Warn", "Error").forEachIndexed { i, label ->
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(if (filter == i) Ds.Purple else Ds.Field).clickable { filter = i }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text(label, color = if (filter == i) Color.White else Ds.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) {
                Text(
                    "No log lines visible.\nAndroid only lets an app read its own logs on recent versions.",
                    color = Ds.Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown.size) { i ->
                    val l = shown[i]
                    if (l.level == ' ') {
                        Text(l.raw, color = Ds.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    } else {
                        val c = when (l.level) {
                            'I' -> Ds.Green; 'D' -> Ds.Blue; 'W' -> Ds.Orange; 'E', 'F' -> Ds.Red; else -> Ds.Muted
                        }
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = Ds.Muted)) { append(l.time + "  ") }
                                withStyle(SpanStyle(color = c, fontWeight = FontWeight.Bold)) { append("${l.level} ") }
                                withStyle(SpanStyle(color = if (l.level == 'E' || l.level == 'F') Ds.Red else Ds.OnBg)) { append("${l.tag}: ${l.msg}") }
                            },
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DsButton("Clear", Icons.Default.Delete, onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { ProcessBuilder("logcat", "-c").start().waitFor(5, TimeUnit.SECONDS) } }
                    lines = emptyList()
                }
            }, modifier = Modifier.weight(1f), primary = false)
            DsButton(if (paused) "Resume" else "Pause", if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, onClick = { paused = !paused }, modifier = Modifier.weight(1f))
        }
    }
}

/* ─────────────────────────────── GIT ─────────────────────────────── */

private data class GitResult(val ok: Boolean, val out: String)
private data class GitCommit(val subject: String, val whenText: String, val author: String)

private suspend fun runGit(args: List<String>, dir: File?, cacheDir: File, timeoutSec: Long = 30): GitResult = withContext(Dispatchers.IO) {
    var tmp: File? = null
    try {
        val pb = ProcessBuilder(listOf("git") + args).redirectErrorStream(true)
        if (dir != null && dir.isDirectory) pb.directory(dir)
        pb.environment()["GIT_TERMINAL_PROMPT"] = "0"
        val f = File.createTempFile("git", ".log", cacheDir)
        tmp = f
        pb.redirectOutput(f)
        val p = pb.start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            GitResult(false, "Timed out after ${timeoutSec}s")
        } else {
            val text = f.readText().take(20000)
            GitResult(p.exitValue() == 0, text.ifBlank { "Done (exit code ${p.exitValue()})" })
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        GitResult(false, if (t is IOException) "git executable not found on this device.\n${t.message}" else "${t.javaClass.simpleName}: ${t.message}")
    } finally {
        tmp?.delete()
    }
}

@Composable
fun GitScreen(projectDir: File?, onBack: () -> Unit, onBell: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cache = context.cacheDir
    var available by remember { mutableStateOf<Boolean?>(null) }
    var isRepo by remember(projectDir) { mutableStateOf(false) }
    var branch by remember(projectDir) { mutableStateOf("") }
    var changes by remember(projectDir) { mutableStateOf(0) }
    var commits by remember(projectDir) { mutableStateOf<List<GitCommit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, GitResult>?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var textInput by remember { mutableStateOf("") }

    suspend fun refresh() {
        val v = runGit(listOf("--version"), null, cache, 10)
        available = v.ok
        if (!v.ok || projectDir == null) { isRepo = false; branch = ""; commits = emptyList(); changes = 0; return }
        val inside = runGit(listOf("rev-parse", "--is-inside-work-tree"), projectDir, cache, 10)
        isRepo = inside.ok && inside.out.trim() == "true"
        if (!isRepo) { branch = ""; commits = emptyList(); changes = 0; return }
        val b = runGit(listOf("rev-parse", "--abbrev-ref", "HEAD"), projectDir, cache, 10)
        branch = if (b.ok) b.out.trim() else "no commits yet"
        changes = runGit(listOf("status", "--short"), projectDir, cache, 15).out.lines().count { it.isNotBlank() }
        val log = runGit(listOf("log", "-n", "5", "--pretty=format:%s%x1f%cr%x1f%an"), projectDir, cache, 15)
        commits = if (log.ok) log.out.lines().filter { it.contains('\u001f') }.map {
            val parts = it.split('\u001f')
            GitCommit(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
        } else emptyList()
    }

    fun exec(title: String, args: List<String>, dir: File? = projectDir, timeout: Long = 60) {
        if (busy) return
        busy = true
        scope.launch {
            val r = runGit(args, dir, cache, timeout)
            result = title to r
            refresh()
            busy = false
        }
    }

    LaunchedEffect(projectDir) { busy = true; refresh(); busy = false }

    val ready = available == true && projectDir != null && isRepo

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DsTopBar(title = "Git", leadingIcon = Icons.Default.ArrowBack, onLeading = onBack, onBell = onBell)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (available == false) {
                DsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = Ds.Orange)
                        Spacer(Modifier.width(10.dp))
                        Text("Git is not installed", color = Ds.OnBg, fontWeight = FontWeight.Bold)
                    }
                    Text("Android does not ship a git binary. Add a compatible ARM64 git executable to the app PATH to enable these actions.", color = Ds.Muted, fontSize = 12.sp)
                }
            }
            DsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF16323A)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Android, null, tint = Ds.Cyan)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(projectDir?.name ?: "No project selected", color = Ds.OnBg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                projectDir == null -> "Open a project first"
                                available != true -> "git unavailable"
                                !isRepo -> "Not a git repository"
                                else -> "$branch  ·  ${if (changes == 0) "No changes" else "$changes change(s)"}"
                            },
                            color = Ds.Muted, fontSize = 12.sp
                        )
                    }
                    if (available == true && projectDir != null && !isRepo) {
                        TextButton(onClick = { exec("git init", listOf("init")) }) { Text("git init", color = Ds.PurpleSoft) }
                    }
                }
            }
            val actions = listOf(
                GitAction("Status", Icons.Default.Description, true, ready) { exec("Status", listOf("status", "-sb")) },
                GitAction("Commit", Icons.Default.Done, false, ready) { textInput = ""; dialog = "commit" },
                GitAction("Push", Icons.Default.Upload, false, ready) { exec("Push", listOf("push"), timeout = 120) },
                GitAction("Pull", Icons.Default.Download, false, ready) { exec("Pull", listOf("pull"), timeout = 120) },
                GitAction("Branch", Icons.Default.CallSplit, false, ready) { textInput = ""; dialog = "branch" },
                GitAction("Clone", Icons.Default.ContentCopy, false, available == true) { textInput = ""; dialog = "clone" }
            )
            actions.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { a ->
                        Surface(
                            onClick = a.onClick,
                            enabled = a.enabled && !busy,
                            color = if (a.primary) Ds.Purple else Ds.Card,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, if (a.primary) Ds.Purple else Ds.Line),
                            modifier = Modifier.weight(1f).height(76.dp)
                        ) {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(a.icon, null, tint = if (a.enabled) Color.White else Ds.Muted, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.height(6.dp))
                                Text(a.label, color = if (a.enabled) Color.White else Ds.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = Ds.Purple, trackColor = Ds.Line)

            Text("Recent Commits", color = Ds.OnBg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (commits.isEmpty()) Text("No commits to show.", color = Ds.Muted, fontSize = 12.sp)
            commits.forEach { c ->
                Surface(color = Ds.Card, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Ds.Line), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Ds.PurpleDeep), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Person, null, tint = Ds.PurpleSoft, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.subject, color = Ds.OnBg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${c.whenText}  ·  ${c.author}  ·  $branch", color = Ds.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    result?.let { (title, r) ->
        AlertDialog(
            onDismissRequest = { result = null }, containerColor = Ds.Card,
            title = { Text(title, color = if (r.ok) Ds.Green else Ds.Red, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(r.out, color = Ds.OnBg, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { result = null }) { Text("OK", color = Ds.PurpleSoft) } }
        )
    }
    when (dialog) {
        "commit" -> GitInputDialog("Commit all changes", "Commit message", textInput, { textInput = it }, "Commit", { dialog = null }) {
            dialog = null
            val msg = textInput.trim()
            if (msg.isNotEmpty() && !busy) {
                busy = true
                scope.launch {
                    val add = runGit(listOf("add", "-A"), projectDir, cache)
                    val r = if (add.ok) runGit(listOf("commit", "-m", msg), projectDir, cache) else add
                    result = "Commit" to r
                    refresh()
                    busy = false
                }
            }
        }
        "branch" -> GitInputDialog("New branch", "Branch name", textInput, { textInput = it }, "Create & switch", { dialog = null }) {
            dialog = null
            val name = textInput.trim()
            if (name.isNotEmpty()) exec("Branch", listOf("checkout", "-b", name))
        }
        "clone" -> GitInputDialog("Clone repository", "Repository URL (https://…)", textInput, { textInput = it }, "Clone", { dialog = null }) {
            dialog = null
            val url = textInput.trim()
            if (url.isNotEmpty()) {
                val target = projectDir?.parentFile ?: File(context.filesDir, "projects").also { it.mkdirs() }
                exec("Clone", listOf("clone", url), dir = target, timeout = 300)
            }
        }
    }
}

private class GitAction(val label: String, val icon: ImageVector, val primary: Boolean, val enabled: Boolean, val onClick: () -> Unit)

@Composable
private fun GitInputDialog(
    title: String, label: String, value: String, onValue: (String) -> Unit,
    confirmText: String, onDismiss: () -> Unit, onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ds.Card,
        title = { Text(title, color = Ds.OnBg, fontWeight = FontWeight.Bold) },
        text = { OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = true, colors = dsFieldColors(), modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = value.isNotBlank()) { Text(confirmText, color = Ds.PurpleSoft) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Ds.Muted) } }
    )
}
