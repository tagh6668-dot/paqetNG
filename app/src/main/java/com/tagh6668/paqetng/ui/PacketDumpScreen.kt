package com.tagh6668.paqetng.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tagh6668.paqetng.data.PaqetConfig
import com.tagh6668.paqetng.data.TcpdumpBuffer
import com.tagh6668.paqetng.paqet.TcpdumpRunner

/** Protocol filter for tcpdump lines */
enum class TcpdumpFilter { All, TCP, UDP, ICMP }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacketDumpScreen(
    tcpdumpBuffer: TcpdumpBuffer,
    tcpdumpRunner: TcpdumpRunner,
    isPaqetRunning: Boolean,
    connectionMode: String,
    connectedConfig: PaqetConfig?,
    latencyMs: Int?,
    latencyTesting: Boolean,
    onTestConnection: () -> Unit,
    onBack: () -> Unit,
    onOpenPacketDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val lines by tcpdumpBuffer.lines.collectAsState(initial = emptyList())
    val isTcpdumpRunning by tcpdumpRunner.isRunning.collectAsState(initial = false)
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var filter by remember { mutableStateOf(TcpdumpFilter.All) }
    var autoScroll by remember { mutableStateOf(true) }

    /** Server host for tcpdump filter: only show packets between phone and server */
    val hostFilter = remember(connectedConfig?.serverAddr) {
        connectedConfig?.serverAddr?.let { addr ->
            if (addr.contains(':')) addr.substringBeforeLast(':').trim()
            else addr.trim()
        }?.takeIf { it.isNotEmpty() }
    }

    /** In SOCKS-only mode use network interface (e.g. wlan0); in VPN mode use tun0 */
    val tcpdumpInterface = remember(connectionMode, connectedConfig?.networkInterface) {
        if (connectionMode == "socks") {
            connectedConfig?.networkInterface?.trim()?.takeIf { it.isNotBlank() } ?: "any"
        } else {
            null
        }
    }

    /** Pairs of (index in full list, line) for correct getPacketBlock(index) when filtering */
    val filteredWithIndex = remember(lines, filter) {
        when (filter) {
            TcpdumpFilter.All -> lines.mapIndexed { i, s -> i to s }
            TcpdumpFilter.TCP -> lines.mapIndexed { i, s -> i to s }.filter { (_, s) -> s.contains(" TCP ", ignoreCase = true) }
            TcpdumpFilter.UDP -> lines.mapIndexed { i, s -> i to s }.filter { (_, s) -> s.contains(" UDP ", ignoreCase = true) }
            TcpdumpFilter.ICMP -> lines.mapIndexed { i, s -> i to s }.filter { (_, s) -> s.contains("ICMP", ignoreCase = true) }
        }
    }

    // tcpdump runs ONLY while this screen is visible: start when user enters, stop when user leaves
    LaunchedEffect(isPaqetRunning, hostFilter, tcpdumpInterface) {
        if (isPaqetRunning) tcpdumpRunner.start(hostFilter = hostFilter, interfaceName = tcpdumpInterface)
        else tcpdumpRunner.stop()
    }
    DisposableEffect(Unit) {
        onDispose {
            // Stop tcpdump as soon as user leaves this screen (back or navigate away)
            tcpdumpRunner.stop()
        }
    }
    LaunchedEffect(filteredWithIndex.size) {
        if (autoScroll && filteredWithIndex.isNotEmpty()) listState.animateScrollToItem(filteredWithIndex.size - 1)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Packet dump") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(
                            if (autoScroll) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (autoScroll) "Pause auto-scroll" else "Resume auto-scroll"
                        )
                    }
                    IconButton(onClick = { tcpdumpBuffer.clear() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear")
                    }
                    IconButton(
                        onClick = {
                            val text = tcpdumpBuffer.getFullText()
                            if (text.isNotEmpty()) {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                    ?.setPrimaryClip(ClipData.newPlainText("paqetNG tcpdump", text))
                            }
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Export to clipboard")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Test connection strip (like Home screen)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = isPaqetRunning && !latencyTesting) { if (isPaqetRunning) onTestConnection() }
            ) {
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = connectedConfig?.let { it.name.ifEmpty { it.serverAddr } } ?: "No profile",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            text = when {
                                latencyTesting -> "Testing…"
                                isPaqetRunning && latencyMs == -1 -> "Connection failed · -1ms"
                                isPaqetRunning && latencyMs != null && latencyMs >= 0 -> "Connected · ${latencyMs}ms · tap to test"
                                isPaqetRunning -> "Tap to test connection"
                                else -> "Connect (VPN or SOCKS) to see packets to/from server"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                latencyTesting -> MaterialTheme.colorScheme.onSurfaceVariant
                                isPaqetRunning && latencyMs == -1 -> MaterialTheme.colorScheme.error
                                isPaqetRunning && latencyMs != null && latencyMs >= 0 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (latencyTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Filter:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilterChip(
                    selected = filter == TcpdumpFilter.All,
                    onClick = { filter = TcpdumpFilter.All },
                    label = { Text("All") }
                )
                FilterChip(
                    selected = filter == TcpdumpFilter.TCP,
                    onClick = { filter = TcpdumpFilter.TCP },
                    label = { Text("TCP") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2E7D32),
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = filter == TcpdumpFilter.UDP,
                    onClick = { filter = TcpdumpFilter.UDP },
                    label = { Text("UDP") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF1565C0),
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = filter == TcpdumpFilter.ICMP,
                    onClick = { filter = TcpdumpFilter.ICMP },
                    label = { Text("ICMP") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF455A64),
                        selectedLabelColor = Color.White
                    )
                )
            }
            if (!isPaqetRunning) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Connect first (VPN or SOCKS). Packet dump runs tcpdump on ${if (connectionMode == "socks") "network interface" else "TUN"} (requires root).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (lines.isEmpty() && !isTcpdumpRunning) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Starting tcpdump… (root required). Showing only packets to/from server. Generate traffic to see packets.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (filteredWithIndex.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (lines.isEmpty())
                            "No packets to/from server yet. Send or receive data over VPN."
                        else
                            "No entries match the current filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(
                            filteredWithIndex,
                            key = { _, pair -> "tcpdump-${pair.first}-${pair.second.hashCode()}" }
                        ) { _, pair ->
                            val (globalIndex, line) = pair
                            val packetBlock = tcpdumpBuffer.getPacketBlock(globalIndex)
                            TcpdumpLine(
                                line = line,
                                onClick = { onOpenPacketDetail(packetBlock) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TcpdumpLine(
    line: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor) = when {
        line.contains(" TCP ", ignoreCase = true) -> Color(0xFFE8F5E9) to Color(0xFF1B5E20)
        line.contains(" UDP ", ignoreCase = true) -> Color(0xFFE3F2FD) to Color(0xFF0D47A1)
        line.contains("ICMP", ignoreCase = true) -> Color(0xFFECEFF1) to Color(0xFF455A64)
        line.contains("Flags [", ignoreCase = true) -> Color(0xFFFFF8E1) to Color(0xFFE65100)
        else -> Color.Transparent to MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            maxLines = 2
        )
    }
}
