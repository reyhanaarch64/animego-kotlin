package app.animego.inc.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.animego.inc.AppGraph
import app.animego.inc.ui.uiText
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(graph: AppGraph, registration: Boolean, onBack: () -> Unit, onSwitchMode: () -> Unit, onSuccess: () -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    fun launchAuth(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = false
            message = ""
            try {
                block()
                if (graph.auth.isSignedIn) runCatching { graph.sync.syncNow() }
                message = if (registration && email.isNotBlank()) "pendaftaran berhasil. cek email untuk verifikasi." else "login berhasil."
                onSuccess()
            } catch (e: Exception) {
                error = true
                message = e.message?.substringAfterLast(':')?.trim()?.ifBlank { "autentikasi gagal." } ?: "autentikasi gagal."
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(uiText(if (registration) "buat akun animego" else "login dengan email"), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(uiText("kembali")) }
        }
        if (registration) {
            Text(uiText("buat akun baru untuk menyimpan profil dan sinkronisasi data AnimeGo."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(displayName, { displayName = it }, Modifier.fillMaxWidth(), label = { Text(uiText("nama tampilan")) }, singleLine = true)
        }
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text(uiText("email")) }, singleLine = true)
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(uiText("password")) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        if (registration) {
            OutlinedTextField(confirmPassword, { confirmPassword = it }, Modifier.fillMaxWidth(), label = { Text(uiText("konfirmasi password")) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
        Button(
            onClick = {
                launchAuth {
                    if (registration) {
                        require(password == confirmPassword) { "konfirmasi password tidak sama." }
                        graph.auth.register(email, password, displayName)
                    } else graph.auth.login(email, password)
                }
            },
            enabled = !busy && email.isNotBlank() && password.isNotBlank() && (!registration || displayName.trim().length >= 2),
            modifier = Modifier.fillMaxWidth()
        ) { Text(uiText(if (busy) "memproses..." else if (registration) "daftar dengan email" else "login dengan email")) }

        if (!registration) {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    error = false
                    message = ""
                    runCatching { graph.auth.sendPasswordReset(email) }
                        .onSuccess { message = "link reset password dikirim ke email lu." }
                        .onFailure { error = true; message = it.message.orEmpty().ifBlank { "gagal mengirim link reset password." } }
                    busy = false
                }
            }, enabled = !busy && email.isNotBlank()) { Text(uiText("lupa password")) }
        }
        if (registration) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                OutlinedButton(onClick = {
                    if (activity == null) {
                        error = true
                        message = "activity android tidak tersedia untuk membuka pemilih akun google."
                    } else launchAuth { graph.auth.signInWithGoogle(activity, true) }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(uiText("daftar dengan google"))
                }
            }
        }
        if (message.isNotBlank()) Text(uiText(message), color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(uiText(if (registration) "sudah punya akun?" else "belum punya akun?"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onSwitchMode) { Text(uiText(if (registration) "login" else "daftar")) }
        }
    }
}


@Composable
fun GoogleAuthLaunchScreen(graph: AppGraph, registration: Boolean, onSuccess: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    var message by remember { mutableStateOf("membuka pemilihan akun google...") }
    var busy by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (activity == null) {
            message = "activity android tidak tersedia untuk membuka pemilih akun google."
        } else {
            busy = true
            runCatching {
                graph.auth.signInWithGoogle(activity, registration)
                graph.sync.syncNow()
            }.onSuccess {
                onSuccess()
            }.onFailure {
                message = it.message?.substringAfterLast(':')?.trim()?.ifBlank { "autentikasi google gagal." } ?: "autentikasi google gagal."
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.Center) {
        if (busy) CircularProgressIndicator()
        Text(uiText(message), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!busy) TextButton(onClick = onBack) { Text(uiText("kembali")) }
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
