# media3, coil, okhttp, dan kotlinx membawa consumer rules sendiri
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-dontwarn org.bouncycastle.**

# Firebase Realtime Database uses reflection to map these model classes.
-keep class app.animego.inc.core.firebase.CloudUser { *; }
-keep class app.animego.inc.core.firebase.CloudComment { *; }
-keep class app.animego.inc.core.firebase.ChatMessage { *; }
-keep class app.animego.inc.core.firebase.FriendRequest { *; }
