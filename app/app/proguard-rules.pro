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

# AI Keyboard (Phase 6): keep BackendResolver intact so the dispatch site in
# CommandRowController is observable in dex (rather than getting inlined into a
# lambda). Trivial cost; preserves the resolver as the documented single dispatch
# point for the rewrite path.
-keep class com.aikeyboard.app.ai.client.BackendResolver {
    public static *** resolve(...);
}

# Phase 7b: keep A11yProxy in both flavors so the play dex invariant
# (no ScreenReaderService reference) and the fdroid dex invariant
# (proxy is the boundary, not inlined into CommandRowController) both hold.
-keep class com.aikeyboard.app.ai.a11y.A11yProxy { *; }

# Phase 7b: keep ReadRespondPromptBuilder observable in dex. R8 otherwise
# inlines `build()` (single call site in CommandRowController.handleSuccessfulWalk)
# and eliminates the class. Same precedent as the BackendResolver keep rule —
# preserves the documented prompt-construction site as a single named entry
# point for future audits.
-keep class com.aikeyboard.app.ai.a11y.ReadRespondPromptBuilder {
    public static *** build(...);
}
