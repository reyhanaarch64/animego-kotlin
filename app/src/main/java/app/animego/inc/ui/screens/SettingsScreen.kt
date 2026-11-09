package app.animego.inc.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import app.animego.inc.AppGraph
import app.animego.inc.ui.components.ScreenHeader
import app.animego.inc.ui.uiText
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark by graph.settings.darkTheme.collectAsState(initial = true)
    val dynamic by graph.settings.dynamicColor.collectAsState(initial = false)
    val language by graph.settings.languageTag.collectAsState(initial = "system")
    var clearDialog by remember { mutableStateOf(false) }
    var cache by remember { mutableStateOf(graph.cache.calculate()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(5000L)
            cache = graph.cache.calculate()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(uiText("pengaturan"), uiText("preferensi aplikasi dan pemutaran"), action = {
            TextButton(onClick = onBack) { Text(uiText("selesai")) }
        })
        Text(uiText("bahasa aplikasi"), modifier = Modifier.padding(start = 18.dp, top = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        listOf("system" to "ikuti bahasa sistem", "id" to "indonesia", "en" to "english").forEach { (tag, label) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = language == tag, onClick = {
                    scope.launch {
                        graph.settings.setLanguage(tag)
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(if (tag == "system") "" else tag))
                    }
                })
                Text(uiText(label))
            }
        }
        SettingRow(uiText("tema gelap"), uiText("gunakan tampilan gelap AnimeGo")) {
            Switch(checked = dark, onCheckedChange = { value -> scope.launch { graph.settings.setDarkTheme(value) } })
        }
        SettingRow(uiText("warna dinamis"), uiText("ikuti warna sistem pada Android 12 ke atas")) {
            Switch(checked = dynamic, onCheckedChange = { value -> scope.launch { graph.settings.setDynamicColor(value) } })
        }

        Text(uiText("penyimpanan"), modifier = Modifier.padding(start = 18.dp, top = 16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(uiText("total cache aplikasi"), style = MaterialTheme.typography.titleSmall)
                    Text(formatBytes(cache.total), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                CacheSizeRow(uiText("cache avatar"), cache.avatars)
                CacheSizeRow(uiText("cache gambar"), cache.images)
                CacheSizeRow(uiText("file sementara"), cache.temporary)
                TextButton(onClick = { clearDialog = true }, modifier = Modifier.align(Alignment.End)) { Text(uiText("bersihkan cache")) }
            }
        }

        SettingRow(uiText("optimasi baterai"), uiText("izinkan AnimeGo tetap berjalan untuk unduhan dan pemutaran latar")) {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            TextButton(onClick = {
                if (!power.isIgnoringBatteryOptimizations(context.packageName)) {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                } else context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }) { Text(uiText(if (power.isIgnoringBatteryOptimizations(context.packageName)) "dikelola sistem" else "atur")) }
        }
        SettingRow(uiText("aktivitas latar"), uiText("atur izin autostart dan pembatasan aplikasi dari sistem")) {
            TextButton(onClick = { openBackgroundSettings(context) }) { Text(uiText("kelola")) }
        }
        SettingRow(uiText("floating player"), uiText("izinkan jendela video di atas aplikasi lain")) {
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }) { Text(uiText(if (Settings.canDrawOverlays(context)) "diizinkan" else "izinkan")) }
        }
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(uiText("animego native"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(uiText("versi 2.0.0 · Kotlin + Jetpack Compose"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(uiText("backend: karanime.com · database lokal: animego.db"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(uiText("ui, networking, player, dan unduhan dijalankan secara native tanpa WebView."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (clearDialog) AlertDialog(
        onDismissRequest = { clearDialog = false },
        title = { Text(uiText("bersihkan cache aplikasi?")) },
        text = { Text(uiText("cache avatar, thumbnail, dan file sementara akan dihapus. data akun dan download offline tetap aman.")) },
        confirmButton = {
            TextButton(onClick = {
                graph.cache.clear()
                graph.database.clearAvatarCacheRows()
                cache = graph.cache.calculate()
                clearDialog = false
            }) { Text(uiText("bersihkan")) }
        },
        dismissButton = { TextButton(onClick = { clearDialog = false }) { Text(uiText("batal")) } }
    )
}

@Composable
private fun CacheSizeRow(label: String, bytes: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatBytes(bytes), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingRow(title: String, description: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
    if (!opened) runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
