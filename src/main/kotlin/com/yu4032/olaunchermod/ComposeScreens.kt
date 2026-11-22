package com.yu4032.olaunchermod

import android.content.Intent
import android.os.Bundle
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val CanvasColor = Color(0xFFF5F6F8)
private val Accent = Color(0xFF2564D8)

@Composable
private fun ColorOsTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = Accent,
        onPrimary = Color.White,
        background = CanvasColor,
        onBackground = Color(0xFF17181B),
        surface = Color.White,
        onSurface = Color(0xFF17181B),
        surfaceVariant = Color(0xFFF0F2F5)
    )
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}

@Composable
private fun SettingsSurface(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun PageFrame(title: String, summary: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(CanvasColor)) {
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, top = 28.dp, end = 22.dp, bottom = 12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(summary, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                content = content
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ColorOsTheme {
                GridPage { startActivity(Intent(this, MarginSettingsActivity::class.java)) }
            }
        }
    }
}

@Composable
private fun GridPage(onOpenMargins: () -> Unit) {
    var columns by rememberSaveable { mutableStateOf("8") }
    var rows by rememberSaveable { mutableStateOf("4") }
    var keepEmpty by rememberSaveable { mutableStateOf(true) }
    var message by remember { mutableStateOf("读取桌面设置…") }
    var diagnostics by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val saved = RootBridge.read(RootBridge.GRID).split(Regex("\\s+"))
        if (saved.size >= 2 && saved[0].toIntOrNull() != null && saved[1].toIntOrNull() != null) {
            columns = saved[0]
            rows = saved[1]
            keepEmpty = saved.getOrNull(2) != "0"
            message = "已读取当前横屏网格"
        } else {
            message = "尚无自定义网格配置，显示推荐值"
        }
        diagnostics = RootBridge.status()
    }
    PageFrame("OLauncherMod", "ColorOS 17.5.5 · 自定义桌面网格与工作区布局") {
        SettingsSurface("桌面网格", "横屏参数；竖屏自动交换行列，不改变持久化图标坐标。") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = columns, onValueChange = { columns = it.filter(Char::isDigit).take(2) },
                    label = { Text("横屏列数") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = rows, onValueChange = { rows = it.filter(Char::isDigit).take(2) },
                    label = { Text("横屏行数") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Text("列数 4–8，行数 3–8", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("保留空位", fontWeight = FontWeight.Medium)
                    Text("禁止系统在启动时紧凑重排图标",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = keepEmpty, onCheckedChange = { keepEmpty = it })
            }
            Button(
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val x = columns.toIntOrNull()
                    val y = rows.toIntOrNull()
                    if (x == null || y == null || x !in 4..8 || y !in 3..8) {
                        message = "网格范围无效：列数 4–8，行数 3–8"
                    } else {
                        saving = true
                        scope.launch {
                            val ok = RootBridge.saveGrid(x, y, keepEmpty)
                            saving = false
                            message = if (ok) "已保存，桌面重新启动后生效。" else "保存失败，检查 Root 授权。"
                            if (ok) diagnostics = RootBridge.status()
                        }
                    }
                }
            ) { Text(if (saving) "正在保存…" else "应用网格并重启桌面") }
            Text(message, style = MaterialTheme.typography.bodySmall)
        }

        SettingsSurface("工作区位置", "横屏、竖屏分别调整水平、顶部和底部留白，支持滑块、精确输入及重置。") {
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenMargins
            ) { Text("进入工作区位置设置") }
        }

        SettingsSurface("运行状态", "模型加载边界与工作区测量报告。") {
            OutlinedButton(
                onClick = { scope.launch { diagnostics = RootBridge.status() } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("刷新运行状态") }
            Text(diagnostics.ifBlank { "尚无状态记录" },
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(26.dp))
    }
}

class MarginSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ColorOsTheme { MarginPage(onBack = { finish() }) } }
    }
}

@Composable
private fun MarginPage(onBack: () -> Unit) {
    var draft by remember { mutableStateOf(WorkspaceMarginConfig.DEFAULT) }
    val currentOrientation = LocalConfiguration.current.orientation
    var portrait by rememberSaveable {
        mutableStateOf(currentOrientation == Configuration.ORIENTATION_PORTRAIT)
    }
    var loaded by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在读取边距…") }
    var saving by remember { mutableStateOf(false) }
    var initialLegacyAsymmetric by remember { mutableStateOf(false) }
    var showLegacyConfirm by remember { mutableStateOf(false) }
    var editAxis by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    val labels = listOf("水平距离偏移", "顶部距离偏移", "底部距离偏移")
    val details = listOf(
        "同时调节工作区左、右两侧的水平距离",
        "从系统原生顶部留白增减，不调整 Dock",
        "从系统原生底部留白增减，不调整 Dock"
    )

    LaunchedEffect(Unit) {
        val raw = RootBridge.readChecked(RootBridge.MARGINS)
        if (raw == null) {
            status = "无法以 Root 读取现有配置，已禁止覆盖保存。请先授权后重开设置。"
            loaded = false
            return@LaunchedEffect
        }
        try {
            draft = if (raw.isBlank()) WorkspaceMarginConfig.DEFAULT
                else WorkspaceMarginConfig.parse(raw)
            initialLegacyAsymmetric = draft.isLegacyAsymmetric()
            status = if (draft.isLegacy()) "已载入旧版四边距；编辑后转为横竖屏独立设置。"
                else "参数已读取。调节滑块不会立即改动桌面。"
        } catch (error: IllegalArgumentException) {
            draft = WorkspaceMarginConfig.DEFAULT
            status = "旧配置无效，未改动文件；需重新确认后保存。"
        }
        loaded = true
    }
    fun update(axis: Int, value: Int) {
        val limited = value.coerceIn(-WorkspaceMarginConfig.LIMIT_DP,
            WorkspaceMarginConfig.LIMIT_DP)
        draft = draft.with(portrait, axis, limited)
        dirty = true
    }
    fun applyNow() {
        if (!loaded || saving) return
        saving = true
        val snapshot = draft
        scope.launch {
            val ok = RootBridge.saveMargins(snapshot)
            saving = false
            status = if (ok) "设置已保存，桌面将重新启动。" else "保存失败，请检查 Root 授权。"
            if (ok) {
                dirty = false
                initialLegacyAsymmetric = false
            }
        }
    }
    PageFrame("工作区位置", "COUI 风格 Compose 设置 · 横竖屏独立布局") {
        TextButton(onClick = onBack) { Text("返回网格设置") }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                modifier = Modifier.weight(1f),
                selected = !portrait,
                onClick = { portrait = false },
                label = { Text("横屏布局") }
            )
            FilterChip(
                modifier = Modifier.weight(1f),
                selected = portrait,
                onClick = { portrait = true },
                label = { Text("竖屏布局") }
            )
        }
        SettingsSurface("工作区边距", "单位 dp · -600～600。0 为系统原始距离；上下值按当前屏幕剩余空间适配。") {
            for (axis in 0..2) {
                val value = draft.get(portrait, axis)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(labels[axis], fontWeight = FontWeight.SemiBold)
                            Text(details[axis], fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { editAxis = axis }) {
                            Text("$value dp", fontWeight = FontWeight.Bold)
                        }
                    }
                    Slider(
                        value = value.toFloat(),
                        onValueChange = { update(axis, it.roundToInt()) },
                        valueRange = -600f..600f
                    )
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { update(axis, 0) }) { Text("重置") }
                        OutlinedButton(onClick = { update(axis, value - 1) }) { Text("−") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { update(axis, value + 1) }) { Text("+") }
                    }
                }
                if (axis < 2) HorizontalDivider()
            }
        }
        SettingsSurface("重置与应用", "修改会先保留在本页面；保存时才写入配置并重启桌面。") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f),
                    onClick = {
                        draft = draft.resetOrientation(portrait)
                        dirty = true
                    }) { Text("重置当前方向") }
                OutlinedButton(modifier = Modifier.weight(1f),
                    onClick = {
                        draft = WorkspaceMarginConfig.DEFAULT
                        dirty = true
                    }) { Text("全部恢复默认") }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = loaded && dirty && !saving,
                onClick = {
                    if (initialLegacyAsymmetric && !draft.isLegacy()) {
                        showLegacyConfirm = true
                    } else applyNow()
                }
            ) {
                Text(if (saving) "正在保存…" else "应用边距并重启桌面")
            }
            Text(status, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(28.dp))
    }

    if (editAxis in 0..2) {
        val selected = editAxis
        var valueText by remember(selected, portrait) {
            mutableStateOf(draft.get(portrait, selected).toString())
        }
        val parsed = valueText.toIntOrNull()
        AlertDialog(
            onDismissRequest = { editAxis = -1 },
            title = { Text(labels[selected]) },
            text = {
                OutlinedTextField(
                    value = valueText, onValueChange = { valueText = it },
                    label = { Text("输入 -600～600 dp") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = parsed != null && parsed in -600..600,
                    onClick = {
                        update(selected, parsed!!)
                        editAxis = -1
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { editAxis = -1 }) { Text("取消") }
            }
        )
    }
    if (showLegacyConfirm) {
        AlertDialog(
            onDismissRequest = { showLegacyConfirm = false },
            title = { Text("旧版左右边距不同") },
            text = { Text("旧版的左右边距不对称。新版使用对称的水平距离，"
                    + "首次保存会以旧值平均数作为默认。继续保存吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showLegacyConfirm = false
                    applyNow()
                }) { Text("继续保存") }
            },
            dismissButton = {
                TextButton(onClick = { showLegacyConfirm = false }) { Text("取消") }
            }
        )
    }
}
