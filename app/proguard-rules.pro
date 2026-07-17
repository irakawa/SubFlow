# --- SubFlow release rules ---
# A missing rule = a stripped class = a silently broken feature. Keep complete.

# FFmpegKit — both the original package and the maintained fork we ship
-keep class com.arthenica.ffmpegkit.** { *; }
-keep interface com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**
-keep class com.antonkarpenko.ffmpegkit.** { *; }
-keep interface com.antonkarpenko.ffmpegkit.** { *; }
-dontwarn com.antonkarpenko.ffmpegkit.**

# ML Kit OCR — screenshot parsing dies if stripped
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_latin.** { *; }
-dontwarn com.google.mlkit.**

# Whisper JNI bridge — the native lib resolves this exact method signature
-keep class com.subflow.pipeline.WhisperEngine { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Translation + post-processor chain — stripped = raw MT output, censored tone
-keep class com.subflow.pipeline.PostProcessor { *; }
-keep class com.subflow.pipeline.MegaDictionary { *; }
-keep class com.subflow.pipeline.SlangDictionary { *; }
-keep class com.subflow.pipeline.TranslationEngine { *; }
-keep class com.subflow.pipeline.SubtitleMatcher { *; }
-keep class com.subflow.pipeline.ContentIdentity { *; }
-keep class com.subflow.pipeline.SubtitleCascade { *; }
-keep class com.subflow.pipeline.LangDetect { *; }

# OkHttp — HTTP layer
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature
-keepattributes *Annotation*

# Room — local DB
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# JSON models read via org.json field names
-keep class com.subflow.models.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# Kotlin metadata
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# keep line numbers for readable crash logs
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# junrar (RAR extraction) — logging shims are optional at runtime
-dontwarn org.slf4j.**
-dontwarn com.github.junrar.**
