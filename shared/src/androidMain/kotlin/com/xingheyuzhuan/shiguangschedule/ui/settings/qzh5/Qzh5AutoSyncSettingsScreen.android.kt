package com.xingheyuzhuan.shiguangschedule.ui.settings.qzh5

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.service.qzh5.Qzh5CredentialStore
import com.xingheyuzhuan.shiguangschedule.service.qzh5.Qzh5SyncJavascriptBridge
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.vectorResource
import shiguangschedule.shared.generated.resources.Res
import shiguangschedule.shared.generated.resources.arrow_back_24px
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun Qzh5AutoSyncSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { Qzh5CredentialStore(context) }

    var hasCredentials by remember { mutableStateOf(store.hasCredentials()) }
    var enabled by remember { mutableStateOf(store.isAutoSyncEnabled()) }
    var intervalHours by remember { mutableIntStateOf(store.getSyncIntervalHours()) }
    var lastSyncAt by remember { mutableLongStateOf(store.getLastSyncAt()) }
    var lastStatus by remember { mutableStateOf(store.getLastSyncStatus()) }

    LaunchedEffect(Unit) {
        while (true) {
            hasCredentials = store.hasCredentials()
            enabled = store.isAutoSyncEnabled()
            intervalHours = store.getSyncIntervalHours()
            lastSyncAt = store.getLastSyncAt()
            lastStatus = store.getLastSyncStatus()
            delay(1500)
        }
    }

    fun send(action: String) {
        context.sendBroadcast(
            Intent(action).setPackage(context.packageName)
        )
    }

    val lastSyncText = remember(lastSyncAt) {
        if (lastSyncAt <= 0L) {
            "尚未执行"
        } else {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(lastSyncAt))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("云南交院自动同步") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            vectorResource(Res.drawable.arrow_back_24px),
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "自动同步课表",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                if (hasCredentials)
                                    "后台定时检查学校 qzh5 课表变化"
                                else
                                    "请先通过教务导入完成一次账号绑定",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enabled && hasCredentials,
                            enabled = hasCredentials,
                            onCheckedChange = { checked ->
                                store.setAutoSyncEnabled(checked)
                                enabled = checked
                                send(
                                    if (checked)
                                        Qzh5SyncJavascriptBridge.ACTION_QZH5_SYNC_CONFIGURED
                                    else
                                        Qzh5SyncJavascriptBridge.ACTION_QZH5_SYNC_DISABLED
                                )
                            }
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("同步频率", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Android 会根据系统后台策略在所选周期附近执行，并非严格准点。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    listOf(1, 2, 4, 6).forEach { hours ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = hasCredentials) {
                                    store.setSyncIntervalHours(hours)
                                    intervalHours = hours
                                    if (enabled) {
                                        send(Qzh5SyncJavascriptBridge.ACTION_QZH5_SYNC_CONFIGURED)
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = intervalHours == hours,
                                enabled = hasCredentials,
                                onClick = {
                                    store.setSyncIntervalHours(hours)
                                    intervalHours = hours
                                    if (enabled) {
                                        send(Qzh5SyncJavascriptBridge.ACTION_QZH5_SYNC_CONFIGURED)
                                    }
                                }
                            )
                            Text(hours.toString() + " 小时")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("同步状态", style = MaterialTheme.typography.titleMedium)
                    Text("上次同步：" + lastSyncText)
                    Text(
                        if (lastStatus.isBlank()) "状态：暂无记录" else "状态：" + lastStatus,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(4.dp))

                    Button(
                        onClick = {
                            store.setLastSyncStatus("正在请求立即同步…")
                            lastStatus = "正在请求立即同步…"
                            send(Qzh5SyncJavascriptBridge.ACTION_QZH5_SYNC_NOW)
                        },
                        enabled = hasCredentials,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("立即同步")
                    }
                }
            }

            Text(
                "检测到课程名称、教师、教室、星期、节次、周次或上课时间变化时，会自动覆盖更新当前绑定课表并发送通知。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
