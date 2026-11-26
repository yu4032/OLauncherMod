package com.yu4032.olaunchermod

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.Collator

private data class LauncherAppItem(
    val packageName: String,
    val label: String
)

private data class IconRuleRow(
    val targetPkg: String,
    val targetLabel: String,
    val spec: String,
    val desc: String,
    val preview: Drawable?
)

class IconSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ColorOsTheme {
                IconReplacementPage(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun IconReplacementPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(IconReplacementConfig.EMPTY) }
    var rows by remember { mutableStateOf<List<IconRuleRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在读取图标替换配置…") }

    var pickingTarget by remember { mutableStateOf(false) }
    var pendingTargetPkg by rememberSaveable { mutableStateOf<String?>(null) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var pickingBorrowSource by remember { mutableStateOf(false) }

    suspend fun reloadConfig() {
        val loadedCfg = RootBridge.readIconFix()
        if (loadedCfg == null) {
            status = "读取失败，请先在 KernelSU 中授予 Root 权限。"
            loaded = false
            return
        }
        config = loadedCfg
        rows = buildRuleRows(pm, context.cacheDir, loadedCfg)
        loaded = true
        status = "已配置 ${loadedCfg.entries().size} 个应用图标替换。"
    }

    LaunchedEffect(Unit) {
        reloadConfig()
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val target = pendingTargetPkg
        pendingTargetPkg = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val fileName = IconReplacementConfig.safeIconFileName(target)
            val tmpFile = File(context.cacheDir, "picked_$fileName")
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
                    } ?: return@runCatching false
                    true
                }.getOrDefault(false)
            }
            val installed = copied && RootBridge.installIconImage(tmpFile.absolutePath, fileName)
            val next = config.withEntry(target, "${IconReplacementConfig.RELATIVE_ICONS_DIR}/$fileName")
            val saved = installed && RootBridge.saveIconFix(next, restartLauncher = true)
            if (saved) reloadConfig()
            busy = false
            status = if (saved) "已替换 $target 并刷新桌面" else "替换失败，请检查文件权限与 Root 授权。"
        }
    }

    PageFrame("图标替换", "OLauncherMod · 在桌面进程内替换任意应用图标") {
        TextButton(onClick = onBack) { Text("返回网格设置") }

        SettingsSurface("操作与状态", status) {
            Button(
                enabled = loaded && !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = { pickingTarget = true }
            ) { Text("添加替换") }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = loaded && !busy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        busy = true
                        scope.launch {
                            val ok = RootBridge.refreshLauncherIcons()
                            busy = false
                            status = if (ok) "已清理图标缓存并重启桌面" else "刷新失败，请检查 Root 授权。"
                        }
                    }
                ) { Text("清缓存并刷新桌面") }

                OutlinedButton(
                    enabled = loaded && !busy && !config.isEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        busy = true
                        scope.launch {
                            val ok = RootBridge.clearAllIconFix()
                            if (ok) reloadConfig()
                            busy = false
                            status = if (ok) "已全部清除并重启桌面" else "清除失败，请检查 Root 授权。"
                        }
                    }
                ) { Text("全部清除") }
            }
        }

        SettingsSurface("已配置的替换", "修改后会自动清除桌面图标数据库并重启桌面。") {
            if (!loaded) {
                Text("等待读取配置…", style = MaterialTheme.typography.bodySmall)
            } else if (rows.isEmpty()) {
                Text("还没有替换任何应用图标", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                rows.forEachIndexed { idx, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DrawableIcon(row.preview, modifier = Modifier.size(40.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.targetLabel, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${row.targetPkg} ← ${row.desc}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val next = config.withoutPackage(row.targetPkg)
                                    if (!row.spec.startsWith("pkg:")) {
                                        RootBridge.removeIconImage(row.spec.substringAfterLast('/'))
                                    }
                                    val ok = RootBridge.saveIconFix(next, restartLauncher = true)
                                    if (ok) reloadConfig()
                                    busy = false
                                    status = if (ok) "已移除 ${row.targetLabel}" else "删除失败，请检查 Root 授权。"
                                }
                            }
                        ) { Text("删除") }
                    }
                    if (idx < rows.lastIndex) HorizontalDivider()
                }
            }
        }

        Spacer(Modifier.height(26.dp))
    }

    if (pickingTarget) {
        AppPickerDialog(
            title = "第 1 步：选择要改图标的应用",
            showIcons = false,
            excludePkg = context.packageName,
            pm = pm,
            onDismiss = { pickingTarget = false },
            onSelect = { pkg ->
                pickingTarget = false
                pendingTargetPkg = pkg
                showSourceDialog = true
            }
        )
    }

    if (showSourceDialog && pendingTargetPkg != null) {
        AlertDialog(
            onDismissRequest = {
                showSourceDialog = false
                pendingTargetPkg = null
            },
            title = { Text("第 2 步：选择图标来源") },
            text = { Text("目标应用：${pendingTargetPkg}") },
            confirmButton = {
                TextButton(onClick = {
                    showSourceDialog = false
                    pickingBorrowSource = true
                }) { Text("借用其他应用图标") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showSourceDialog = false
                        pendingTargetPkg = null
                    }) { Text("取消") }
                    TextButton(onClick = {
                        showSourceDialog = false
                        pickImageLauncher.launch("image/*")
                    }) { Text("从图片文件选择") }
                }
            }
        )
    }

    if (pickingBorrowSource && pendingTargetPkg != null) {
        val target = pendingTargetPkg!!
        AppPickerDialog(
            title = "选择图标来源应用",
            showIcons = true,
            excludePkg = target,
            pm = pm,
            onDismiss = {
                pickingBorrowSource = false
                pendingTargetPkg = null
            },
            onSelect = { srcPkg ->
                pickingBorrowSource = false
                pendingTargetPkg = null
                busy = true
                scope.launch {
                    val next = config.withEntry(target, "pkg:$srcPkg")
                    val ok = RootBridge.saveIconFix(next, restartLauncher = true)
                    if (ok) reloadConfig()
                    busy = false
                    status = if (ok) "已替换 $target ← $srcPkg 并刷新桌面" else "保存失败，请检查 Root 授权。"
                }
            }
        )
    }
}

@Composable
private fun DrawableIcon(drawable: Drawable?, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        },
        update = { imageView ->
            imageView.setImageDrawable(drawable)
        }
    )
}

@Composable
private fun AppPickerDialog(
    title: String,
    showIcons: Boolean,
    excludePkg: String,
    pm: PackageManager,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<LauncherAppItem>?>(null) }

    LaunchedEffect(pm, excludePkg) {
        apps = withContext(Dispatchers.IO) { queryLauncherApps(pm, excludePkg) }
    }

    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps.orEmpty()
        else apps.orEmpty().filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索应用名或包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                when {
                    apps == null -> Text("正在读取应用列表…")
                    filtered.isEmpty() -> Text("没有匹配的应用")
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(filtered, key = { it.packageName }) { item ->
                            val icon = remember(item.packageName, showIcons) {
                                if (!showIcons) null else runCatching { pm.getApplicationIcon(item.packageName) }.getOrNull()
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { onSelect(item.packageName) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (showIcons) {
                                    DrawableIcon(icon, modifier = Modifier.size(36.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.packageName, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private suspend fun buildRuleRows(
    pm: PackageManager,
    cacheDir: File,
    config: IconReplacementConfig
): List<IconRuleRow> {
    val out = ArrayList<IconRuleRow>(config.entries().size)
    for ((targetPkg, spec) in config.entries()) {
        val label = withContext(Dispatchers.IO) {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(targetPkg, 0)).toString() }
                .getOrDefault(targetPkg)
        }
        val (desc, preview) = if (spec.startsWith("pkg:")) {
            val srcPkg = spec.removePrefix("pkg:").trim()
            val d = withContext(Dispatchers.IO) {
                runCatching { pm.getApplicationIcon(srcPkg) }.getOrNull()
            }
            "借用 $srcPkg" to d
        } else {
            val dst = File(cacheDir, "prev_${spec.substringAfterLast('/')}")
            val copied = RootBridge.exportIconPreview(spec, dst.absolutePath)
            val d = if (copied && dst.exists()) Drawable.createFromPath(dst.absolutePath) else null
            spec to d
        }
        out.add(IconRuleRow(targetPkg, label, spec, desc, preview))
    }
    return out
}

private fun queryLauncherApps(pm: PackageManager, excludePkg: String): List<LauncherAppItem> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val ris = pm.queryIntentActivities(intent, 0)
    val seen = HashSet<String>()
    val list = ArrayList<LauncherAppItem>()
    for (ri in ris) {
        val pkg = ri.activityInfo?.packageName ?: continue
        if (pkg == excludePkg || !seen.add(pkg)) continue
        val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
        list.add(LauncherAppItem(pkg, label))
    }
    val collator = Collator.getInstance()
    return list.sortedWith(compareBy(collator, LauncherAppItem::label).thenBy(LauncherAppItem::packageName))
}
