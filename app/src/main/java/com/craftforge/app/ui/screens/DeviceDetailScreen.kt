package com.craftforge.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.craftforge.app.data.DeviceInfoItem
import com.craftforge.app.ui.components.*
import com.craftforge.app.ui.theme.CpuGraphsGrid
import com.craftforge.app.ui.theme.infoCardStyles
import com.craftforge.app.ui.viewmodel.DeviceDetailViewModel
import com.craftforge.app.ui.viewmodel.DeviceInfoViewModel

@Composable
fun DeviceDetailScreen(viewModel: DeviceDetailViewModel) {
    val tabs = listOf("Overview", "System", "CPU & GPU", "Memory", "Battery", "Display & Cam", "Connectivity")
    var selectedTab by remember { mutableIntStateOf(0) }

    // УВАГА: Ми БІЛЬШЕ НЕ ЧИТАЄМО стани тут через "by".
    // Ми будемо передавати їх як State об'єкти у вкладки. Це рятує ScrollableTabRow від рекомпозиції.

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { paddingVals ->
        Column(Modifier.fillMaxSize().padding(paddingVals)) {
            Text(
                text = "Device Information",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(16.dp)
            )

            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 0.dp
            ) {
                tabs.forEachIndexed { index, text ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = text,
                                color = if (selectedTab == index) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            // Рендеримо відповідну вкладку, передаючи САМ СТАН (не його значення)
            when (selectedTab) {
                0 -> DashboardTab(viewModel.headData)
                1 -> ListDataTab(viewModel.systemData, "System Details")
                2 -> CpuGpuTab(viewModel.cpuGpuData, viewModel.cpuHistoryData, viewModel.cpuMaxFreq, "Processor & Graphics")
                3 -> ListDataTab(viewModel.memoryData, "Memory")
                4 -> ListDataTab(viewModel.batteryDetailsData, "Battery")
                5 -> ListDataTab(viewModel.displayCameraData, "Display & Camera")
                6 -> ListDataTab(viewModel.connectivityData, "Network & Sensors")
            }
        }
    }
}

@Composable
fun DashboardTab(headDataState: State<List<DeviceInfoItem>>) {
    // Читаємо стан ТІЛЬКИ ТУТ
    val headData by headDataState
    val styles = infoCardStyles() // Кешуємо стилі, щоб не створювати об'єкт щоразу

    val viewModel: DeviceInfoViewModel = viewModel()
    val ram by viewModel.ramData.collectAsState()
    val storage by viewModel.storageData.collectAsState()
    val battery by viewModel.batteryData.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 8.dp)
    ) {
        item {
            Column {
                if (headData.isNotEmpty()) {
                    val deviceName = headData.firstOrNull { it.label == "Device" }?.value ?: "N/A"
                    HeadBlock(title = deviceName, items = headData, styles = styles)
                    Spacer(Modifier.height(8.dp))
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    RamInfoBlock(ram, styles)
                    Spacer(modifier = Modifier.height(12.dp))
                    StorageInfoBlock(storage, styles)
                    Spacer(modifier = Modifier.height(12.dp))
                    BatteryInfoBlock(battery, styles)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun ListDataTab(dataState: State<List<DeviceInfoItem>>, blockTitle: String) {
    // Читаємо стан ТІЛЬКИ ТУТ. Оновлюється лише ця вкладка.
    val data by dataState
    val styles = infoCardStyles()

    if (data.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "Data not loaded.", color = styles.titleTextColor, fontSize = 16.sp)
        }
        return
    }

    // Використовуємо індекси замість створення нових списків через drop/take
    val hasHeadItems = data.size >= 3

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 8.dp)
    ) {
        if (hasHeadItems) {
            item {
                // Беремо перші 3 елементи без виділення нової пам'яті під список
                val headItems = listOf(data[0], data[1], data[2])
                HeadBlock(title = blockTitle, items = headItems, styles = styles)
            }
        }

        // ОПТИМІЗАЦІЯ: Додано key = { it.label }. Тепер Compose не перемальовує зайве!
        items(
            items = if (hasHeadItems) data.subList(3, data.size) else data,
            key = { it.label }
        ) { item ->
            DeviceInfoRow(item = item, styles = styles)
        }
    }
}

@Composable
fun CpuGpuTab(
    dataState: State<List<DeviceInfoItem>>,
    cpuHistoriesState: State<List<List<Float>>>,
    maxFreqState: State<Float>,
    blockTitle: String
) {
    val data by dataState
    val cpuHistories by cpuHistoriesState
    val maxFreq by maxFreqState
    val styles = infoCardStyles()

    if (data.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "Data not loaded.", color = styles.titleTextColor, fontSize = 16.sp)
        }
        return
    }

    val hasHeadItems = data.size >= 3

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 8.dp)
    ) {
        if (hasHeadItems) {
            item {
                val headItems = listOf(data[0], data[1], data[2])
                HeadBlock(title = blockTitle, items = headItems, styles = styles)
            }
        }

        item {
            CpuGraphsGrid(histories = cpuHistories, maxFreq = maxFreq, styles = styles)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ОПТИМІЗАЦІЯ: Ключі (key) також тут
        items(
            items = if (hasHeadItems) data.subList(3, data.size) else data,
            key = { it.label }
        ) { item ->
            DeviceInfoRow(item = item, styles = styles)
        }
    }
}