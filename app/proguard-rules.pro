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

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
