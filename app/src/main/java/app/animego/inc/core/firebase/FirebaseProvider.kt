package app.animego.inc.core.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.functions.FirebaseFunctions

/** Central Firebase handles. SQLite remains the durable local catalogue/history store. */
class FirebaseProvider {
    val auth: FirebaseAuth = FirebaseAuth.getInstance()
    val database: FirebaseDatabase = FirebaseDatabase.getInstance(
        "https://animego2-default-rtdb.asia-southeast1.firebasedatabase.app"
    ).apply { setPersistenceEnabled(true) }
    val storage: FirebaseStorage = FirebaseStorage.getInstance()
    val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-southeast1")
}
