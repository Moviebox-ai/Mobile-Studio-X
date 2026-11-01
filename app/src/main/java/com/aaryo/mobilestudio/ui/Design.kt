package com.aaryo.mobilestudio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Design tokens taken from the uploaded Mobile Studio X mock-up (dark navy + violet). */
object Ds {
    val Bg = Color(0xFF0A0C16)
    val Card = Color(0xFF13162A)
    val CardAlt = Color(0xFF181B30)
    val Field = Color(0xFF1F2236)
    val Line = Color(0xFF262A44)
    val Nav = Color(0xFF0E1020)
    val Purple = Color(0xFF7440EB)
    val PurpleSoft = Color(0xFFA48BFF)
    val PurpleDeep = Color(0xFF2A2250)
    val NavPill = Color(0xFF4A2FB0)
    val Blue = Color(0xFF5B86F5)
    val Cyan = Color(0xFF3CC8F0)
    val Green = Color(0xFF3DD16F)
    val Orange = Color(0xFFFFB454)
    val Red = Color(0xFFFF5C6C)
    val OnBg = Color(0xFFF2F3FA)
    val Muted = Color(0xFF8E96B3)
    val TerminalBg = Color(0xFF05080C)
    val EditorBg = Color(0xFF0A0D11)
}

enum class NavTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    PROJECTS("Projects", Icons.Default.Folder),
    EDITOR("Editor", Icons.Default.Code),
    BUILD("Build", Icons.Default.Layers),
    MORE("More", Icons.Default.MoreHoriz)
}

@Composable
fun DsBellButton(onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(Color(0x337440EB)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Default.Notifications, "Notifications", tint = Ds.PurpleSoft, modifier = Modifier.size(20.dp)) }
}

@Composable
fun DsTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = Icons.Default.Menu,
    onLeading: () -> Unit = {},
    showBell: Boolean = true,
    onBell: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            IconButton(onClick = onLeading, modifier = Modifier.size(40.dp)) {
                Icon(leadingIcon, null, tint = Ds.OnBg)
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, color = Ds.OnBg, fontWeight = FontWeight.Bold,
                fontSize = if (subtitle != null) 20.sp else 19.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) Text(subtitle, color = Ds.Muted, fontSize = 11.sp)
        }
        actions()
        if (showBell) DsBellButton(onBell)
    }
}

@Composable
fun DsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Ds.Card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Ds.Line)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun DsButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(50.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Ds.Purple else Ds.Field,
            contentColor = Color.White,
            disabledContainerColor = if (primary) Ds.Purple.copy(alpha = 0.35f) else Ds.Field.copy(alpha = 0.5f),
            disabledContentColor = Color.White.copy(alpha = 0.45f)
        )
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DsTabs(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ds.Card).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (sel) Ds.Purple else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (sel) Color.White else Ds.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun DsSettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Ds.Card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Ds.Line),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2D47)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Ds.OnBg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Ds.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Ds.Muted)
        }
    }
}

@Composable
fun DsStatusRow(label: String, detail: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
            tint = if (ok) Ds.Green else Ds.Muted, modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Ds.OnBg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (detail.isNotBlank()) Text(detail, color = Ds.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (ok) "Ready" else "Missing", color = if (ok) Ds.Green else Ds.Muted, fontSize = 11.sp)
    }
}

@Composable
fun DsBottomNav(selected: NavTab, moreIsTools: Boolean, onSelect: (NavTab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ds.Nav)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ds.Line))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            NavTab.values().forEach { tab ->
                val sel = tab == selected
                val label = if (tab == NavTab.MORE && moreIsTools) "Tools" else tab.label
                val icon = if (tab == NavTab.MORE && moreIsTools) Icons.Default.Build else tab.icon
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onSelect(tab) }.padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.width(54.dp).height(30.dp).clip(RoundedCornerShape(15.dp))
                            .background(if (sel) Ds.NavPill else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) { Icon(icon, null, tint = if (sel) Color.White else Ds.Muted, modifier = Modifier.size(20.dp)) }
                    Text(
                        label, color = if (sel) Color.White else Ds.Muted, fontSize = 10.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun dsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Ds.Purple,
    unfocusedBorderColor = Ds.Line,
    focusedTextColor = Ds.OnBg,
    unfocusedTextColor = Ds.OnBg,
    focusedLabelColor = Ds.PurpleSoft,
    unfocusedLabelColor = Ds.Muted,
    cursorColor = Ds.PurpleSoft,
    focusedContainerColor = Ds.Field,
    unfocusedContainerColor = Ds.Field
)

@Composable
fun DsLineChart(values: List<Float>, axisMax: Float, modifier: Modifier = Modifier, color: Color = Ds.Purple) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (i in 0..4) {
            val y = h * i / 4f
            drawLine(Ds.Line, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(w, y), strokeWidth = 1f)
        }
        if (values.size < 2 || axisMax <= 0f) return@Canvas
        val step = w / (values.size - 1).coerceAtLeast(1)
        val line = Path()
        val area = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = h - (v / axisMax).coerceIn(0f, 1f) * h
            if (i == 0) { line.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y) } else { line.lineTo(x, y); area.lineTo(x, y) }
        }
        area.lineTo((values.size - 1) * step, h)
        area.close()
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0.02f))))
        drawPath(line, color, style = Stroke(width = 4f))
    }
}

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var progress by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val steps = 28
        for (i in 1..steps) {
            progress = i / steps.toFloat()
            delay(45)
        }
        delay(150)
        onFinished()
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF130D28), Color(0xFF0A0B15))))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            for (k in 0..3) {
                val shift = k * 0.018f * h
                val p = Path().apply {
                    moveTo(0f, h * 0.74f + shift)
                    cubicTo(w * 0.25f, h * 0.60f + shift, w * 0.42f, h * 0.86f + shift, w * 0.68f, h * 0.68f + shift)
                    cubicTo(w * 0.84f, h * 0.58f + shift, w * 0.94f, h * 0.66f + shift, w, h * 0.62f + shift)
                }
                drawPath(
                    p,
                    Brush.horizontalGradient(listOf(Color(0x00A070FF), Color(0xFFB07CFF).copy(alpha = 0.85f - k * 0.2f), Color(0x006A3DE0))),
                    style = Stroke(width = 3f - k * 0.5f)
                )
            }
        }
        Column(
            Modifier.align(Alignment.Center).padding(bottom = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(92.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFF1A1235))
                    .border(3.dp, Brush.linearGradient(listOf(Color(0xFFB07CFF), Color(0xFF7440EB))), RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("</>", color = Color(0xFFC9B5FF), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 34.sp)
            }
            Spacer(Modifier.height(18.dp))
            Text("Mobile Studio X", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Android Development Studio", color = Ds.Muted, fontSize = 15.sp)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Loading workspace...", color = Color(0xFFD5D8EA), fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.width(170.dp).height(4.dp).clip(CircleShape),
                color = Ds.Purple,
                trackColor = Ds.Line
            )
        }
    }
}
