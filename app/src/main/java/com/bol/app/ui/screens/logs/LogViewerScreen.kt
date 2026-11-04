package com.bol.app.ui.screens.logs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bol.app.data.local.entity.PerformanceLogEntity
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import com.bol.app.monitoring.MetricsSnapshot
import com.bol.app.ui.components.HeedButton
import com.bol.app.ui.components.HeedButtonStyle
import com.bol.app.ui.components.HeedCard
import com.bol.app.ui.components.HeedChip
import com.bol.app.ui.components.RowDivider
import com.bol.app.ui.components.TopBar
import com.bol.app.ui.theme.HeedTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit,
    viewModel: LogViewerViewModel = koinViewModel()
) {
    val logs by viewModel.filteredLogs.collectAsState()
    val logCount by viewModel.logCount.collectAsState()
    val liveMetrics by viewModel.liveMetrics.collectAsState()
    val selectedCategories by viewModel.selectedCategories.collectAsState()
    val selectedLevel by viewModel.selectedLevel.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch {
                val json = viewModel.exportLogsAsJson()
                context.contentResolver.openOutputStream(it)?.use { out ->
                    out.write(json.toByteArray())
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopBar(
                title = "Logs",
                subtitle = "$logCount entries",
                onBack = onBack,
                actions = {
                    HeedButton(
                        text = "Export",
                        style = HeedButtonStyle.Text,
                        onClick = { exportLauncher.launch("bol-logs-${System.currentTimeMillis()}.json") },
                    )
                    IconButton(onClick = { viewModel.clearAll() }) {
                        Icon(Icons.Default.Delete, "Clear all", tint = HeedTheme.colors.textSecondary)
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Live metrics cards
            liveMetrics?.let { metrics ->
                LiveMetricsBar(metrics)
            }

            // Category filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LogCategory.entries.forEach { cat ->
                    HeedChip(
                        label = cat.name,
                        selected = cat in selectedCategories,
                        onClick = { viewModel.toggleCategory(cat) },
                    )
                }
            }

            // Level filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LogLevel.entries.forEach { level ->
                    HeedChip(
                        label = level.name,
                        selected = selectedLevel == level,
                        onClick = { viewModel.setLevelFilter(level) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Log list
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No logs yet", style = MaterialTheme.typography.bodyLarge,
                        color = HeedTheme.colors.textMuted)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(logs, key = { it.id }) { entry ->
                        LogEntryRow(entry)
                        RowDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveMetricsBar(metrics: MetricsSnapshot) {
    HeedCard(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricItem("Java Heap", "${metrics.javaHeapUsedMb.toInt()}/${metrics.javaHeapMaxMb.toInt()}MB")
                MetricItem("Native", "${metrics.nativeHeapMb.toInt()}MB")
                MetricItem("Avail RAM", "${metrics.systemAvailRamMb.toInt()}/${metrics.systemTotalRamMb.toInt()}MB")
                MetricItem("CPU", "${metrics.cpuUsagePercent.toInt()}%")
                MetricItem("Thermal", thermalLabel(metrics.thermalStatus))
                MetricItem("Batt", "${metrics.batteryPercent}%${if (metrics.isCharging) " \u26A1" else ""}")
            }
            if (metrics.systemLowMemory) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "LOW MEMORY WARNING — System is under memory pressure",
                    style = MaterialTheme.typography.labelSmall,
                    color = HeedTheme.colors.danger,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.labelLarge, color = HeedTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = HeedTheme.colors.textMuted, fontSize = 9.sp)
    }
}

@Composable
private fun LogEntryRow(entry: PerformanceLogEntity) {
    var expanded by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("MM/dd HH:mm:ss.SSS", Locale.US) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Level badge
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(levelColor(entry.level))
            )
            Spacer(modifier = Modifier.width(6.dp))

            // Timestamp
            Text(
                timeFormat.format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = HeedTheme.colors.textMuted,
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.width(6.dp))

            // Category tag
            Text(
                entry.category,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = HeedTheme.colors.brand,
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.width(6.dp))

            // Process
            if (entry.processName == ":ai") {
                Text(":ai", style = MaterialTheme.typography.labelSmall,
                    color = HeedTheme.colors.textSecondary, fontSize = 9.sp)
                Spacer(modifier = Modifier.width(4.dp))
            }

            // Event name
            Text(
                entry.event,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Duration
            entry.durationMs?.let { ms ->
                Text(
                    formatDuration(ms),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = HeedTheme.colors.textSecondary,
                    fontSize = 10.sp
                )
            }

            // Expand indicator
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = HeedTheme.colors.textMuted
            )
        }

        // Message (always visible if present)
        if (entry.message.isNotBlank()) {
            Text(
                entry.message,
                style = MaterialTheme.typography.bodySmall,
                color = HeedTheme.colors.textMuted,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp),
                fontSize = 11.sp
            )
        }

        // Expanded details — parse JSON outside composable scope, then render
        val extras: Map<String, String>? = remember(entry.extrasJson) {
            entry.extrasJson?.takeIf { it.isNotBlank() }?.let {
                runCatching { Json.decodeFromString<Map<String, String>>(it) }.getOrNull()
            }
        }
        val metrics: MetricsSnapshot? = remember(entry.metricsJson) {
            entry.metricsJson?.takeIf { it.isNotBlank() }?.let {
                runCatching { Json.decodeFromString<MetricsSnapshot>(it) }.getOrNull()
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 14.dp, top = 4.dp)) {
                extras?.forEach { (key, value) ->
                    Row {
                        Text("$key: ", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        Text(value, style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
                metrics?.let { m ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Java Heap: ${m.javaHeapUsedMb.toInt()}/${m.javaHeapMaxMb.toInt()}MB | " +
                            "Native Heap: ${m.nativeHeapMb.toInt()}MB | " +
                            "System RAM: ${m.systemAvailRamMb.toInt()}/${m.systemTotalRamMb.toInt()}MB free" +
                            (if (m.systemLowMemory) " [LOW]" else "") + "\n" +
                            "CPU: ${m.cpuUsagePercent.toInt()}% | " +
                            "Threads: ${m.threadCount} | " +
                            "Thermal: ${thermalLabel(m.thermalStatus)} | " +
                            "Battery: ${m.batteryPercent}% at ${m.batteryTemperature}\u00B0C${if (m.isCharging) " (charging)" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = HeedTheme.colors.textMuted,
                        fontSize = 9.sp,
                        lineHeight = 13.sp
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun levelColor(level: String): Color {
    return when (level) {
        "ERROR" -> com.bol.app.ui.theme.HeedTheme.colors.danger
        "WARN" -> com.bol.app.ui.theme.Tone.Ideas.fg(com.bol.app.ui.theme.HeedTheme.colors.isDark)
        "INFO" -> com.bol.app.ui.theme.HeedTheme.colors.brand
        "DEBUG" -> com.bol.app.ui.theme.HeedTheme.colors.textMuted
        else -> com.bol.app.ui.theme.HeedTheme.colors.textMuted
    }
}

private fun thermalLabel(status: Int): String {
    return when (status) {
        0 -> "None"
        1 -> "Light"
        2 -> "Moderate"
        3 -> "Severe"
        4 -> "Critical"
        5 -> "Emergency"
        6 -> "Shutdown"
        else -> "N/A"
    }
}

private fun formatDuration(ms: Long): String {
    return when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000}.${(ms % 1000) / 100}s"
        else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
    }
}
