# Keep Unity wrapper class
-keep class com.haishinkit.unity.** { *; }

# Keep all HaishinKit classes used by the wrapper
-keep class com.haishinkit.** { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }

# Keep JvmStatic annotated methods
-keepclassmembers class * {
    @kotlin.jvm.JvmStatic *;
}
