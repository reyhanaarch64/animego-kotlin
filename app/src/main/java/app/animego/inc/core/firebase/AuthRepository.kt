package app.animego.inc.core.firebase

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
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
        require(displayName.trim().length in 2..32) { "nama harus 2–32 karakter" }
        val result = firebase.auth.createUserWithEmailAndPassword(email.trim(), password).await()
        result.user?.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(displayName.trim()).build())?.await()
        result.user?.sendEmailVerification()?.await()
        result.user?.uid?.let { uid ->
            val username = "user_${uid.take(8)}"
            firebase.database.reference.updateChildren(mapOf(
                "users/$uid/uid" to uid,
                "users/$uid/displayName" to displayName.trim(), "users/$uid/username" to username,
                "users/$uid/createdAt" to com.google.firebase.database.ServerValue.TIMESTAMP,
                "users/$uid/updatedAt" to com.google.firebase.database.ServerValue.TIMESTAMP,
                "usernames/$username" to uid
            )).await()
        }
        syncPushToken()
    }

    suspend fun login(email: String, password: String) {
        val result = firebase.auth.signInWithEmailAndPassword(email.trim(), password).await()
        result.user?.let { ensureUserProfile(it) }
        syncPushToken()
    }

    suspend fun sendPasswordReset(email: String) { firebase.auth.sendPasswordResetEmail(email.trim()).await() }

    suspend fun resendVerification() { firebase.auth.currentUser?.sendEmailVerification()?.await() }

    suspend fun signInWithGoogle() {
        val credentialManager = CredentialManager.create(context)
        val googleOption = GetGoogleIdOption.Builder()
            .setServerClientId(context.getString(app.animego.inc.R.string.default_web_client_id))
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(googleOption).build()
        val response = credentialManager.getCredential(context, request)
        val credential = response.credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "kredensial google tidak valid"
        }
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        val firebaseCredential = GoogleAuthProvider.getCredential(google.idToken, null)
        val current = firebase.auth.currentUser
        val shouldLink = current != null && current.providerData.none { it.providerId == GoogleAuthProvider.PROVIDER_ID }
        val authResult = if (shouldLink) current!!.linkWithCredential(firebaseCredential).await()
            else firebase.auth.signInWithCredential(firebaseCredential).await()
        val user = authResult.user ?: return
        ensureUserProfile(user)
        syncPushToken()
    }

    /** Links an existing anonymous guest to email credentials without discarding its UID/data. */
    suspend fun linkGuestWithEmail(email: String, password: String, displayName: String = "pengguna") {
        val user = firebase.auth.currentUser ?: error("sesi guest tidak ditemukan")
        require(user.isAnonymous) { "akun saat ini bukan guest" }
        user.linkWithCredential(EmailAuthProvider.getCredential(email.trim(), password)).await()
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(displayName.trim().ifBlank { "pengguna" }).build()).await()
        user.sendEmailVerification().await()
        val username = "user_${user.uid.take(8)}"
        firebase.database.reference.updateChildren(mapOf(
            "users/${user.uid}/uid" to user.uid,
            "users/${user.uid}/username" to username,
            "users/${user.uid}/displayName" to (user.displayName ?: displayName.trim().ifBlank { "pengguna" }),
            "users/${user.uid}/createdAt" to com.google.firebase.database.ServerValue.TIMESTAMP,
            "usernames/$username" to user.uid
        )).await()
        syncPushToken()
    }

    private suspend fun ensureUserProfile(user: com.google.firebase.auth.FirebaseUser) {
        val ref = firebase.database.reference.child("users").child(user.uid)
        val snapshot = ref.get().await()
        val username = snapshot.child("username").getValue(String::class.java).orEmpty().ifBlank { "user_${user.uid.take(8)}" }
        val updates = mutableMapOf<String, Any>(
            "uid" to user.uid,
            "username" to username,
            "displayName" to snapshot.child("displayName").getValue(String::class.java).orEmpty().ifBlank { user.displayName ?: "pengguna" },
            "avatarUrl" to snapshot.child("avatarUrl").getValue(String::class.java).orEmpty().ifBlank { user.photoUrl?.toString() ?: "" },
            "updatedAt" to com.google.firebase.database.ServerValue.TIMESTAMP
        )
        if (!snapshot.child("createdAt").exists()) updates["createdAt"] = com.google.firebase.database.ServerValue.TIMESTAMP
        firebase.database.reference.updateChildren(updates.mapKeys { "users/${user.uid}/${it.key}" } + mapOf("usernames/$username" to user.uid)).await()
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
