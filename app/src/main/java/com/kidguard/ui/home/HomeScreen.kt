package com.kidguard.ui.home

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.kidguard.lock.DeviceAdminManager
import com.kidguard.ui.navigation.Screen
import com.kidguard.ui.theme.ShieldGreen
import com.kidguard.ui.theme.AlertOrange
import com.kidguard.util.DebugLog
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(SingletonComponent::class)
interface AdminEntryPoint {
    fun deviceAdminManager(): DeviceAdminManager
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val children by viewModel.children.collectAsState()
    val isMonitoring by viewModel.isMonitoring.collectAsState()
    val context = LocalContext.current

    val adminManager = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, AdminEntryPoint::class.java).deviceAdminManager()
    }
    var isAdminActive by remember { mutableStateOf(adminManager.isAdminActive()) }

    // #22: Refresh admin status on every Lifecycle resume
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAdminActive = adminManager.isAdminActive()
                DebugLog.log("HomeVM", "onResume: isAdminActive=$isAdminActive")
            }
        })
    }

    // #21: Handle navigation events from ViewModel (instead of direct startActivity)
    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is HomeViewModel.NavigationEvent.RequestBatteryOptimization -> {
                    event.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(event.intent)
                }
            }
        }
    }

    // 设备管理员激活结果回调
    val adminLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // 返回后重新检查状态
        isAdminActive = adminManager.isAdminActive()
        DebugLog.log("HomeVM", "Admin result: isAdminActive=$isAdminActive")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KidGuard 儿童守护", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            // 管理员权限
            if (!isAdminActive) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("⚠️ 设备管理员未激活", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                    Text("无法自动锁屏，请点击下方按钮激活", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminManager.getAdminComponent())
                                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "KidGuard 需要设备管理员权限来实现自动锁屏功能")
                                    }
                                    adminLauncher.launch(intent)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Shield, null)
                                Spacer(Modifier.width(8.dp))
                                Text("一键激活设备管理员", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 监控状态
            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (isMonitoring) ShieldGreen.copy(alpha = 0.12f) else AlertOrange.copy(alpha = 0.12f))) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Shield, null, tint = if (isMonitoring) ShieldGreen else AlertOrange, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (isMonitoring) "监控已启动" else "监控已暂停", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (isMonitoring) ShieldGreen else AlertOrange)
                                Text("设备管理员: ${if (isAdminActive) "✅ 已激活" else "❌ 未激活"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = { viewModel.toggleMonitoring() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(if (isMonitoring) Icons.Default.Warning else Icons.Default.CheckCircle, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isMonitoring) "暂停监控" else "启动监控")
                        }
                    }
                }
            }

            // 🔧 调试：直接锁屏测试
            if (isAdminActive) {
                item {
                    OutlinedButton(
                        onClick = {
                            adminManager.lockNow()
                            Toast.makeText(context, "锁屏！", Toast.LENGTH_SHORT).show()
                            DebugLog.log("HomeVM", "Direct lock test triggered")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Lock, null)
                        Spacer(Modifier.width(8.dp))
                        Text("🔧 测试直接锁屏（验证管理员权限）")
                    }
                }
            }

            // 儿童列表
            item { Text("已添加的儿童", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }

            if (children.isEmpty()) {
                item {
                    Card(onClick = { navController.navigate(Screen.AddChild.route) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(8.dp))
                            Text("添加第一个儿童", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            } else {
                items(children, key = { it.id }) { child ->
                    Card(modifier = Modifier.fillMaxWidth(), onClick = { navController.navigate(Screen.FaceEnroll.createRoute(child.id)) }) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Face, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(child.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text("年龄: ${child.age}岁", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (child.faceEmbedding != null) Icon(Icons.Default.CheckCircle, null, tint = ShieldGreen, modifier = Modifier.size(24.dp))
                            else Icon(Icons.Default.Warning, null, tint = AlertOrange, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }

            // 快捷操作
            item { Text("快捷操作", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { navController.navigate(Screen.Children.route) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Face, null); Spacer(Modifier.width(8.dp)); Text("儿童管理")
                    }
                    OutlinedButton(onClick = { navController.navigate(Screen.Logs.route) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Lock, null); Spacer(Modifier.width(8.dp)); Text("锁定日志")
                    }
                }
            }

            // 调试日志
            item {
                OutlinedButton(
                    onClick = { DebugLog.copyLogToClipboard(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, null)
                    Spacer(Modifier.width(8.dp))
                    Text("复制调试日志")
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
