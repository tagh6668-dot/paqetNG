package com.tagh6668.paqetng.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.tagh6668.paqetng.data.PaqetConfig
import com.tagh6668.paqetng.data.toPaqetYaml
import com.tagh6668.paqetng.ui.theme.PaqetNGTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel,
    onSettingsClick: () -> Unit = {},
    onLogsClick: () -> Unit = {},
    onConnect: ((PaqetConfig?) -> Unit)? = null,
    onDisconnect: (() -> Unit)? = null
) {
    val configs by viewModel.configs.collectAsState(initial = emptyList())
    val isRunning by viewModel.isRunning.collectAsState(initial = false)
    val selectedConfigId by viewModel.selectedConfigId.collectAsState()
    val editorConfig by viewModel.editorConfig.collectAsState()
    val deleteConfigId by viewModel.deleteConfigId.collectAsState()

    if (editorConfig != null) {
        BackHandler { viewModel.dismissEditor() }
        ConfigEditorScreen(
            config = editorConfig!!,
            onSave = viewModel::saveConfig,
            onDismiss = viewModel::dismissEditor
        )
        return
    }

    val selectedConfig = configs.find { it.id == selectedConfigId } ?: configs.firstOrNull()
    var exportConfig by remember { mutableStateOf<PaqetConfig?>(null) }
    var exportShowQr by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val showAddConfigDialog by viewModel.showAddConfigDialog.collectAsState()
    val showRootRequiredDialog by viewModel.showRootRequiredDialog.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    // Helper functions for export dialog state management
    val dismissExportDialog = {
        exportConfig = null
        exportShowQr = false
    }
    val showExportQrCode = {
        exportShowQr = true
    }
    val openExportDialog = { config: PaqetConfig ->
        exportConfig = config
        exportShowQr = false
    }

    if (showRootRequiredDialog) {
        RootRequiredDialog(onDismiss = viewModel::dismissRootRequiredDialog)
    }

    // Delete confirmation dialog
    val configToDelete = deleteConfigId?.let { id -> configs.find { it.id == id } }
    if (configToDelete != null) {
        DeleteConfirmDialog(
            config = configToDelete,
            onConfirm = viewModel::confirmDeleteConfig,
            onDismiss = viewModel::cancelDeleteConfig
        )
    }

    val qrScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.addConfigFromImport(it) }
    }

    if (showAddConfigDialog) {
        AddConfigDialog(
            onDismiss = viewModel::dismissAddConfigDialog,
            onFromClipboard = {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                val count = viewModel.addConfigsFromImportWithCount(text)
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (count == 0) "Invalid or empty clipboard" else if (count == 1) "Imported 1 config" else "Imported $count configs"
                    )
                }
                // ViewModel already dismisses dialog on success; ensure dismiss on failure too
                if (count == 0) viewModel.dismissAddConfigDialog()
            },
            onFromQrCode = {
                qrScanLauncher.launch(ScanOptions())
            },
            onAddManually = {
                viewModel.dismissAddConfigDialog()
                viewModel.openAdd()
            }
        )
    }

    if (exportConfig != null) {
        val currentExportConfig = exportConfig!!
        ExportDialog(
            config = currentExportConfig,
            showQr = exportShowQr,
            onDismiss = dismissExportDialog,
            onCopyToClipboard = {
                copyConfigToClipboard(context, currentExportConfig)
                dismissExportDialog()
            },
            onCopyFullConfigYaml = {
                copyConfigYamlToClipboard(context, currentExportConfig)
                dismissExportDialog()
            },
            onShowQrCode = showExportQrCode
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { snackbarData ->
                Snackbar(snackbarData)
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("paqetNG") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = onLogsClick) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Logs")
                    }
                    if (configs.isNotEmpty()) {
                        IconButton(onClick = {
                            val allText = viewModel.exportAllToClipboardText(configs)
                            copyAllConfigsToClipboard(context, allText)
                            scope.launch { snackbarHostState.showSnackbar("Exported ${configs.size} configs to clipboard") }
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Export all configs")
                        }
                    }
                    IconButton(onClick = { viewModel.openAddConfigDialog() }) {
                        Icon(Icons.Default.Add, contentDescription = "Add config")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            Box(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                val latencyMs by viewModel.latencyMs.collectAsState()
                val latencyTesting by viewModel.latencyTesting.collectAsState()
                val showLatencyInUi by viewModel.showLatencyInUi.collectAsState(initial = true)
                ConnectPanel(
                    selectedConfig = selectedConfig,
                    isRunning = isRunning,
                    latencyMs = latencyMs,
                    latencyTesting = latencyTesting,
                    showLatencyInUi = showLatencyInUi,
                    onStatusClick = {
                        Log.d("HomeScreen", "status area tapped: selectedConfig=${selectedConfig?.serverAddr}, isRunning=$isRunning, latencyMs=$latencyMs")
                        viewModel.testLatency(selectedConfig)
                    },
                    onConnect = {
                        if (onConnect != null) onConnect(selectedConfig)
                        else viewModel.connect(selectedConfig)
                    },
                    onDisconnect = onDisconnect ?: viewModel::disconnect
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(configs) { config ->
                ConfigItem(
                    config = config,
                    isSelected = config.id == selectedConfigId,
                    onSelect = { viewModel.selectConfig(config.id) },
                    onEdit = { viewModel.openEdit(config) },
                    onDelete = { viewModel.requestDeleteConfig(config.id) },
                    onExport = { openExportDialog(config) }
                )
            }
        }
    }
}

@Composable
private fun ConfigItem(
    config: PaqetConfig,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        val contentColor = if (isSelected)
            MaterialTheme.colorScheme.onPrimaryContainer
        else
            MaterialTheme.colorScheme.onSurface
        val supportingColor = if (isSelected)
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        else
            MaterialTheme.colorScheme.onSurfaceVariant
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = config.name.ifEmpty { config.serverAddr },
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor
                )
                Text(
                    text = config.serverAddr,
                    style = MaterialTheme.typography.bodyMedium,
                    color = supportingColor
                )
            }
            IconButton(onClick = onExport) {
                Icon(Icons.Default.Share, contentDescription = "Export", tint = contentColor)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = contentColor)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = contentColor)
            }
        }
    }
}

@Composable
private fun RootRequiredDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Root access required") },
        text = {
            Text(
                "This app needs root access to connect and route traffic. Without root, you can still browse configs and change settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Skip") }
        }
    )
}

@Composable
private fun DeleteConfirmDialog(
    config: PaqetConfig,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete profile") },
        text = {
            Text(
                "Are you sure you want to delete \"${config.name.ifEmpty { config.serverAddr }}\"? This action cannot be undone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun AddConfigDialog(
    onDismiss: () -> Unit,
    onFromClipboard: () -> Unit,
    onFromQrCode: () -> Unit,
    onAddManually: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add config") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Add a profile from clipboard, scan a QR code, or enter manually.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onFromClipboard,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("From clipboard") }
                Button(
                    onClick = onFromQrCode,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Scan QR code") }
                Button(
                    onClick = onAddManually,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add manually") }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun copyAllConfigsToClipboard(context: Context, allText: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("paqetNG configs", allText))
}

private fun copyConfigToClipboard(context: Context, config: PaqetConfig) {
    val paqetUri = config.toPaqetUri()
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("paqetNG config", paqetUri))
}

private fun copyConfigYamlToClipboard(context: Context, config: PaqetConfig) {
    val yaml = config.toPaqetYaml()
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("paqetNG config YAML", yaml))
}

@Composable
private fun ExportDialog(
    config: PaqetConfig,
    showQr: Boolean,
    onDismiss: () -> Unit,
    onCopyToClipboard: () -> Unit,
    onCopyFullConfigYaml: () -> Unit,
    onShowQrCode: () -> Unit
) {
    if (showQr) {
        val paqetUri = remember(config) { config.toPaqetUri() }
        val density = LocalDensity.current
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val sizePx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(256)
                val bitmap = remember(paqetUri, sizePx) {
                    runCatching {
                        val matrix: BitMatrix = MultiFormatWriter().encode(
                            paqetUri,
                            BarcodeFormat.QR_CODE,
                            sizePx,
                            sizePx
                        )
                        val w = matrix.width
                        val h = matrix.height
                        val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        for (x in 0 until w) for (y in 0 until h) {
                            bmp[x, y] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                        }
                        bmp
                    }.getOrNull()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    )
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Export profile") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Copy the share link (paqet://), full paqet YAML config, or show as QR code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onCopyToClipboard,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Copy to clipboard (paqet:// link)") }
                    Button(
                        onClick = onCopyFullConfigYaml,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Copy full config (YAML)") }
                    Button(
                        onClick = onShowQrCode,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Show QR code") }
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) { Text("Cancel") }
            }
        )
    }
}

// Match v2rayNG: strip 64dp, FAB overlaps with marginBottom 36dp, marginEnd 16dp
private val BOTTOM_STRIP_HEIGHT = 64.dp
private val FAB_OVERLAP_BOTTOM = 36.dp
private val FAB_MARGIN_END = 16.dp
private val FAB_SIZE = 56.dp
// FAB uses theme: primary when connected, surfaceVariant when disconnected

@Composable
private fun ConnectPanel(
    modifier: Modifier = Modifier,
    selectedConfig: PaqetConfig?,
    isRunning: Boolean,
    latencyMs: Int?,
    latencyTesting: Boolean,
    showLatencyInUi: Boolean = true,
    onStatusClick: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    // Total height so FAB (56dp) with bottom 36dp from container bottom fits without clipping
    val totalHeight = FAB_OVERLAP_BOTTOM + FAB_SIZE
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
    ) {
        // Flat strip at bottom (like v2rayNG layout_test): full width, 64dp, divider on top
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(BOTTOM_STRIP_HEIGHT)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(enabled = isRunning) { if (isRunning) onStatusClick() }
        ) {
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .padding(end = FAB_SIZE + FAB_MARGIN_END),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = selectedConfig?.let { it.name.ifEmpty { it.serverAddr } } ?: "No config",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when {
                            latencyTesting -> "Testing…"
                            isRunning && showLatencyInUi && latencyMs == -1 -> "Connection failed · -1ms"
                            isRunning && showLatencyInUi && latencyMs != null && latencyMs >= 0 -> "Connected · ${latencyMs}ms"
                            isRunning -> "Connected, tap to check connection"
                            else -> "Not connected"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            latencyTesting -> MaterialTheme.colorScheme.onSurfaceVariant
                            isRunning && showLatencyInUi && latencyMs == -1 -> MaterialTheme.colorScheme.error
                            isRunning && showLatencyInUi && latencyMs != null && latencyMs >= 0 -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (latencyTesting) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }
        // FAB overlapping strip (v2rayNG: bottom|end, marginBottom 36dp, marginEnd 16dp)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = FAB_OVERLAP_BOTTOM, end = FAB_MARGIN_END)
                .size(FAB_SIZE)
                .clip(CircleShape)
                .background(
                    if (isRunning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .clickable {
                    if (isRunning) onDisconnect() else onConnect()
                }
        ) {
            if (isRunning) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Disconnect",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Connect",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectPanelPreview() {
    PaqetNGTheme {
        ConnectPanel(
            selectedConfig = null,
            isRunning = false,
            latencyMs = null,
            latencyTesting = false,
            showLatencyInUi = true,
            onStatusClick = { },
            onConnect = { },
            onDisconnect = { }
        )
    }
}
