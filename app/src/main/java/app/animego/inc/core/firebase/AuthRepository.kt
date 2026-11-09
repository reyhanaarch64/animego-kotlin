package app.animego.inc.core.firebase

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.tasks.await

class AuthRepository(private val context: Context, private val firebase: FirebaseProvider) {
    val currentUser get() = firebase.auth.currentUser
    val isSignedIn get() = currentUser != null && currentUser?.isAnonymous == false
    val isGuest get() = currentUser?.isAnonymous == true

    suspend fun continueAsGuest() {
        if (firebase.auth.currentUser == null) firebase.auth.signInAnonymously().await()
    }

    suspend fun register(email: String, password: String, displayName: String) {
        val normalizedEmail = email.trim().lowercase()
        require(normalizedEmail.matches(Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE))) { "format email tidak valid" }
        require(password.length >= 6) { "password minimal 6 karakter" }
        require(displayName.trim().length in 2..32) { "nama harus 2–32 karakter" }
        val methods = firebase.auth.fetchSignInMethodsForEmail(normalizedEmail).await().signInMethods.orEmpty()
        require(methods.isEmpty()) { "akun sudah terdaftar. silakan login menggunakan metode yang sebelumnya digunakan." }
        val result = try {
            firebase.auth.createUserWithEmailAndPassword(normalizedEmail, password).await()
        } catch (e: Exception) {
            if (e is FirebaseAuthUserCollisionException || e.message.orEmpty().contains("email address is already in use", true)) {
                error("akun sudah terdaftar. silakan login menggunakan metode yang sebelumnya digunakan.")
            }
            throw e
        }
        val user = result.user ?: error("firebase tidak mengembalikan akun")
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(displayName.trim()).build()).await()
        user.sendEmailVerification().await()
        // Authentication is already committed by Firebase Auth at this point.
        // A temporary/misconfigured RTDB rule must not turn a successful account
        // creation into a misleading registration failure (which would orphan the
        // newly-created account from the user's perspective).
        runCatching {
            createOrUpdateProfile(user.uid, displayName.trim(), "user_${user.uid.take(8)}")
        }.onFailure { error ->
            android.util.Log.e("AnimeGoAuth", "Account created, but initial RTDB profile write failed", error)
        }
        syncPushToken()
        schedulePostLoginSync()
    }

    suspend fun login(email: String, password: String) {
        val normalizedEmail = email.trim().lowercase()
        require(normalizedEmail.isNotBlank() && password.isNotBlank()) { "email dan password wajib diisi" }
        val methods = runCatching { firebase.auth.fetchSignInMethodsForEmail(normalizedEmail).await().signInMethods.orEmpty() }.getOrDefault(emptyList())
        if (GoogleAuthProvider.PROVIDER_ID in methods) {
            error("akun ini terdaftar menggunakan google. silakan login dengan google.")
        }
        try {
            val result = firebase.auth.signInWithEmailAndPassword(normalizedEmail, password).await()
            result.user?.let { ensureUserProfile(it) }
            syncPushToken()
            schedulePostLoginSync()
        } catch (e: FirebaseAuthInvalidUserException) {
            error("akun tidak ditemukan atau terdaftar menggunakan metode login lain.")
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            error("email atau password salah, atau akun ini terdaftar menggunakan google.")
        }
    }

    suspend fun sendPasswordReset(email: String) {
        require(email.trim().isNotBlank()) { "email wajib diisi" }
        firebase.auth.sendPasswordResetEmail(email.trim()).await()
    }

    suspend fun resendVerification() {
        firebase.auth.currentUser?.sendEmailVerification()?.await() ?: error("sesi login tidak ditemukan")
    }

    /** Uses the Android Credential Manager account chooser. Google credentials are never linked automatically. */
    suspend fun signInWithGoogle(activity: Activity, registration: Boolean) {
        val credentialManager = CredentialManager.create(activity)
        val googleOption = GetGoogleIdOption.Builder()
            .setServerClientId(activity.getString(app.animego.inc.R.string.default_web_client_id))
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(googleOption).build()
        val response = credentialManager.getCredential(activity, request)
        val credential = response.credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "kredensial google tidak valid"
        }
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        val email = google.id.takeIf { it.contains('@') }?.lowercase().orEmpty()
        require(email.isNotBlank()) { "email google tidak tersedia" }
        val methods = runCatching { firebase.auth.fetchSignInMethodsForEmail(email).await().signInMethods.orEmpty() }.getOrDefault(emptyList())
        if (registration && methods.isNotEmpty()) {
            error("akun sudah terdaftar. silakan login menggunakan metode yang sebelumnya digunakan.")
        }
        if (!registration && "password" in methods) {
            error("akun ini sudah terdaftar menggunakan email dan password. silakan login dengan email.")
        }

        // Firebase sign-in replaces an anonymous session; it never links the guest UID.
        val firebaseCredential = GoogleAuthProvider.getCredential(google.idToken, null)
        val authResult = try {
            firebase.auth.signInWithCredential(firebaseCredential).await()
        } catch (e: FirebaseAuthUserCollisionException) {
            error("akun sudah terdaftar menggunakan metode lain. silakan login dengan metode sebelumnya.")
        }
        val user = authResult.user ?: error("firebase tidak mengembalikan akun google")
        val isNewUser = authResult.additionalUserInfo?.isNewUser == true
        if (!registration && isNewUser) {
            user.delete().await()
            firebase.auth.signOut()
            error("akun google belum terdaftar. gunakan menu daftar dengan google.")
        }
        if (registration && !isNewUser) {
            firebase.auth.signOut()
            error("akun sudah terdaftar. silakan login menggunakan metode yang sebelumnya digunakan.")
        }
        runCatching { ensureUserProfile(user) }
            .onFailure { error ->
                android.util.Log.e("AnimeGoAuth", "Google auth succeeded, but RTDB profile sync failed", error)
            }
        syncPushToken()
        schedulePostLoginSync()
    }

    private fun schedulePostLoginSync() {
        CloudSyncScheduler.syncSoon(context)
        ChatSyncScheduler.schedule(context)
    }

    /** Legacy linking is intentionally disabled; account providers remain mutually exclusive. */
    suspend fun linkGuestWithEmail(email: String, password: String, displayName: String = "pengguna") {
        error("tautkan akun guest tidak tersedia. daftar atau login menggunakan metode autentikasi sendiri.")
    }

    private suspend fun createOrUpdateProfile(uid: String, displayName: String, username: String) {
        val updates = mapOf<String, Any>(
            "users/$uid/uid" to uid,
            "users/$uid/displayName" to displayName,
            "users/$uid/username" to username,
            "users/$uid/createdAt" to com.google.firebase.database.ServerValue.TIMESTAMP,
            "users/$uid/updatedAt" to com.google.firebase.database.ServerValue.TIMESTAMP,
            "publicProfiles/$uid/uid" to uid,
            "publicProfiles/$uid/displayName" to displayName,
            "publicProfiles/$uid/username" to username,
            "publicProfiles/$uid/createdAt" to com.google.firebase.database.ServerValue.TIMESTAMP,
            "usernames/$username" to uid
        )
        firebase.database.reference.updateChildren(updates).await()
    }

    private suspend fun ensureUserProfile(user: com.google.firebase.auth.FirebaseUser) {
        val privateRef = firebase.database.reference.child("users").child(user.uid)
        val publicRef = firebase.database.reference.child("publicProfiles").child(user.uid)
        val privateSnapshot = privateRef.get().await()
        val publicSnapshot = publicRef.get().await()
        val username = publicSnapshot.child("username").getValue(String::class.java)
            ?: privateSnapshot.child("username").getValue(String::class.java)
            ?: "user_${user.uid.take(8)}"
        val displayName = publicSnapshot.child("displayName").getValue(String::class.java)
            ?: privateSnapshot.child("displayName").getValue(String::class.java)
            ?: user.displayName ?: "pengguna"
        val now = com.google.firebase.database.ServerValue.TIMESTAMP
        val updates = mutableMapOf<String, Any>(
            "users/${user.uid}/uid" to user.uid,
            "users/${user.uid}/updatedAt" to now,
            "publicProfiles/${user.uid}/uid" to user.uid,
            "publicProfiles/${user.uid}/username" to username,
            "publicProfiles/${user.uid}/displayName" to displayName,
            "publicProfiles/${user.uid}/updatedAt" to now,
            "usernames/$username" to user.uid
        )
        if (!privateSnapshot.child("createdAt").exists()) updates["users/${user.uid}/createdAt"] = now
        if (!publicSnapshot.child("createdAt").exists()) updates["publicProfiles/${user.uid}/createdAt"] = now
        firebase.database.reference.updateChildren(updates).await()
    }

    private suspend fun syncPushToken() {
        val user = firebase.auth.currentUser ?: return
        if (user.isAnonymous) return
        runCatching {
            val token = com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
            firebase.database.reference.child("userTokens").child(user.uid).child(token).setValue(true).await()
        }
    }

    suspend fun logout() {
        firebase.auth.signOut()
        runCatching { CredentialManager.create(context).clearCredentialState(androidx.credentials.ClearCredentialStateRequest()) }
    }
}
