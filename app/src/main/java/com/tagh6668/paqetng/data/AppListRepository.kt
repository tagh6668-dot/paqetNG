package com.tagh6668.paqetng.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean
)

class AppListRepository(private val context: Context) {
    suspend fun loadApps(includeSystem: Boolean): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        apps.mapNotNull { ai ->
            val pkg = ai.packageName ?: return@mapNotNull null
            // Always include self so user can see it, but we handle its routing in VPN service
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && isSystem) return@mapNotNull null
            val label = try { pm.getApplicationLabel(ai).toString() } catch (_: Exception) { pkg }
            AppInfo(pkg, label, isSystem)
        }.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
    }
}
