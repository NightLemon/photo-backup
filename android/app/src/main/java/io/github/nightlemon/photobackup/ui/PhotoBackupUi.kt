package io.github.nightlemon.photobackup.ui

import android.app.DatePickerDialog
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.nightlemon.photobackup.AppViewModel
import io.github.nightlemon.photobackup.data.BackupRecord
import io.github.nightlemon.photobackup.sync.BackupSettings
import io.github.nightlemon.photobackup.sync.BackupSettingsStore
import io.github.nightlemon.photobackup.sync.BackupSortOrder
import io.github.nightlemon.photobackup.sync.ManualSyncScope
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoBackupRoot(
    viewModel: AppViewModel,
    openCleanupInitially: Boolean,
    hasFullMediaAccess: Boolean,
    onRequestPermissions: () -> Unit,
    onScan: () -> Unit,
    onDelete: (List<BackupRecord>) -> Unit,
    onBatterySettings: () -> Unit,
) {
    val colors = lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF1769E0),
        secondary = androidx.compose.ui.graphics.Color(0xFF1769E0),
        background = androidx.compose.ui.graphics.Color(0xFFF7F9FC),
        surface = androidx.compose.ui.graphics.Color.White,
    )
    MaterialTheme(colorScheme = colors) {
        val credential by viewModel.credential.collectAsState()
        Scaffold(topBar = { TopAppBar(title = { Text("家庭照片备份", fontWeight = FontWeight.SemiBold) }) }) { padding ->
            if (credential == null) {
                PairingScreen(
                    modifier = Modifier.padding(padding),
                    viewModel = viewModel,
                    onScan = onScan,
                )
            } else {
                var tab by rememberSaveable { mutableIntStateOf(if (openCleanupInitially) 1 else 0) }
                Scaffold(
                    modifier = Modifier.padding(padding),
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = { Icon(Icons.Default.CloudUpload, null) },
                                label = { Text("备份") },
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                icon = { Icon(Icons.Default.Delete, null) },
                                label = { Text("清理") },
                            )
                            NavigationBarItem(
                                selected = tab == 2,
                                onClick = { tab = 2 },
                                icon = { Icon(Icons.Default.Settings, null) },
                                label = { Text("设置") },
                            )
                        }
                    },
                ) { inner ->
                    when (tab) {
                        0 -> BackupScreen(
                            modifier = Modifier.padding(inner),
                            viewModel = viewModel,
                            hasFullMediaAccess = hasFullMediaAccess,
                            onRequestPermissions = onRequestPermissions,
                            onBatterySettings = onBatterySettings,
                        )
                        1 -> CleanupScreen(
                            modifier = Modifier.padding(inner),
                            viewModel = viewModel,
                            onDelete = onDelete,
                        )
                        else -> SettingsScreen(Modifier.padding(inner), viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingScreen(modifier: Modifier, viewModel: AppViewModel, onScan: () -> Unit) {
    val message by viewModel.message.collectAsState()
    val pairing by viewModel.pairing.collectAsState()
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.PhoneAndroid, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Text("连接家庭服务器", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("在电脑上打开管理页，生成二维码后用这里扫描。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onScan, enabled = !pairing) {
                if (pairing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("扫描配对二维码")
            }
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BackupScreen(
    modifier: Modifier,
    viewModel: AppViewModel,
    hasFullMediaAccess: Boolean,
    onRequestPermissions: () -> Unit,
    onBatterySettings: () -> Unit,
) {
    val credential by viewModel.credential.collectAsState()
    val message by viewModel.message.collectAsState()
    var showForget by remember { mutableStateOf(false) }
    var showManualSync by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            viewModel.refreshSyncMessage()
        }
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        if (!hasFullMediaAccess) item {
            InfoCard("照片权限不完整", "目前只能备份系统允许访问的内容。请授予完整照片和视频权限。") {
                Button(onClick = onRequestPermissions) { Text("授予权限") }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Text(credential?.serverName.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = viewModel::syncNow, enabled = hasFullMediaAccess) { Text("按设置同步") }
                        TextButton(onClick = { showManualSync = true }, enabled = hasFullMediaAccess) {
                            Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
                            Text("手动同步", Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
        item {
            InfoCard("后台运行", "Android 可能延后后台任务。上传中会显示常驻通知，被中断后会从已确认分块继续。") {
                TextButton(onClick = onBatterySettings) { Text("查看电池优化设置") }
            }
        }
        item { TextButton(onClick = { showForget = true }) { Text("解除本机配对") } }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (showForget) AlertDialog(
        onDismissRequest = { showForget = false },
        title = { Text("解除配对？") },
        text = { Text("以后需要重新扫描电脑上的二维码。已有备份记录和服务器照片不会删除。") },
        confirmButton = { TextButton(onClick = { showForget = false; viewModel.forgetServer() }) { Text("解除") } },
        dismissButton = { TextButton(onClick = { showForget = false }) { Text("取消") } },
    )
    if (showManualSync) ManualSyncDialog(
        defaults = viewModel.settings.collectAsState().value,
        onDismiss = { showManualSync = false },
        onStart = { scope ->
            showManualSync = false
            viewModel.startManualSync(scope)
        },
    )
}

@Composable
private fun ManualSyncDialog(
    defaults: BackupSettings,
    onDismiss: () -> Unit,
    onStart: (ManualSyncScope) -> Unit,
) {
    var screenshots by rememberSaveable { mutableStateOf(defaults.includeScreenshots) }
    var images by rememberSaveable { mutableStateOf(defaults.includeOtherImages) }
    var videos by rememberSaveable { mutableStateOf(defaults.includeVideos) }
    var limitDates by rememberSaveable { mutableStateOf(false) }
    var startDay by rememberSaveable { mutableStateOf(LocalDate.now().minusDays(29).toEpochDay()) }
    var endDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    val valid = (screenshots || images || videos) && (!limitDates || startDay <= endDay)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动同步") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("本次同步范围", fontWeight = FontWeight.SemiBold)
                SettingCheckbox("截图", screenshots) { screenshots = it }
                SettingCheckbox("普通图片", images) { images = it }
                SettingCheckbox("视频", videos) { videos = it }
                SettingCheckbox("限制日期范围", limitDates) { limitDates = it }
                if (limitDates) {
                    DateRangeButton("开始日期", startDay) { startDay = it }
                    DateRangeButton("结束日期", endDay) { endDay = it }
                    Text("开始和结束日期均包含。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!screenshots && !images && !videos) {
                    Text("请至少选择一种媒体类型。", color = MaterialTheme.colorScheme.error)
                } else if (limitDates && startDay > endDay) {
                    Text("开始日期不能晚于结束日期。", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "排序方式和并行数沿用设置页；本次范围不会修改自动同步设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onStart(
                        ManualSyncScope(
                            includeScreenshots = screenshots,
                            includeOtherImages = images,
                            includeVideos = videos,
                            startEpochDay = startDay.takeIf { limitDates },
                            endEpochDay = endDay.takeIf { limitDates },
                        ),
                    )
                },
                enabled = valid,
            ) { Text("开始同步") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DateRangeButton(label: String, epochDay: Long, onSelected: (Long) -> Unit) {
    val context = LocalContext.current
    val date = LocalDate.ofEpochDay(epochDay)
    val formatted = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
    TextButton(
        onClick = {
            DatePickerDialog(
                context,
                { _, year, month, day -> onSelected(LocalDate.of(year, month + 1, day).toEpochDay()) },
                date.year,
                date.monthValue - 1,
                date.dayOfMonth,
            ).show()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("$label：$formatted", Modifier.fillMaxWidth())
    }
}

@Composable
private fun CleanupScreen(modifier: Modifier, viewModel: AppViewModel, onDelete: (List<BackupRecord>) -> Unit) {
    val candidates by viewModel.cleanup.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val selectedRecords = candidates.filter { it.mediaKey in selected }
    val selectedBytes = selectedRecords.sumOf { it.byteLength }
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshCleanupClock()
            delay(60 * 60 * 1000L)
        }
    }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("已验证备份", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (candidates.isEmpty()) "当前没有可以安全清理的文件" else "${candidates.size} 个文件，可释放 ${formatSize(candidates.sumOf { it.byteLength })}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (settings.cleanupRetentionDays > 0) Text(
                "最近 ${settings.cleanupRetentionDays} 天的文件已排除",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (candidates.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.selectAll(selected.isEmpty()) }) {
                    Text(if (selected.isNotEmpty()) "取消全选" else "全选")
                }
                Button(
                    onClick = { onDelete(selectedRecords) },
                    enabled = selectedRecords.isNotEmpty(),
                ) {
                    Text("删除 ${selectedRecords.size} 项 · ${formatSize(selectedBytes)}")
                }
            }
            Text("清理依据本机保存的完成凭据，不会在删除前重新联系服务器。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(candidates, key = { it.mediaKey }) { record ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AsyncImage(
                        model = Uri.parse(record.contentUri),
                        contentDescription = null,
                        modifier = Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(record.displayName, maxLines = 1, fontWeight = FontWeight.Medium)
                        Text(formatSize(record.byteLength), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Checkbox(checked = record.mediaKey in selected, onCheckedChange = { viewModel.toggle(record.mediaKey) })
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier, viewModel: AppViewModel) {
    val saved by viewModel.settings.collectAsState()
    var draft by remember(saved) { mutableStateOf(saved) }
    var retentionText by remember(saved.cleanupRetentionDays) { mutableStateOf(saved.cleanupRetentionDays.toString()) }
    var savedNotice by remember { mutableStateOf(false) }
    val retentionDays = retentionText.toIntOrNull()
    val retentionValid = retentionDays != null && retentionDays in 0..BackupSettingsStore.MAX_RETENTION_DAYS

    fun update(value: BackupSettings) {
        draft = value
        savedNotice = false
    }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("同步范围", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("选择允许上传到家庭服务器的媒体类型。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingCheckbox("截图", draft.includeScreenshots) { update(draft.copy(includeScreenshots = it)) }
                    SettingCheckbox("普通图片", draft.includeOtherImages) { update(draft.copy(includeOtherImages = it)) }
                    SettingCheckbox("视频", draft.includeVideos) { update(draft.copy(includeVideos = it)) }
                    if (!draft.includeScreenshots && !draft.includeOtherImages && !draft.includeVideos) {
                        Text("未选择媒体类型，下一次同步不会上传新文件。", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("并行上传", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("多个文件同时上传；单个文件的分块仍按顺序处理。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BackupSettingsStore.PARALLEL_UPLOAD_OPTIONS.forEach { count ->
                        SortOption(
                            label = "$count 路",
                            detail = when (count) {
                                1 -> "最省电，等同串行"
                                2 -> "较低资源占用"
                                4 -> "推荐，兼顾速度与温度"
                                else -> "最高吞吐，手机可能更热"
                            },
                            selected = draft.parallelUploads == count,
                            onClick = { update(draft.copy(parallelUploads = count)) },
                        )
                    }
                    Text("排序表示任务开始顺序；并行时完成顺序可能不同。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("备份顺序", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("上次中断的文件始终优先恢复。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    sortOptions.forEach { (order, label, detail) ->
                        SortOption(
                            label = label,
                            detail = detail,
                            selected = draft.sortOrder == order,
                            onClick = { update(draft.copy(sortOrder = order)) },
                        )
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("清理保留", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("最近产生的媒体不会出现在清理页，服务器备份不受影响。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = retentionText,
                        onValueChange = { value ->
                            if (value.all(Char::isDigit)) retentionText = value
                            savedNotice = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("最近多少天不清理") },
                        suffix = { Text("天") },
                        singleLine = true,
                        isError = !retentionValid,
                        supportingText = {
                            Text(if (retentionValid) "设为 0 表示不保留，所有已验证备份均可清理。" else "请输入 0 到 ${BackupSettingsStore.MAX_RETENTION_DAYS}。")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
        }
        item {
            Button(
                onClick = {
                    val normalized = draft.copy(cleanupRetentionDays = requireNotNull(retentionDays))
                    viewModel.saveSettings(normalized)
                    savedNotice = true
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = retentionValid,
            ) { Text("保存设置") }
            if (savedNotice) Text(
                "设置已保存，将用于下一轮自动同步；清理范围已刷新。",
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SettingCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label)
    }
}

@Composable
private fun SortOption(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(label, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val sortOptions = listOf(
    Triple(BackupSortOrder.TYPE_PRIORITY, "类型优先", "截图 → 普通图片 → 视频，每组从旧到新"),
    Triple(BackupSortOrder.OLDEST_FIRST, "最老优先", "所有已选类型按拍摄时间从旧到新"),
    Triple(BackupSortOrder.NEWEST_FIRST, "最新优先", "所有已选类型按拍摄时间从新到旧"),
    Triple(BackupSortOrder.SMALLEST_FIRST, "小文件优先", "先快速完成更多小文件"),
    Triple(BackupSortOrder.LARGEST_FIRST, "大文件优先", "优先处理占用空间最多的文件"),
)

@Composable
private fun InfoCard(title: String, detail: String, action: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action()
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
