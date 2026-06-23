# Add project specific ProGuard rules here.
# Keep Xposed module entry
-keep class com.lspo.ActivityHooker { *; }

# Keep libxposed API classes
-keep class io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.service.** { *; }
