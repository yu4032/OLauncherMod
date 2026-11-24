package com.yu4032.olaunchermod

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ActivityChoice(val app: String, val label: String, val component: String)

@Composable
internal fun IndicatorActivityControls() {
    val pm = LocalContext.current.packageManager
    val scope = rememberCoroutineScope()
    var enabled by rememberSaveable { mutableStateOf(false) }
    var component by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selectedLabel by remember(component) { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (loaded) return@LaunchedEffect
        val config = RootBridge.readIndicatorActivity()
        if (config == null) {
            message = "读取失败，请检查 Root 授权。"
        } else {
            enabled = config.enabled()
            component = config.component()
            loaded = true
        }
    }
    LaunchedEffect(component) {
        val name = ComponentName.unflattenFromString(component)
        if (name != null) {
            selectedLabel = withContext(Dispatchers.IO) {
                try {
                    val info = pm.getActivityInfo(name, 0)
                    "${info.applicationInfo.loadLabel(pm)} · ${info.loadLabel(pm)}"
                } catch (_: PackageManager.NameNotFoundException) {
                    ""
                }
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("替换「问小布」", modifier = Modifier.weight(1f))
        Switch(checked = enabled, enabled = loaded && !saving, onCheckedChange = { enabled = it })
    }
    OutlinedButton(
        enabled = loaded && !saving,
        modifier = Modifier.fillMaxWidth(),
        onClick = { picking = true }
    ) { Text(if (component.isEmpty()) "选择应用活动" else "更换应用活动") }
    if (component.isNotEmpty()) {
        if (selectedLabel.isNotEmpty()) Text(selectedLabel)
        Text(component, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Button(
        enabled = loaded && !saving && (!enabled || component.isNotEmpty()),
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            val config = IndicatorActivityConfig(enabled, component)
            saving = true
            scope.launch {
                val saved = RootBridge.saveIndicatorActivity(config)
                message = if (saved) "已保存，返回桌面后生效。" else "保存失败，请检查 Root 授权。"
                saving = false
            }
        }
    ) { Text(if (saving) "正在保存…" else "保存并重启桌面") }
    if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)

    if (picking) ActivityPicker(pm, component, onDismiss = { picking = false }) {
        component = it
        picking = false
    }
}

@Composable
private fun ActivityPicker(
    pm: PackageManager,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var choices by remember { mutableStateOf<List<ActivityChoice>?>(null) }
    LaunchedEffect(pm) {
        choices = withContext(Dispatchers.IO) { installedActivities(pm) }
    }
    val matches = remember(choices, query) {
        val search = query.trim()
        if (search.isEmpty()) choices.orEmpty() else choices.orEmpty().mapNotNull { choice ->
            val rank = when {
                choice.app.equals(search, true) || choice.label.equals(search, true) -> 0
                choice.app.startsWith(search, true) || choice.label.startsWith(search, true) -> 1
                choice.app.contains(search, true) || choice.label.contains(search, true) -> 2
                choice.component.contains(search, true) -> 3
                else -> return@mapNotNull null
            }
            rank to choice
        }.sortedBy { it.first }.map { it.second }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择应用活动") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索应用、包名或活动") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                when {
                    choices == null -> Text("正在读取已安装的活动…")
                    matches.isEmpty() -> Text("没有匹配的活动")
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(matches, key = { it.component }) { choice ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(role = Role.RadioButton) { onSelect(choice.component) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(selected = choice.component == selected, onClick = null)
                                Column(Modifier.weight(1f)) {
                                    Text("${choice.app} · ${choice.label}", maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                    Text(choice.component, style = MaterialTheme.typography.bodySmall)
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

private fun installedActivities(pm: PackageManager): List<ActivityChoice> {
    val collator = Collator.getInstance()
    val permissions = mutableMapOf<String, Boolean>()
    return pm.getInstalledPackages(PackageManager.GET_ACTIVITIES).flatMap { pkg ->
        val app = pkg.applicationInfo ?: return@flatMap emptyList()
        val appLabel = app.loadLabel(pm).toString()
        pkg.activities.orEmpty().mapNotNull { info ->
            if (!info.exported || !info.enabled || !app.enabled) return@mapNotNull null
            val permission = info.permission
            if (permission != null && !permissions.getOrPut(permission) {
                    pm.checkPermission(permission, "com.android.launcher") == PackageManager.PERMISSION_GRANTED
                }) return@mapNotNull null
            val component = ComponentName(info.packageName, info.name).flattenToString()
            if (!IndicatorActivityConfig.validComponent(component)) return@mapNotNull null
            ActivityChoice(appLabel, info.loadLabel(pm).toString(), component)
        }
    }.distinctBy { it.component }.sortedWith(
        compareBy(collator, ActivityChoice::app).thenBy(collator, ActivityChoice::label)
            .thenBy(ActivityChoice::component)
    )
}
