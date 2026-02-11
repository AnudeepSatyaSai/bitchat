# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the default ProGuard configuration.

# Keep Noise protocol classes (security-critical)
-keep class chat.bitchat.app.noise.** { *; }

# Keep binary protocol (wire-compatible)
-keep class chat.bitchat.app.protocol.** { *; }

# Keep models (serialization)
-keep class chat.bitchat.app.model.** { *; }

# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Tink
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
