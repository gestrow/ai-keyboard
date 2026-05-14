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

# Phase 8: keep AlwaysOnProxy in both flavors. Same precedent as A11yProxy:
# the play impl is a 1-line no-op that R8 will inline; the keep rule preserves
# the dex-invariant assertion that the play APK contains a (no-op) proxy
# class rather than inlining its constants into Settings UI bytecode.
-keep class com.aikeyboard.app.ai.a11y.AlwaysOnProxy { *; }

# Phase 8: keep ReadRespondNotificationBuilder so the play APK ships the
# class even though no fdroid-only code references it there. Required by
# the §13 dex invariant table.
-keep class com.aikeyboard.app.ai.a11y.ReadRespondNotificationBuilder { *; }

# Phase 9a: keep StickerCommitter observable in dex. Single call site
# (CommandRowController.commitSticker) — same R8 inlining pattern as
# BackendResolver and ReadRespondPromptBuilder. Negligible cost; preserves
# the documented insertion entry point as a single named class for audits.
-keep class com.aikeyboard.app.ai.sticker.StickerCommitter {
    public static *** insert(...);
    static *** acceptsWebp(...);
}

# Phase 9b: keep singleton objects observable in dex. Same precedent as
# Phase 6's BackendResolver, Phase 7b's ReadRespondPromptBuilder, and
# Phase 9a's StickerCommitter — R8 inlines single-call-site singletons in
# this codebase. WhatsAppStickerContentProvider does NOT need an explicit
# keep rule (manifest-referenced ContentProviders are kept by R8's defaults).
-keep class com.aikeyboard.app.ai.sticker.TrayIconNormalizer {
    public static *** normalize(...);
    static *** encodeFitting(...);
}
-keep class com.aikeyboard.app.ai.sticker.StickerPackValidator {
    public static *** validate(...);
}
-keep class com.aikeyboard.app.ai.sticker.AddToWhatsAppHelper {
    public static *** status(...);
    public static *** buildIntent(...);
    static *** statusFromInstalled(...);
}

# Phase 10: keep LocalLanBackend observable in dex. Single call site
# (BackendResolver.LOCAL_LAN branch) — same R8 inlining pattern as
# BackendResolver, ReadRespondPromptBuilder, StickerCommitter.
-keep class com.aikeyboard.app.ai.client.locallan.LocalLanBackend {
    public <init>(...);
    public *** rewrite(...);
}

# Phase 12 §10.6: keep HealthDiagnosticChecker observable in dex. Single
# call site in HealthDiagnosticsRoute, prior precedent showed R8 will
# inline single-call-site suspend fun bodies into composable lambdas
# otherwise — and the health screen then renders all rows as FAIL because
# the checker was inlined away from the dex.
-keep class com.aikeyboard.app.ai.ui.health.HealthDiagnosticChecker {
    public *** runAll(...);
}
