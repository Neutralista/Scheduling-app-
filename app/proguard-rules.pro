-keepattributes *Annotation*

# Rhino JavaScript engine — keep all classes so R8 doesn't strip them
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
