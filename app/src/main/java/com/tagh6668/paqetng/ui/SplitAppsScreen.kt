package com.tagh6668.paqetng.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.tagh6668.paqetng.R
import com.tagh6668.paqetng.data.AppInfo
import com.tagh6668.paqetng.data.AppListRepository
import com.tagh6668.paqetng.data.SettingsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitAppsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedApps by settingsRepository.splitApps.collectAsState(initial = emptySet())
    val showSystem by settingsRepository.splitShowSystem.collectAsState(initial = false)
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(showSystem) {
        loading = true
        apps = AppListRepository(context).loadApps(showSystem)
        loading = false
    }

    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        val list = apps ?: emptyList()
        if (q.isEmpty()) list else list.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.split_apps_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.pref_split_show_system), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = showSystem, onCheckedChange = { scope.launch { settingsRepository.setSplitShowSystem(it) } })
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.split_apps_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val all = filtered.map { it.packageName }.toSet()
                    scope.launch { settingsRepository.setSplitApps(selectedApps + all) }
                }) { Text(stringResource(R.string.split_apps_select_all)) }
                TextButton(onClick = {
                    val ids = filtered.map { it.packageName }.toSet()
                    scope.launch { settingsRepository.setSplitApps(selectedApps - ids) }
                }) { Text(stringResource(R.string.split_apps_deselect_all)) }
                Text(
                    text = if (selectedApps.isEmpty()) stringResource(R.string.pref_split_apps_summary_none)
                    else stringResource(R.string.pref_split_apps_summary, selectedApps.size),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                loading -> {
                    Column(modifier = Modifier.fillMaxSize().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                    }
                }
                filtered.isEmpty() -> {
                    Text(stringResource(R.string.split_apps_empty), modifier = Modifier.padding(top = 24.dp).align(Alignment.CenterHorizontally))
                }
                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxSize()) {
                        items(filtered, key = { it.packageName }) { app ->
                            val checked = app.packageName in selectedApps
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        scope.launch {
                                            val next = if (isChecked) selectedApps + app.packageName else selectedApps - app.packageName
                                            settingsRepository.setSplitApps(next)
                                        }
                                    }
                                )
                                // App icon (best effort)
                                val icon = remember(app.packageName) {
                                    try { context.packageManager.getApplicationIcon(app.packageName) } catch (_: Exception) { null }
                                }
                                if (icon != null) {
                                    val bitmap = remember(icon) {
                                        try { icon.toBitmap(48, 48).asImageBitmap() } catch (_: Exception) { null }
                                    }
                                    if (bitmap != null) {
                                        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(28.dp).padding(end = 8.dp))
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
