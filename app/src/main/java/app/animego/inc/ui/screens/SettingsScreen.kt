package app.animego.inc.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.PowerManager
import android.os.Build
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.animego.inc.AppGraph
import app.animego.inc.ui.components.ScreenHeader
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark by graph.settings.darkTheme.collectAsState(initial = true)
    val dynamic by graph.settings.dynamicColor.collectAsState(initial = false)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("pengaturan", "preferensi aplikasi dan pemutaran", action = {
            TextButton(onClick = onBack) { Text("selesai") }
        })
        SettingRow("tema gelap", "gunakan tampilan gelap AnimeGo") {
            Switch(checked = dark, onCheckedChange = { value -> scope.launch { graph.settings.setDarkTheme(value) } })
        }
        SettingRow("warna dinamis", "ikuti warna sistem pada Android 12 ke atas") {
            Switch(checked = dynamic, onCheckedChange = { value -> scope.launch { graph.settings.setDynamicColor(value) } })
        }
        SettingRow("optimasi baterai", "izinkan AnimeGo tetap berjalan untuk unduhan dan pemutaran latar") {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            TextButton(onClick = {
                if (!power.isIgnoringBatteryOptimizations(context.packageName)) {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                } else context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }) { Text(if (power.isIgnoringBatteryOptimizations(context.packageName)) "dikelola sistem" else "atur") }
        }
        SettingRow("aktivitas latar", "atur izin autostart dan pembatasan aplikasi dari sistem") {
            TextButton(onClick = { openBackgroundSettings(context) }) { Text("kelola") }
        }
        SettingRow("floating player", "izinkan jendela video di atas aplikasi lain") {
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }) { Text(if (Settings.canDrawOverlays(context)) "diizinkan" else "izinkan") }
        }
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("animego native", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("versi 2.0.0 · Kotlin + Jetpack Compose", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("backend: karanime.com · database lokal: animego.db", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("ui, networking, player, dan unduhan dijalankan secara native tanpa WebView.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, description: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        control()
    }
}

private fun openBackgroundSettings(context: Context) {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val opened = if (manufacturer.contains("vivo")) {
        runCatching {
            context.startActivity(Intent().setClassName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ))
        }.isSuccess
    } else false

    if (!opened) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}
