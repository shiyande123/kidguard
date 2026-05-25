package com.kidguard.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kidguard.data.model.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "设置",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        val currentSettings = settings
        if (currentSettings == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "开关设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SwitchRow(
                        label = "启用监控",
                        checked = currentSettings.isEnabled,
                        onCheckedChange = {
                            viewModel.updateSettings(currentSettings.copy(isEnabled = it))
                        }
                    )
                    SwitchRow(
                        label = "锁定时通知",
                        checked = currentSettings.notifyParent,
                        onCheckedChange = {
                            viewModel.updateSettings(currentSettings.copy(notifyParent = it))
                        }
                    )
                    SwitchRow(
                        label = "锁定提示音",
                        checked = currentSettings.soundEnabled,
                        onCheckedChange = {
                            viewModel.updateSettings(currentSettings.copy(soundEnabled = it))
                        }
                    )
                }
            }

            Text(
                text = "参数设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SliderRow(
                        label = "每日限制",
                        value = currentSettings.dailyTimeLimit.toFloat(),
                        onValueChangeFinished = { value ->
                            viewModel.updateSettings(currentSettings.copy(dailyTimeLimit = value.toInt()))
                        },
                        valueRange = 30f..480f,
                        valueText = "${currentSettings.dailyTimeLimit} 分钟"
                    )
                    SliderRow(
                        label = "锁定延迟",
                        value = currentSettings.lockDelay.toFloat(),
                        onValueChangeFinished = { value ->
                            viewModel.updateSettings(currentSettings.copy(lockDelay = value.toInt()))
                        },
                        valueRange = 1f..30f,
                        valueText = "${currentSettings.lockDelay} 秒"
                    )
                    SliderRow(
                        label = "灵敏度",
                        value = currentSettings.sensitivity,
                        onValueChangeFinished = { value ->
                            viewModel.updateSettings(currentSettings.copy(sensitivity = value))
                        },
                        valueRange = 0.3f..1.0f,
                        valueText = "${(currentSettings.sensitivity * 100).toInt()}%"
                    )
                }
            }

            Text(
                text = "人脸识别模型",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ArcFace 模型（已集成）",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "buffalo_s (512维嵌入)，无需下载",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("✓", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Text(
                text = "工作时间",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TimeRow(
                        label = "开始时间",
                        hour = currentSettings.workingHoursStart,
                        onHourChange = {
                            viewModel.updateSettings(currentSettings.copy(workingHoursStart = it))
                        }
                    )
                    TimeRow(
                        label = "结束时间",
                        hour = currentSettings.workingHoursEnd,
                        onHourChange = {
                            viewModel.updateSettings(currentSettings.copy(workingHoursEnd = it))
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    onValueChangeFinished: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String
) {
    // Local state for smooth slider drag, persist only on release
    var sliderValue by remember(value) { mutableFloatStateOf(value) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChangeFinished(sliderValue) },
            valueRange = valueRange
        )
    }
}

@Composable
private fun TimeRow(
    label: String,
    hour: Int,
    onHourChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (hour > 0) onHourChange(hour - 1)
            }) {
                Text("-", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = String.format("%02d:00", hour),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            IconButton(onClick = {
                if (hour < 23) onHourChange(hour + 1)
            }) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
