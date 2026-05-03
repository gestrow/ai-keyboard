# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class com.aikeyboard.app.latin.dictionary.Dictionary
-keep class com.aikeyboard.app.latin.NgramContext
-keep class com.aikeyboard.app.latin.makedict.ProbabilityInfo

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate

# AI Keyboard: androidx.security:security-crypto pulls in Tink, which references
# error-prone / javax annotations that aren't on the runtime classpath.
# These are compile-time annotations only; safe to ignore for R8.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# AI Keyboard (Phase 3a): Ktor + OkHttp + kotlinx.serialization.
# Ktor's coroutine-based engines reflect on the OkHttp engine class at runtime;
# the rest is dontwarn for transitive bytecode references that are not loaded on Android.
-dontwarn io.ktor.**
-keep class io.ktor.client.engine.okhttp.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# kotlinx.serialization: keep generated $$serializer classes and Companion accessors so
# JSON encode/decode keeps working under R8 minify in debug + release builds.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclasseswithmembers class * { @kotlinx.serialization.KSerializer <fields>; }
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class ** { *** Companion; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }
