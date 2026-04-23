# MAP Proxy ProGuard Rules

# Keep proxy classes
-keep class com.map.proxy.** { *; }

# Keep service
-keep class com.map.service.MapService { *; }

# Timber
-dontwarn org.jetbrains.annotations.**
