# Add project specific ProGuard rules here.
# Keep Xposed module entry (by structure — matches any XposedModule subclass)
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Adapt java_init.list when class names are obfuscated
-adaptresourcefilecontents META-INF/xposed/java_init.list

# Keep libxposed API classes (compileOnly dependency — not shipped in APK)
-keep class io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.service.** { *; }
-dontwarn io.github.libxposed.annotation.**
