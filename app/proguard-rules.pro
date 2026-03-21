# yt-dlp / Chaquopy
-keep class com.chaquo.** { *; }
-keep class com.ytdownloader.app.util.ProgressCallback { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
