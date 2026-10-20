# Verdroid R8 rules (v1.0.40: release builds are minified + resource-shrunk).
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# App code: tool registry, JSON models, reflection-y glue. Keep names/members of our own classes (low risk); R8 still
# shrinks and optimizes the libraries, which is where most of the size/speed win is.
-keep,allowoptimization class com.verdroid.app.** { *; }

# Shizuku UserService is instantiated reflectively by Shizuku (app_process).
-keep class com.verdroid.app.shizuku.ShellUserService { *; }
-keep class com.verdroid.app.shizuku.IShellService** { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.**

# kotlinx.serialization
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }
-dontwarn kotlinx.serialization.**

# Retrofit / OkHttp (libraries ship most rules; these cover suspend + generics)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# JGit: reflection for config/transport + optional deps
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn org.ietf.jgss.**
-dontwarn com.jcraft.**
-dontwarn javax.naming.**

# jsoup optional deps
-dontwarn org.jspecify.**
-dontwarn com.google.re2j.**

# Tink (security-crypto) annotation deps
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# Google LiteRT-LM: the AAR ships no consumer rules; its JNI library calls back into these classes by name
# (callbacks, Message/ToolCall builders, gson-parsed JSON), so keep them unobfuscated.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Release: strip debug/verbose logging app-wide (nothing typed or private reaches logcat via Log.d/v).
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
