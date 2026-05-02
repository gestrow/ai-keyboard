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
