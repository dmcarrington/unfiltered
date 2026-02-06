# Add project specific ProGuard rules here.

# Google Error Prone annotations (used by Tink crypto, not needed at runtime)
-dontwarn com.google.errorprone.annotations.**

# Java AWT classes (not available on Android, referenced by JNA)
-dontwarn java.awt.**

# rust-nostr JNA rules
-keep class rust.nostr.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Kotlin serialization - comprehensive rules
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep serialization core classes
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep serializers for all @Serializable classes
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep generated serializers
-keepnames class **$$serializer { *; }

# Keep app's serializable model classes
-keep class com.nostr.unfiltered.nostr.models.Notification { *; }
-keep class com.nostr.unfiltered.nostr.models.Notification$* { *; }
-keep class com.nostr.unfiltered.nostr.models.NotificationType { *; }
-keepclassmembers class com.nostr.unfiltered.nostr.models.Notification {
    public <init>(...);
    *** Companion;
    *** INSTANCE;
}
-keepclasseswithmembers class com.nostr.unfiltered.nostr.models.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ML Kit Barcode Scanning
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-dontwarn com.google.mlkit.**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Keep ViewModels
-keep class com.nostr.unfiltered.viewmodel.** { *; }

# Keep data classes used in StateFlows
-keep class com.nostr.unfiltered.viewmodel.WalletUiState { *; }
-keep class com.nostr.unfiltered.viewmodel.WalletTransaction { *; }
