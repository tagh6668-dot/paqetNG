package com.tagh6668.paqetng.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tagh6668.paqetng.data.SettingsRepository
import com.tagh6668.paqetng.data.ThemePref
import com.tagh6668.paqetng.ui.theme.PaqetNGTheme

/**
 * Activity that shows full tcpdump packet detail (TCP flags, seq, ack, etc.).
 * Launched from Packet Dump screen when user taps a packet line.
 */
class PacketDetailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val packetText = intent.getStringExtra(EXTRA_PACKET_TEXT) ?: ""
        val settingsRepository = SettingsRepository(this)
        setContent {
            val theme by settingsRepository.theme.collectAsState(initial = ThemePref.SYSTEM)
            val darkTheme = when (theme) {
                ThemePref.LIGHT -> false
                ThemePref.DARK -> true
                ThemePref.SYSTEM -> isSystemInDarkTheme()
            }
            PaqetNGTheme(darkTheme = darkTheme) {
                PacketDetailScreen(
                    packetText = packetText,
                    onBack = { finish() },
                    onCopy = {
                        (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)
                            ?.setPrimaryClip(ClipData.newPlainText("tcpdump packet", packetText))
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PACKET_TEXT = "packet_text"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacketDetailScreen(
    packetText: String,
    onBack: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Packet detail") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
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
            ParsedSummary(packetText)
            SelectionContainer {
                Text(
                    text = packetText.ifEmpty { "(No packet data)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
private fun ParsedSummary(packetText: String) {
    val flags = parseTcpFlags(packetText)
    val seqAck = parseSeqAck(packetText)
    val length = parseLength(packetText)
    val srcDst = parseSrcDst(packetText)
    if (flags == null && seqAck == null && length == null && srcDst == null) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        srcDst?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace) }
        flags?.let { Text("Flags: $it", style = MaterialTheme.typography.labelMedium, color = Color(0xFF1565C0)) }
        seqAck?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace) }
        length?.let { Text("Length: $it", style = MaterialTheme.typography.labelMedium) }
    }
}

private fun parseTcpFlags(line: String): String? {
    val m = Regex("Flags \\[([^]]+)\\]").find(line) ?: return null
    return m.groupValues[1]
}

private fun parseSeqAck(line: String): String? {
    val seq = Regex("seq ([^,]+)").find(line)?.groupValues?.get(1) ?: return null
    val ack = Regex("ack ([^,]+)").find(line)?.groupValues?.get(1)
    return if (ack != null) "seq $seq, ack $ack" else "seq $seq"
}

private fun parseLength(line: String): String? {
    return Regex("length (\\d+)").find(line)?.groupValues?.get(1)
}

private fun parseSrcDst(line: String): String? {
    val m = Regex("(\\d+\\.\\d+\\.\\d+\\.\\d+\\.\\d+)\\s*>\\s*(\\d+\\.\\d+\\.\\d+\\.\\d+\\.\\d+)").find(line)
        ?: return null
    return "${m.groupValues[1]} > ${m.groupValues[2]}"
}
