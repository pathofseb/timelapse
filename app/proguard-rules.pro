# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep CameraX classes
-keep class androidx.camera.** { *; }

# Keep video codec classes
-keep class android.media.** { *; }

# Keep service classes
-keep class com.timelapse.TimeLapseService { *; }
-keep class com.timelapse.VideoCompiler { *; }

-dontwarn androidx.camera.**