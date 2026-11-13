# Framewise ProGuard rules
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.framewise.app.updater.** { *; }
-keep class com.framewise.app.device.** { *; }
-keep class com.framewise.app.settings.** { *; }
