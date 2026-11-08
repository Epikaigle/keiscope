# KeiScope ProGuard rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}
-dontwarn java.lang.management.**
-dontwarn javax.annotation.**
