# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# NewPipeExtractor + transitive deps. Rhino bootstraps its JS engine reflectively
# and crashes under R8 without these keeps; jsoup/nanojson parse via reflection too.
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-keep class org.mozilla.classfile.** { *; }
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
# Rhino/jsoup reference JVM-only classes not present on Android.
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn javax.script.**
-dontwarn javax.lang.model.**
-dontwarn org.mozilla.javascript.tools.**
