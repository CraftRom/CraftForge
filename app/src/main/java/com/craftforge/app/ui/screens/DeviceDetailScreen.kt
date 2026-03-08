package com.craftforge.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.craftforge.app.data.DeviceInfoItem
import com.craftforge.app.data.DeviceInfoSection
import com.craftforge.app.ui.components.BatteryInfoBlock
import com.craftforge.app.ui.components.DeviceInfoRow
import com.craftforge.app.ui.components.HeadBlock
import com.craftforge.app.ui.components.RamInfoBlock
import com.craftforge.app.ui.components.StorageInfoBlock
import com.craftforge.app.ui.theme.CpuGraphsGrid
import com.craftforge.app.ui.theme.StyledBlockCard
import com.craftforge.app.ui.theme.infoCardStyles
import com.craftforge.app.ui.viewmodel.DeviceDetailViewModel
import com.craftforge.app.ui.viewmodel.DeviceInfoViewModel

@Composable
fun DeviceDetailScreen(viewModel: DeviceDetailViewModel) {
    val tabs = listOf("Overview", "System", "CPU & GPU", "Memory", "Battery", "Display & Cam", "Connectivity")
    var selectedTab by remember { mutableIntStateOf(0) }
    val styles = infoCardStyles()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { paddingVals ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingVals)
        ) {
            DeviceHeader(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))

            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = styles.accentColor,
                edgePadding = 8.dp
            ) {
                tabs.forEachIndexed { index, text ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (selectedTab == index) styles.accentColor else styles.labelTextColor,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    )
                }
            }

            when (selectedTab) {
                0 -> DashboardTab(viewModel.headData)
                1 -> SectionedDataTab(viewModel.systemData)
                2 -> CpuGpuTab(viewModel.cpuGpuData, viewModel.cpuHistoryData, viewModel.cpuMaxFreq)
                3 -> SectionedDataTab(viewModel.memoryData)
                4 -> SectionedDataTab(viewModel.batteryDetailsData)
                5 -> SectionedDataTab(viewModel.displayCameraData)
                6 -> SectionedDataTab(viewModel.connectivityData)
            }
        }
    }
}

@Composable
private fun DeviceHeader(modifier: Modifier = Modifier) {
    val styles = infoCardStyles()
    Column(modifier = modifier) {
        Text(
            text = "Device Information",
            style = MaterialTheme.typography.headlineMedium,
            color = styles.titleTextColor,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Grouped hardware, ROM and runtime diagnostics",
            style = MaterialTheme.typography.bodyMedium,
            color = styles.labelTextColor
        )
    }
}

@Composable
fun DashboardTab(headDataState: State<List<DeviceInfoItem>>) {
    val headData by headDataState
    val styles = infoCardStyles()

    val viewModel: DeviceInfoViewModel = viewModel()
    val ram by viewModel.ramData.collectAsState()
    val storage by viewModel.storageData.collectAsState()
    val battery by viewModel.batteryData.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            if (headData.isNotEmpty()) {
                val deviceName = headData.firstOrNull { it.label == "Device" }?.value ?: "N/A"
                HeadBlock(title = deviceName, items = headData, styles = styles, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RamInfoBlock(ram, styles)
                StorageInfoBlock(storage, styles)
                BatteryInfoBlock(battery, styles)
            }
        }
    }
}

@Composable
fun SectionedDataTab(dataState: State<List<DeviceInfoSection>>) {
    val sections by dataState
    val styles = infoCardStyles()

    if (sections.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "Data not loaded.", color = styles.titleTextColor, fontSize = 16.sp)
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sections, key = { it.title }) { section ->
            StyledBlockCard(title = section.title, styles = styles) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    section.items.forEachIndexed { index, item ->
                        DeviceInfoRow(item = item, styles = styles, showDivider = index != section.items.lastIndex)
                    }
                }
            }
        }
    }
}

@Composable
fun CpuGpuTab(
    dataState: State<List<DeviceInfoSection>>,
    cpuHistoriesState: State<List<List<Float>>>,
    maxFreqState: State<Float>
) {
    val sections by dataState
    val cpuHistories by cpuHistoriesState
    val maxFreq by maxFreqState
    val styles = infoCardStyles()

    if (sections.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "Data not loaded.", color = styles.titleTextColor, fontSize = 16.sp)
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            CpuGraphsGrid(histories = cpuHistories, maxFreq = maxFreq, styles = styles)
        }
        items(sections, key = { it.title }) { section ->
            StyledBlockCard(title = section.title, styles = styles) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    section.items.forEachIndexed { index, item ->
                        DeviceInfoRow(item = item, styles = styles, showDivider = index != section.items.lastIndex)
                    }
                }
            }
        }
    }
}