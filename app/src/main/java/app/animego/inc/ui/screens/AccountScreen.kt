package app.animego.inc.ui.screens

import androidx.compose.foundation.layout.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil3.compose.AsyncImage
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.animego.inc.AppGraph
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(graph: AppGraph, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var avatarUrl by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var register by remember { mutableStateOf(false) }
    var signedUid by remember { mutableStateOf(graph.auth.currentUser?.uid) }
    var anonymous by remember { mutableStateOf(graph.auth.currentUser?.isAnonymous == true) }

    LaunchedEffect(signedUid) {
        if (signedUid != null && anonymous.not()) {
            runCatching { graph.social.getUser(signedUid!!)?.let { username = it.username; displayName = it.displayName; bio = it.bio; avatarUrl = it.avatarUrl } }
        }
    }

    DisposableEffect(graph) {
        val listener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { auth ->
            signedUid = auth.currentUser?.uid
            anonymous = auth.currentUser?.isAnonymous == true
        }
        graph.firebase.auth.addAuthStateListener(listener)
        onDispose { graph.firebase.auth.removeAuthStateListener(listener) }
    }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching { graph.social.uploadAvatar(uri) }.onSuccess { avatarUrl = it; message = "foto profil diperbarui" }
                .onFailure { message = it.message ?: "gagal upload avatar" }
            busy = false
        }
    }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = ""
            try { block(); if (graph.auth.isSignedIn) graph.sync.syncNow(); message = "berhasil" }
            catch (e: Exception) { message = e.message ?: "operasi gagal" }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("akun animego", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("kembali") }
        }
        if (signedUid != null && !anonymous) {
            Text("akun terhubung", style = MaterialTheme.typography.titleMedium)
            Text("uid: $signedUid", style = MaterialTheme.typography.bodySmall)
            if (avatarUrl.isNotBlank()) AsyncImage(model = avatarUrl, contentDescription = "avatar", modifier = Modifier.size(88.dp))
            OutlinedButton(onClick = { avatarPicker.launch("image/*") }, enabled = !busy) { Text("ubah foto profil") }
            OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text("username") }, singleLine = true)
            OutlinedTextField(displayName, { displayName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("nama tampilan") }, singleLine = true)
            OutlinedTextField(bio, { bio = it }, modifier = Modifier.fillMaxWidth(), label = { Text("bio") }, maxLines = 3)
            Button(onClick = { run { graph.social.updateProfile(username, displayName, bio) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("simpan profil") }
            OutlinedButton(onClick = { run { graph.auth.signInWithGoogle() } }, enabled = !busy) { Text("tautkan / masuk dengan google") }
            if (graph.auth.currentUser?.isEmailVerified == false) {
                Text("email belum diverifikasi")
                OutlinedButton(onClick = { run { graph.auth.resendVerification() } }, enabled = !busy) { Text("kirim ulang verifikasi") }
            }
            OutlinedButton(onClick = { run { graph.auth.logout() } }, enabled = !busy) { Text("keluar") }
        } else {
            if (anonymous) Text("lu sedang menggunakan mode guest. akun bisa ditautkan agar UID dan data cloud guest tetap sama.")
            Text(if (register) "buat akun" else "masuk", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("password") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (register || anonymous) OutlinedTextField(value = displayName, onValueChange = { displayName = it }, label = { Text("nama tampilan") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = {
                run {
                    if (anonymous) graph.auth.linkGuestWithEmail(email, password, displayName)
                    else if (register) graph.auth.register(email, password, displayName)
                    else graph.auth.login(email, password)
                }
            }, enabled = !busy && email.isNotBlank() && password.length >= 6, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "memproses..." else if (anonymous) "tautkan akun guest" else if (register) "daftar" else "masuk")
            }
            TextButton(onClick = { register = !register }) { Text(if (register) "sudah punya akun? masuk" else "belum punya akun? daftar") }
            TextButton(onClick = { run { graph.auth.sendPasswordReset(email) } }, enabled = !busy && email.isNotBlank()) { Text("lupa password") }
            OutlinedButton(onClick = { run { graph.auth.signInWithGoogle() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("lanjut dengan google") }
            if (signedUid == null) OutlinedButton(onClick = { run { graph.auth.continueAsGuest() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("lanjut sebagai guest") }
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
    }
}
