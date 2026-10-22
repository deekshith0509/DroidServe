# Kotlin coroutines (same reflective touch-points the phone module keeps)
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# App entry points
-keep class com.deekshith.droidserve.tv.MainActivity { *; }
-keep class com.deekshith.droidserve.tv.App { *; }

# General Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
