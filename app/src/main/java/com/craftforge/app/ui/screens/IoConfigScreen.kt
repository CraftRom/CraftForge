package com.craftforge.app.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.craftforge.app.R
import com.craftforge.app.ui.theme.SettingsBadgeRow
import com.craftforge.app.ui.theme.SettingsDropdownRow
import com.craftforge.app.ui.theme.SettingsSwitchRow
import com.craftforge.app.ui.theme.StyledBlockCard
import com.craftforge.app.ui.theme.infoCardStyles
import com.craftforge.app.util.KernelOperationNotifier
import com.craftforge.app.util.RootManager
import com.craftforge.app.util.ZramApplyStage
import com.craftforge.app.util.ZramManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun IoConfigScreen(isRooted: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("TweaksPrefs", Context.MODE_PRIVATE)
    val styles = infoCardStyles()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val loadingText = stringResource(R.string.common_loading)
    val lockedText = stringResource(R.string.common_locked)
    val unknownText = stringResource(R.string.common_unknown)
    val virtualUnknownText = stringResource(R.string.common_virtual_unknown)

    val readAheadLevels = listOf(
        stringResource(R.string.io_readahead_128),
        stringResource(R.string.io_readahead_256),
        stringResource(R.string.io_readahead_512),
        stringResource(R.string.io_readahead_1024),
        stringResource(R.string.io_readahead_2048)
    )
    val nrRequestsLevels = listOf(
        stringResource(R.string.io_nr_requests_64),
        stringResource(R.string.io_nr_requests_128),
        stringResource(R.string.io_nr_requests_256),
        stringResource(R.string.io_nr_requests_512)
    )
    val swappinessLevels = listOf(
        stringResource(R.string.io_swappiness_0),
        stringResource(R.string.io_swappiness_30),
        stringResource(R.string.io_swappiness_60),
        stringResource(R.string.io_swappiness_100),
        stringResource(R.string.io_swappiness_150)
    )
    val pageClusterLevels = listOf(
        stringResource(R.string.io_page_cluster_0),
        stringResource(R.string.io_page_cluster_1),
        stringResource(R.string.io_page_cluster_2),
        stringResource(R.string.io_page_cluster_3)
    )
    val vfsLevels = listOf(
        stringResource(R.string.io_vfs_10),
        stringResource(R.string.io_vfs_50),
        stringResource(R.string.io_vfs_100),
        stringResource(R.string.io_vfs_150)
    )
    val watermarkLevels = listOf(
        stringResource(R.string.io_watermark_10),
        stringResource(R.string.io_watermark_50),
        stringResource(R.string.io_watermark_100),
        stringResource(R.string.io_watermark_200)
    )
    val dirtyRatioLevels = listOf(
        stringResource(R.string.io_dirty_ratio_5),
        stringResource(R.string.io_dirty_ratio_10),
        stringResource(R.string.io_dirty_ratio_20),
        stringResource(R.string.io_dirty_ratio_30)
    )
    val dirtyBgRatioLevels = listOf(
        stringResource(R.string.io_dirty_bg_ratio_2),
        stringResource(R.string.io_dirty_bg_ratio_5),
        stringResource(R.string.io_dirty_bg_ratio_10),
        stringResource(R.string.io_dirty_bg_ratio_15)
    )

    var blockDevice by remember { mutableStateOf(virtualUnknownText) }
    var blockPath by remember { mutableStateOf<String?>(null) }
    var schedulerPath by remember { mutableStateOf<String?>(null) }
    var currentIo by remember { mutableStateOf(loadingText) }
    var availableIos by remember { mutableStateOf(emptyList<String>()) }
    var currentReadAhead by remember { mutableStateOf(loadingText) }
    var readAheadPath by remember { mutableStateOf<String?>(null) }
    var ioStatsPath by remember { mutableStateOf<String?>(null) }
    var isIoStatsEnabled by remember { mutableStateOf(false) }
    var addRandomPath by remember { mutableStateOf<String?>(null) }
    var isAddRandomEnabled by remember { mutableStateOf(false) }
    var nrRequestsPath by remember { mutableStateOf<String?>(null) }
    var currentNrRequests by remember { mutableStateOf(loadingText) }

    var zramCompPath by remember { mutableStateOf<String?>(null) }
    var currentZramComp by remember { mutableStateOf(loadingText) }
    var availableZramComps by remember { mutableStateOf(emptyList<String>()) }
    var isApplyingZram by remember { mutableStateOf(false) }
    var zramProgress by remember { mutableStateOf<Float?>(null) }
    var zramProgressLabel by remember { mutableStateOf<String?>(null) }

    var swappinessPath by remember { mutableStateOf<String?>(null) }
    var currentSwappiness by remember { mutableStateOf(loadingText) }
    var pageClusterPath by remember { mutableStateOf<String?>(null) }
    var currentPageCluster by remember { mutableStateOf(loadingText) }
    var vfsPath by remember { mutableStateOf<String?>(null) }
    var currentVfs by remember { mutableStateOf(loadingText) }

    var mglruPath by remember { mutableStateOf<String?>(null) }
    var isMglruEnabled by remember { mutableStateOf(false) }
    var watermarkPath by remember { mutableStateOf<String?>(null) }
    var currentWatermark by remember { mutableStateOf(loadingText) }
    var dirtyRatioPath by remember { mutableStateOf<String?>(null) }
    var currentDirtyRatio by remember { mutableStateOf(loadingText) }
    var dirtyBgRatioPath by remember { mutableStateOf<String?>(null) }
    var currentDirtyBgRatio by remember { mutableStateOf(loadingText) }

    fun parseSchedulerValue(raw: String): String {
        return raw.substringAfter("[", raw).substringBefore("]").trim()
    }

    fun displayForRaw(raw: String?, options: List<String>): String {
        val clean = raw?.trim().orEmpty()
        if (clean.isBlank()) return unknownText
        return options.firstOrNull { it.startsWith(clean) }
            ?: context.getString(R.string.common_custom_format, clean)
    }

    fun zramStageText(stage: ZramApplyStage): String = when (stage) {
        ZramApplyStage.PREPARING -> context.getString(R.string.zram_progress_preparing)
        ZramApplyStage.SWAPOFF -> context.getString(R.string.zram_progress_swapoff)
        ZramApplyStage.RESETTING -> context.getString(R.string.zram_progress_reset)
        ZramApplyStage.SETTING_ALGO -> context.getString(R.string.zram_progress_set_algo)
        ZramApplyStage.RESTORING_SIZE -> context.getString(R.string.zram_progress_restore_size)
        ZramApplyStage.REENABLING_SWAP -> context.getString(R.string.zram_progress_reenable_swap)
        ZramApplyStage.VERIFYING -> context.getString(R.string.zram_progress_verify)
        ZramApplyStage.DONE -> context.getString(R.string.zram_progress_done)
    }

    LaunchedEffect(isRooted) {
        if (isRooted) {
            withContext(Dispatchers.IO) {
                fun probeNode(vararg paths: String): Pair<String, String>? {
                    for (path in paths) {
                        val value = RootManager.readNode(path)?.trim().orEmpty()
                        if (value.isNotBlank() && RootManager.nodeWritable(path)) {
                            return path to value
                        }
                    }
                    return null
                }

                val sdaProbe = probeNode("/sys/block/sda/queue/scheduler")
                val mmcProbe = probeNode("/sys/block/mmcblk0/queue/scheduler")
                when {
                    sdaProbe != null -> {
                        blockDevice = context.getString(R.string.io_device_ufs)
                        blockPath = "/sys/block/sda/queue"
                        schedulerPath = sdaProbe.first
                        currentIo = parseSchedulerValue(sdaProbe.second)
                        availableIos = sdaProbe.second.replace("[", "").replace("]", "").split(" ").filter { it.isNotBlank() }
                    }
                    mmcProbe != null -> {
                        blockDevice = context.getString(R.string.io_device_emmc)
                        blockPath = "/sys/block/mmcblk0/queue"
                        schedulerPath = mmcProbe.first
                        currentIo = parseSchedulerValue(mmcProbe.second)
                        availableIos = mmcProbe.second.replace("[", "").replace("]", "").split(" ").filter { it.isNotBlank() }
                    }
                    else -> {
                        blockDevice = virtualUnknownText
                        blockPath = null
                        schedulerPath = null
                        currentIo = unknownText
                    }
                }

                if (blockPath != null) {
                    val readAheadProbe = probeNode("$blockPath/read_ahead_kb")
                    readAheadPath = readAheadProbe?.first
                    currentReadAhead = readAheadProbe?.second?.let { context.getString(R.string.io_kb_format, it) } ?: unknownText

                    val ioStatsProbe = probeNode("$blockPath/iostats")
                    ioStatsPath = ioStatsProbe?.first
                    isIoStatsEnabled = ioStatsProbe?.second == "1"

                    val addRandomProbe = probeNode("$blockPath/add_random")
                    addRandomPath = addRandomProbe?.first
                    isAddRandomEnabled = addRandomProbe?.second == "1"

                    val nrReqProbe = probeNode("$blockPath/nr_requests")
                    nrRequestsPath = nrReqProbe?.first
                    currentNrRequests = displayForRaw(nrReqProbe?.second, nrRequestsLevels)
                }

                val zramCompProbe = probeNode("/sys/block/zram0/comp_algorithm")
                zramCompPath = zramCompProbe?.first
                if (zramCompProbe != null) {
                    val raw = zramCompProbe.second
                    currentZramComp = ZramManager.parseCurrentAlgorithm(raw)
                    availableZramComps = raw.replace("[", "").replace("]", "").split(" ").filter { it.isNotBlank() }
                }

                val swappinessProbe = probeNode("/proc/sys/vm/swappiness")
                swappinessPath = swappinessProbe?.first
                currentSwappiness = displayForRaw(swappinessProbe?.second, swappinessLevels)

                val pcProbe = probeNode("/proc/sys/vm/page-cluster")
                pageClusterPath = pcProbe?.first
                currentPageCluster = displayForRaw(pcProbe?.second, pageClusterLevels)

                val vfsProbe = probeNode("/proc/sys/vm/vfs_cache_pressure")
                vfsPath = vfsProbe?.first
                currentVfs = displayForRaw(vfsProbe?.second, vfsLevels)

                val mglruProbe = probeNode("/sys/kernel/mm/lru_gen/enabled")
                mglruPath = mglruProbe?.first
                isMglruEnabled = mglruProbe?.second?.let { it.contains("y") || it.contains("0x0001") || it.contains("0x0003") || it.contains("0x0007") } == true

                val wmProbe = probeNode("/proc/sys/vm/watermark_scale_factor")
                watermarkPath = wmProbe?.first
                currentWatermark = displayForRaw(wmProbe?.second, watermarkLevels)

                val drProbe = probeNode("/proc/sys/vm/dirty_ratio")
                dirtyRatioPath = drProbe?.first
                currentDirtyRatio = displayForRaw(drProbe?.second, dirtyRatioLevels)

                val dbgProbe = probeNode("/proc/sys/vm/dirty_background_ratio")
                dirtyBgRatioPath = dbgProbe?.first
                currentDirtyBgRatio = displayForRaw(dbgProbe?.second, dirtyBgRatioLevels)
            }
        } else {
            currentIo = lockedText
            currentReadAhead = lockedText
            currentNrRequests = lockedText
            currentZramComp = lockedText
            currentSwappiness = lockedText
            currentPageCluster = lockedText
            currentVfs = lockedText
            currentWatermark = lockedText
            currentDirtyRatio = lockedText
            currentDirtyBgRatio = lockedText
        }
    }

    fun writeSimpleValue(path: String?, value: String, afterWrite: () -> Unit) {
        if (path.isNullOrBlank()) return
        scope.launch(Dispatchers.IO) {
            val result = RootManager.writeNode(path, value, verify = true)
            if (result.ok) afterWrite()
        }
    }

    ConfigScreenLayout(title = stringResource(R.string.io_screen_title), styles = styles, onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 16.dp)
        ) {
            val showStorageSection = schedulerPath != null || readAheadPath != null || nrRequestsPath != null || addRandomPath != null || ioStatsPath != null || !isRooted
            if (showStorageSection) {
                StyledBlockCard(styles = styles, title = stringResource(R.string.io_section_storage)) {
                    SettingsBadgeRow(
                        title = stringResource(R.string.io_device_type),
                        subtitle = stringResource(R.string.io_device_type_subtitle),
                        value = blockDevice,
                        isRooted = isRooted,
                        styles = styles
                    )

                    if (schedulerPath != null || !isRooted) {
                        HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = stringResource(R.string.io_scheduler_title),
                            subtitle = stringResource(R.string.io_scheduler_subtitle),
                            currentValue = currentIo,
                            availableValues = availableIos,
                            isRooted = isRooted,
                            styles = styles
                        ) { selected ->
                            currentIo = selected
                            writeSimpleValue(schedulerPath, selected) {
                                prefs.edit().putString("saved_scheduler", selected).apply()
                            }
                        }
                    }

                    if (readAheadPath != null || !isRooted) {
                        HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = stringResource(R.string.io_readahead_title),
                            subtitle = stringResource(R.string.io_readahead_subtitle),
                            currentValue = currentReadAhead,
                            availableValues = readAheadLevels,
                            isRooted = isRooted,
                            styles = styles
                        ) { selected ->
                            currentReadAhead = selected
                            val kb = selected.substringBefore(" ")
                            writeSimpleValue(readAheadPath, kb) {
                                prefs.edit().putString("saved_readahead", kb).apply()
                            }
                        }
                    }

                    if (nrRequestsPath != null || !isRooted) {
                        HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = stringResource(R.string.io_queue_depth_title),
                            subtitle = stringResource(R.string.io_queue_depth_subtitle),
                            currentValue = currentNrRequests,
                            availableValues = nrRequestsLevels,
                            isRooted = isRooted,
                            styles = styles
                        ) { selected ->
                            currentNrRequests = selected
                            val value = selected.substringBefore(" ")
                            writeSimpleValue(nrRequestsPath, value) {
                                prefs.edit().putString("saved_nr_requests", value).apply()
                            }
                        }
                    }

                    if (addRandomPath != null || !isRooted) {
                        HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsSwitchRow(
                            title = stringResource(R.string.io_add_random_title),
                            subtitle = stringResource(R.string.io_add_random_subtitle),
                            checked = isAddRandomEnabled,
                            styles = styles
                        ) { checked ->
                            isAddRandomEnabled = checked
                            val value = if (checked) "1" else "0"
                            writeSimpleValue(addRandomPath, value) {
                                prefs.edit().putString("saved_add_random", value).apply()
                            }
                        }
                    }

                    if (ioStatsPath != null || !isRooted) {
                        HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsSwitchRow(
                            title = stringResource(R.string.io_iostats_title),
                            subtitle = stringResource(R.string.io_iostats_subtitle),
                            checked = isIoStatsEnabled,
                            styles = styles
                        ) { checked ->
                            isIoStatsEnabled = checked
                            val value = if (checked) "1" else "0"
                            writeSimpleValue(ioStatsPath, value) {
                                prefs.edit().putString("saved_iostats", value).apply()
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            val showVmSection = zramCompPath != null || swappinessPath != null || pageClusterPath != null || vfsPath != null || !isRooted
            if (showVmSection) {
                StyledBlockCard(styles = styles, title = stringResource(R.string.io_section_vm)) {
                    if (zramCompPath != null || !isRooted) {
                        SettingsDropdownRow(
                            title = stringResource(R.string.io_zram_title),
                            subtitle = stringResource(R.string.io_zram_subtitle),
                            currentValue = currentZramComp,
                            availableValues = availableZramComps,
                            isRooted = isRooted,
                            styles = styles,
                            isBusy = isApplyingZram,
                            progress = zramProgress,
                            progressText = zramProgressLabel
                        ) { selected ->
                            val previous = currentZramComp
                            scope.launch(Dispatchers.IO) {
                                withContext(Dispatchers.Main) {
                                    isApplyingZram = true
                                    zramProgress = 0f
                                    zramProgressLabel = context.getString(R.string.zram_row_progress_format, 0, context.getString(R.string.zram_progress_preparing))
                                }
                                KernelOperationNotifier.showProgress(context, context.getString(R.string.zram_progress_preparing), 0)

                                val result = ZramManager.applyCompressionAlgorithm(selected) { stage, progressValue ->
                                    val stageText = zramStageText(stage)
                                    withContext(Dispatchers.Main) {
                                        zramProgress = progressValue / 100f
                                        zramProgressLabel = context.getString(R.string.zram_row_progress_format, progressValue, stageText)
                                    }
                                    KernelOperationNotifier.showProgress(context, stageText, progressValue)
                                }

                                val success = result.ok
                                var refreshedCurrent = selected
                                var refreshedAvailable = availableZramComps
                                if (success) {
                                    prefs.edit().putString("saved_zram_comp", selected).apply()
                                    zramCompPath?.let { path ->
                                        RootManager.readNode(path, forceRefresh = true)?.let { raw ->
                                            refreshedAvailable = raw.replace("[", "").replace("]", "").split(" ").filter { it.isNotBlank() }
                                            refreshedCurrent = ZramManager.parseCurrentAlgorithm(raw)
                                        }
                                    }
                                }

                                KernelOperationNotifier.showFinished(
                                    context,
                                    if (success) context.getString(R.string.zram_progress_done) else context.getString(R.string.zram_progress_failed),
                                    success
                                )
                                withContext(Dispatchers.Main) {
                                    if (success) {
                                        availableZramComps = refreshedAvailable
                                        currentZramComp = refreshedCurrent
                                    } else {
                                        currentZramComp = previous
                                    }
                                    Toast.makeText(
                                        context,
                                        if (success) context.getString(R.string.zram_toast_success) else context.getString(R.string.zram_toast_failure),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    zramProgress = null
                                    zramProgressLabel = null
                                    isApplyingZram = false
                                }
                            }
                        }
                    }

                    if (swappinessPath != null || !isRooted) {
                        HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = stringResource(R.string.io_zram_swappiness_title),
                            subtitle = stringResource(R.string.io_zram_swappiness_subtitle),
                            currentValue = currentSwappiness,
                            availableValues = swappinessLevels,
                            isRooted = isRooted,
                            styles = styles
                        ) { selected ->
                            currentSwappiness = selected
                            val value = selected.substringBefore(" ")
                            writeSimpleValue(swappinessPath, value) {
                                prefs.edit().putString("saved_swappiness", value).apply()
                            }
                        }
                    }

                    if (pageClusterPath != null || !isRooted) {
                        HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = stringResource(R.string.io_page_cluster_title),
                            subtitle = stringResource(R.string.io_page_cluster_subtitle),
                            currentValue = currentPageCluster,
                            availableValues = pageClusterLevels,
                            isRooted = isRooted,
                            styles = styles
                        ) { selected ->
                            currentPageCluster = selected
                            val value = selected.substringBefore(" ")
                            writeSimpleValue(pageClusterPath, value) {
                                prefs.edit().putString("saved_page_cluster", value).apply()
                            }
                        }
                    }

                    if (vfsPath != null || !isRooted) {
                        HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = stringResource(R.string.io_vfs_title),
                            subtitle = stringResource(R.string.io_vfs_subtitle),
                            currentValue = currentVfs,
                            availableValues = vfsLevels,
                            isRooted = isRooted,
                            styles = styles
                        ) { selected ->
                            currentVfs = selected
                            val value = selected.substringBefore(" ")
                            writeSimpleValue(vfsPath, value) {
                                prefs.edit().putString("saved_vfs", value).apply()
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            val showAdvVmSection = mglruPath != null || watermarkPath != null || !isRooted
            if (showAdvVmSection) {
                StyledBlockCard(styles = styles, title = stringResource(R.string.io_section_adv_vm)) {
                    if (mglruPath != null || !isRooted) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.io_mglru_title),
                            subtitle = stringResource(R.string.io_mglru_subtitle),
                            checked = isMglruEnabled,
                            styles = styles
                        ) { checked ->
                            isMglruEnabled = checked
                            val value = if (checked) "y" else "n"
                            writeSimpleValue(mglruPath, value) {
                                prefs.edit().putString("saved_mglru", value).apply()
                            }
                        }
                    }

                    if (watermarkPath != null || !isRooted) {
                        HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = stringResource(R.string.io_watermark_title),
                            subtitle = stringResource(R.string.io_watermark_subtitle),
                            currentValue = currentWatermark,
                            availableValues = watermarkLevels,
                            isRooted = isRooted,
                            styles = styles
                        ) { selected ->
                            currentWatermark = selected
                            val value = selected.substringBefore(" ")
                            writeSimpleValue(watermarkPath, value) {
                                prefs.edit().putString("saved_watermark_scale", value).apply()
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            val showDirtySection = dirtyRatioPath != null || dirtyBgRatioPath != null || !isRooted
            if (showDirtySection) {
                StyledBlockCard(styles = styles, title = stringResource(R.string.io_section_dirty)) {
                    if (dirtyRatioPath != null || !isRooted) {
                        SettingsDropdownRow(
                            title = stringResource(R.string.io_dirty_ratio_title),
                            subtitle = stringResource(R.string.io_dirty_ratio_subtitle),
                            currentValue = currentDirtyRatio,
                            availableValues = dirtyRatioLevels,
                            isRooted = isRooted,
                            styles = styles
                        ) { selected ->
                            currentDirtyRatio = selected
                            val value = selected.substringBefore(" ")
                            writeSimpleValue(dirtyRatioPath, value) {
                                prefs.edit().putString("saved_dirty_ratio", value).apply()
                            }
                        }
                    }

                    if (dirtyBgRatioPath != null || !isRooted) {
                        HorizontalDivider(color = styles.titleTextColor.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsDropdownRow(
                            title = stringResource(R.string.io_dirty_bg_ratio_title),
                            subtitle = stringResource(R.string.io_dirty_bg_ratio_subtitle),
                            currentValue = currentDirtyBgRatio,
                            availableValues = dirtyBgRatioLevels,
                            isRooted = isRooted,
                            styles = styles
                        ) { selected ->
                            currentDirtyBgRatio = selected
                            val value = selected.substringBefore(" ")
                            writeSimpleValue(dirtyBgRatioPath, value) {
                                prefs.edit().putString("saved_dirty_bg_ratio", value).apply()
                            }
                        }
                    }
                }
            }
        }
    }
}
