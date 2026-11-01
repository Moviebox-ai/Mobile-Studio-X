package com.aaryo.mobilestudio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val supportedCodeExtensions = setOf(
    "kt", "kts", "java", "xml", "gradle", "properties", "json", "md", "txt",
    "cpp", "c", "h", "hpp", "js", "ts", "jsx", "tsx", "html", "css", "yml", "yaml", "pro"
)

private fun isLayoutXml(f: File) = f.extension.equals("xml", true) && f.parentFile?.name?.startsWith("layout") == true

@Composable
fun CodeEditorScreen(projectDir: File?, onBack: () -> Unit, onRun: () -> Unit, onProjectSaved: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fontSize = remember { EditorPrefs.fontSize(context) }
    var files by remember(projectDir) { mutableStateOf<List<File>>(emptyList()) }
    var openFiles by remember(projectDir) { mutableStateOf<List<File>>(emptyList()) }
    var activePath by remember(projectDir) { mutableStateOf<String?>(null) }
    var editorValue by remember(projectDir) { mutableStateOf(TextFieldValue("")) }
    var dirty by remember(projectDir) { mutableStateOf(false) }
    var loading by remember(projectDir) { mutableStateOf(false) }
    var viewMode by remember(projectDir) { mutableStateOf(2) } // 0 design · 1 split · 2 code
    var message by remember { mutableStateOf<String?>(null) }
    var showFiles by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var showGoToLine by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var lineNumberInput by remember { mutableStateOf("1") }
    var diagnostics by remember { mutableStateOf<List<String>>(emptyList()) }
    val verticalState = rememberScrollState()
    val horizontalState = rememberScrollState()

    LaunchedEffect(projectDir) {
        loading = true
        files = withContext(Dispatchers.IO) {
            val root = projectDir
            if (root == null) emptyList() else root.walkTopDown()
                .onEnter { dir -> dir.name !in setOf(".git", ".gradle", "build", ".idea", ".kotlin") }
                .filter { it.isFile && it.extension.lowercase() in supportedCodeExtensions }
                .take(1500).toList()
                .sortedBy { it.relativeTo(root).path.lowercase() }
        }
        loading = false
        val first = files.firstOrNull { it.name.startsWith("MainActivity") } ?: files.firstOrNull()
        if (first != null) {
            val content = withContext(Dispatchers.IO) { runCatching { first.readText() }.getOrDefault("") }
            activePath = first.absolutePath
            openFiles = listOf(first)
            editorValue = TextFieldValue(content)
            viewMode = if (isLayoutXml(first)) 0 else 2
            dirty = false
        }
    }

    fun openFile(file: File) {
        if (dirty && activePath != null && activePath != file.absolutePath) {
            message = "Save the current file before switching."
            return
        }
        if (activePath == file.absolutePath) { showFiles = false; return }
        scope.launch {
            loading = true
            val text = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrElse { "// Unable to read file: ${it.message}" } }
            activePath = file.absolutePath
            if (openFiles.none { it.absolutePath == file.absolutePath }) openFiles = openFiles + file
            editorValue = TextFieldValue(text)
            dirty = false
            diagnostics = emptyList()
            viewMode = if (isLayoutXml(file)) 0 else 2
            verticalState.scrollTo(0)
            loading = false
            showFiles = false
        }
    }

    fun saveCurrent(onDone: () -> Unit = {}) {
        val path = activePath ?: return
        val target = File(path)
        val text = editorValue.text
        scope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    require(target.isFile && target.canonicalPath.startsWith(projectDir?.canonicalPath.orEmpty() + File.separator)) {
                        "File is outside the active project."
                    }
                    val temp = File(target.parentFile, target.name + ".mobilestudio.tmp")
                    temp.writeText(text)
                    if (target.exists() && !target.delete()) error("Cannot replace original file.")
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                }
            }
            loading = false
            result.onSuccess {
                dirty = false
                diagnostics = inspectSource(text, target.extension)
                message = "Saved ${target.name}"
                onProjectSaved()
                onDone()
            }.onFailure { message = "Save failed: ${it.message}" }
        }
    }

    fun closeTab(file: File) {
        if (file.absolutePath == activePath && dirty) { message = "Save the file before closing it."; return }
        val remaining = openFiles.filter { it.absolutePath != file.absolutePath }
        openFiles = remaining
        if (file.absolutePath == activePath) {
            val next = remaining.lastOrNull()
            if (next != null) openFile(next) else { activePath = null; editorValue = TextFieldValue("") }
        }
    }

    fun insertText(s: String) {
        val sel = editorValue.selection
        val t = editorValue.text
        val start = minOf(sel.start, sel.end).coerceIn(0, t.length)
        val end = maxOf(sel.start, sel.end).coerceIn(0, t.length)
        editorValue = TextFieldValue(t.replaceRange(start, end, s), TextRange(start + s.length))
        dirty = true
    }

    fun insertWidget(snippet: String) {
        val t = editorValue.text
        val idx = t.lastIndexOf("</")
        val updated = if (idx >= 0) t.substring(0, idx) + snippet + "\n" + t.substring(idx) else t + "\n" + snippet
        editorValue = TextFieldValue(updated, TextRange(updated.length))
        dirty = true
    }

    val activeFile = files.firstOrNull { it.absolutePath == activePath }
    val showModes = activeFile != null && isLayoutXml(activeFile)

    val codeArea: @Composable () -> Unit = {
        Row(Modifier.fillMaxSize().verticalScroll(verticalState).padding(top = 10.dp, bottom = 18.dp)) {
            val lineCount = editorValue.text.count { it == '\n' } + 1
            Text(
                (1..lineCount).joinToString("\n") { it.toString() },
                color = Color(0xFF68748B), fontFamily = FontFamily.Monospace, fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.65f).sp,
                modifier = Modifier.width(44.dp).padding(end = 10.dp), textAlign = TextAlign.End
            )
            Box(Modifier.weight(1f).horizontalScroll(horizontalState)) {
                BasicTextField(
                    value = editorValue,
                    onValueChange = { new -> if (new.text != editorValue.text) dirty = true; editorValue = new },
                    modifier = Modifier.widthIn(min = 360.dp).heightIn(min = 520.dp).padding(end = 16.dp),
                    textStyle = TextStyle(color = Color(0xFFE5E9F2), fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize * 1.65f).sp),
                    cursorBrush = SolidColor(Ds.PurpleSoft),
                    visualTransformation = CodeSyntaxTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    decorationBox = { inner ->
                        Box {
                            if (editorValue.text.isEmpty()) Text("// Start writing code...", color = Color(0xFF68748B), fontFamily = FontFamily.Monospace, fontSize = fontSize.sp)
                            inner()
                        }
                    }
                )
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.ArrowBack, "Back", tint = Ds.OnBg) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showFiles = true }, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.FolderOpen, "Browse files", tint = Ds.Muted) }
                IconButton(onClick = { showFind = true }, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.Search, "Find and replace", tint = Ds.Muted) }
                IconButton(onClick = { showGoToLine = true }, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.FormatListNumbered, "Go to line", tint = Ds.Muted) }
                IconButton(onClick = { showDiagnostics = !showDiagnostics }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.BugReport, "Diagnostics", tint = if (showDiagnostics) Ds.PurpleSoft else Ds.Muted)
                }
                IconButton(onClick = { saveCurrent() }, enabled = dirty && !loading, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Save, "Save", tint = if (dirty) Ds.PurpleSoft else Ds.Muted.copy(alpha = 0.5f))
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Ds.Green).clickable(enabled = projectDir != null) {
                        if (dirty) saveCurrent { onRun() } else onRun()
                    },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.PlayArrow, "Run", tint = Color(0xFF06210F)) }
            }

            if (openFiles.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    openFiles.forEach { file ->
                        val selected = file.absolutePath == activePath
                        Surface(
                            onClick = { openFile(file) },
                            color = if (selected) Ds.PurpleDeep else Ds.Card,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (selected) Ds.Purple else Ds.Line)
                        ) {
                            Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    file.name + if (selected && dirty) " •" else "",
                                    color = if (selected) Color.White else Ds.Muted, fontSize = 12.sp, maxLines = 1
                                )
                                Icon(
                                    Icons.Default.Close, "Close tab", tint = Ds.Muted,
                                    modifier = Modifier.padding(start = 6.dp).size(16.dp).clickable { closeTab(file) }
                                )
                            }
                        }
                    }
                }
            }
            if (showModes) {
                DsTabs(listOf("Design", "Split", "Code"), viewMode, { viewMode = it }, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            } else {
                Spacer(Modifier.height(8.dp))
            }

            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                color = Ds.EditorBg, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Ds.Line)
            ) {
                when {
                    projectDir == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Select a project to start editing.", color = Ds.Muted)
                    }
                    loading && activePath == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Ds.Purple)
                    }
                    activePath == null -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Code, null, tint = Ds.PurpleSoft, modifier = Modifier.size(40.dp))
                            Text("No source file open", color = Ds.OnBg)
                            Text("Open a Kotlin, Java, XML or Gradle file.", color = Ds.Muted, fontSize = 12.sp)
                            DsButton("Browse project files", Icons.Default.FolderOpen, { showFiles = true }, Modifier.width(220.dp))
                        }
                    }
                    showModes && viewMode == 0 -> DesignerPane(editorValue.text, ::insertWidget, projectDir?.name.orEmpty(), Modifier.fillMaxSize())
                    showModes && viewMode == 1 -> Column(Modifier.fillMaxSize()) {
                        DesignerPane(editorValue.text, ::insertWidget, projectDir?.name.orEmpty(), Modifier.weight(1f).fillMaxWidth())
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Ds.Line))
                        Box(Modifier.weight(1f).fillMaxWidth()) { codeArea() }
                    }
                    else -> codeArea()
                }
            }

            if (showDiagnostics) {
                Surface(color = Ds.Card, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Ds.Line), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Basic source diagnostics (${diagnostics.size})", color = Ds.OnBg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        if (diagnostics.isEmpty()) Text("Save the file to run basic checks.", color = Ds.Muted, fontSize = 11.sp)
                        diagnostics.take(8).forEach { Text("• $it", color = Ds.Orange, fontSize = 11.sp) }
                    }
                }
            }

            if (activePath != null && !(showModes && viewMode == 0)) {
                val keys = listOf("TAB" to "    ", "{" to "{", "}" to "}", "(" to "(", ")" to ")", "[" to "[", "]" to "]", ";" to ";", "=" to "=", "\"" to "\"", "<" to "<", ">" to ">", "/" to "/", "!" to "!", "." to ".")
                Row(
                    Modifier.fillMaxWidth().background(Ds.Card).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    keys.forEachIndexed { i, (label, value) ->
                        Box(
                            Modifier.width(if (label == "TAB") 52.dp else 40.dp).height(36.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (i == 0) Ds.PurpleDeep else Ds.Field).clickable { insertText(value) },
                            contentAlignment = Alignment.Center
                        ) { Text(label, color = if (i == 0) Ds.PurpleSoft else Ds.OnBg, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }
        }

        message?.let { msg ->
            LaunchedEffect(msg) { kotlinx.coroutines.delay(2200); if (message == msg) message = null }
            Surface(
                color = Ds.CardAlt, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Ds.Line),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)
            ) { Text(msg, color = Ds.OnBg, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 12.sp) }
        }
    }

    if (showFiles) {
        AlertDialog(
            onDismissRequest = { showFiles = false },
            containerColor = Ds.Card,
            title = { Text("Project files", color = Ds.OnBg, fontWeight = FontWeight.Bold) },
            text = {
                if (files.isEmpty()) Text("No supported source files found.", color = Ds.Muted)
                else LazyFileList(files = files, projectDir = projectDir, onOpen = ::openFile)
            },
            confirmButton = { TextButton(onClick = { showFiles = false }) { Text("Close", color = Ds.PurpleSoft) } }
        )
    }
    if (showFind) {
        AlertDialog(
            onDismissRequest = { showFind = false },
            containerColor = Ds.Card,
            title = { Text("Find and replace", color = Ds.OnBg, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = findText, onValueChange = { findText = it }, label = { Text("Find") }, singleLine = true, colors = dsFieldColors())
                    OutlinedTextField(value = replaceText, onValueChange = { replaceText = it }, label = { Text("Replace with") }, singleLine = true, colors = dsFieldColors())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (findText.isNotEmpty()) {
                        val updated = editorValue.text.replace(findText, replaceText)
                        editorValue = TextFieldValue(updated, selection = TextRange(updated.length))
                        dirty = true
                    }
                    showFind = false
                }) { Text("Replace all", color = Ds.PurpleSoft) }
            },
            dismissButton = { TextButton(onClick = { showFind = false }) { Text("Cancel", color = Ds.Muted) } }
        )
    }
    if (showGoToLine) {
        AlertDialog(
            onDismissRequest = { showGoToLine = false },
            containerColor = Ds.Card,
            title = { Text("Go to line", color = Ds.OnBg, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = lineNumberInput, onValueChange = { lineNumberInput = it.filter(Char::isDigit) },
                    label = { Text("Line number") }, singleLine = true, colors = dsFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = (lineNumberInput.toIntOrNull() ?: 1).coerceAtLeast(1)
                    val index = editorValue.text.split('\n').take(target - 1).sumOf { it.length + 1 }.coerceAtMost(editorValue.text.length)
                    editorValue = editorValue.copy(selection = TextRange(index))
                    scope.launch { verticalState.scrollTo(((target - 1) * fontSize * 1.65f * context.resources.displayMetrics.scaledDensity).toInt()) }
                    showGoToLine = false
                }) { Text("Go", color = Ds.PurpleSoft) }
            },
            dismissButton = { TextButton(onClick = { showGoToLine = false }) { Text("Cancel", color = Ds.Muted) } }
        )
    }
}

/* ────────────────────────── DESIGNER ────────────────────────── */

private data class PaletteItem(val label: String, val icon: ImageVector, val snippet: String)

private val palette = listOf(
    PaletteItem("Text", Icons.Default.TextFields, "    <TextView\n        android:layout_width=\"wrap_content\"\n        android:layout_height=\"wrap_content\"\n        android:text=\"New text\" />"),
    PaletteItem("Button", Icons.Default.TouchApp, "    <Button\n        android:layout_width=\"wrap_content\"\n        android:layout_height=\"wrap_content\"\n        android:text=\"Button\" />"),
    PaletteItem("Image", Icons.Default.Image, "    <ImageView\n        android:layout_width=\"96dp\"\n        android:layout_height=\"96dp\"\n        android:contentDescription=\"image\"\n        android:src=\"@android:drawable/ic_menu_gallery\" />"),
    PaletteItem("Layout", Icons.Default.GridView, "    <LinearLayout\n        android:layout_width=\"match_parent\"\n        android:layout_height=\"wrap_content\"\n        android:orientation=\"horizontal\">\n    </LinearLayout>"),
    PaletteItem("Recycler", Icons.Default.ViewList, "    <androidx.recyclerview.widget.RecyclerView\n        android:layout_width=\"match_parent\"\n        android:layout_height=\"200dp\" />"),
    PaletteItem("Card", Icons.Default.CreditCard, "    <androidx.cardview.widget.CardView\n        android:layout_width=\"match_parent\"\n        android:layout_height=\"wrap_content\">\n    </androidx.cardview.widget.CardView>")
)

private data class PreviewWidget(val type: String, val text: String)

private fun parseWidgets(xml: String): List<PreviewWidget> {
    val textRe = Regex("android:text=\"([^\"]*)\"")
    val hintRe = Regex("android:hint=\"([^\"]*)\"")
    val list = Regex("<\\s*([A-Za-z][A-Za-z0-9_.]*)([^<>]*)>").findAll(xml).map { m ->
        val name = m.groupValues[1].substringAfterLast('.')
        val attrs = m.groupValues[2]
        val raw = textRe.find(attrs)?.groupValues?.get(1) ?: hintRe.find(attrs)?.groupValues?.get(1) ?: ""
        PreviewWidget(name, raw.removePrefix("@string/").replace('_', ' '))
    }.toList()
    return list.drop(1)
}

@Composable
private fun DesignerPane(xml: String, onInsert: (String) -> Unit, appName: String, modifier: Modifier) {
    val widgets = remember(xml) { parseWidgets(xml) }
    Row(modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.width(66.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            palette.forEach { item ->
                Surface(
                    onClick = { onInsert(item.snippet) },
                    color = Ds.Card, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Ds.Line),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(item.icon, null, tint = Ds.OnBg, modifier = Modifier.size(22.dp))
                        Text(item.label, color = Ds.Muted, fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
        }
        Surface(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            shape = RoundedCornerShape(22.dp), color = Color(0xFFF6F4FF), border = BorderStroke(3.dp, Color(0xFF2A2D47))
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().background(Ds.Purple).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(appName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (widgets.isEmpty()) Text("Empty layout. Tap a component to add it.", color = Color(0xFF6B7088), fontSize = 12.sp)
                    widgets.forEach { w -> PreviewItem(w) }
                }
            }
        }
    }
}

@Composable
private fun PreviewItem(w: PreviewWidget) {
    val dark = Color(0xFF1C1F33)
    when (w.type) {
        "TextView", "MaterialTextView", "AppCompatTextView" ->
            Text(w.text.ifBlank { "TextView" }, color = dark, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        "Button", "MaterialButton", "AppCompatButton" ->
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Ds.Purple).padding(horizontal = 18.dp, vertical = 9.dp)) {
                Text(w.text.ifBlank { "Button" }, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        "EditText", "TextInputEditText", "AppCompatEditText" ->
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(w.text.ifBlank { "Input" }, color = Color(0xFF8A8FA8), fontSize = 13.sp)
            }
        "ImageView", "AppCompatImageView", "ShapeableImageView" ->
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFD9D4F5)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Image, null, tint = Ds.Purple)
            }
        "RecyclerView" ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE4E0FA))) }
            }
        "CardView", "MaterialCardView" ->
            Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(Color.White))
        "LinearLayout", "ConstraintLayout", "FrameLayout", "RelativeLayout", "ScrollView", "LinearLayoutCompat", "CoordinatorLayout" -> Unit
        else -> Text("‹${w.type}›", color = Color(0xFF6B7088), fontSize = 11.sp)
    }
}

/* ────────────────────────── helpers ────────────────────────── */

@Composable
private fun LazyFileList(files: List<File>, projectDir: File?, onOpen: (File) -> Unit) {
    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
        items(files, key = { it.absolutePath }) { file ->
            val path = if (projectDir != null) runCatching { file.relativeTo(projectDir).path }.getOrDefault(file.name) else file.name
            TextButton(onClick = { onOpen(file) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.InsertDriveFile, null, tint = Ds.PurpleSoft, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, color = Ds.OnBg, maxLines = 1)
                    Text(path, color = Ds.Muted, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

private fun inspectSource(text: String, extension: String): List<String> {
    val result = mutableListOf<String>()
    text.lines().forEachIndexed { index, line ->
        if (line.length > 120) result += "Line ${index + 1}: long line (${line.length} characters)."
        if ("TODO" in line || "FIXME" in line) result += "Line ${index + 1}: TODO/FIXME marker."
    }
    if (extension in setOf("kt", "kts", "java", "cpp", "c", "h", "hpp", "js", "ts", "jsx", "tsx")) {
        val opens = text.count { it == '{' }
        val closes = text.count { it == '}' }
        if (opens != closes) result += "Brace count differs: $opens opening, $closes closing. Check strings/comments too."
        val po = text.count { it == '(' }
        val pc = text.count { it == ')' }
        if (po != pc) result += "Parenthesis count differs: $po opening, $pc closing."
    }
    if (result.isEmpty()) result += "No basic issues detected. This is not a compiler or LSP analysis."
    return result
}

private object CodeSyntaxTransformation : VisualTransformation {
    private val keywords = setOf(
        "package", "import", "class", "object", "interface", "enum", "data", "sealed", "annotation",
        "fun", "val", "var", "const", "lateinit", "override", "private", "public", "protected", "internal",
        "if", "else", "when", "for", "while", "do", "try", "catch", "finally", "throw", "return",
        "this", "super", "null", "true", "false", "new", "static", "final", "void", "int", "long",
        "boolean", "String", "extends", "implements", "suspend", "inline", "operator", "companion", "by"
    )
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val result = androidx.compose.ui.text.buildAnnotatedString {
            append(source)
            Regex("\\b[A-Z][A-Za-z0-9_]*\\b").findAll(source).forEach { addStyle(SpanStyle(color = Color(0xFF82AAFF)), it.range.first, it.range.last + 1) }
            Regex("\\b[0-9]+(\\.[0-9]+)?\\b").findAll(source).forEach { addStyle(SpanStyle(color = Color(0xFFF78C6C)), it.range.first, it.range.last + 1) }
            Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b").findAll(source).forEach { match ->
                if (match.value in keywords) addStyle(SpanStyle(color = Color(0xFFC792EA)), match.range.first, match.range.last + 1)
            }
            Regex("\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'").findAll(source).forEach { addStyle(SpanStyle(color = Color(0xFFF2B27A)), it.range.first, it.range.last + 1) }
            Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/").findAll(source).forEach { addStyle(SpanStyle(color = Color(0xFF7F8C78)), it.range.first, it.range.last + 1) }
        }
        return TransformedText(result, OffsetMapping.Identity)
    }
}
