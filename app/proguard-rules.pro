# Scala 3 standard library references JVM classes that do not exist on Android.
-dontwarn scala.**
-dontwarn sun.misc.Unsafe
# Lazy vals look up their state fields reflectively by name.
-keepclassmembers class * {
    *** *$lzy*;
    *** *bitmap$*;
    *** *$lzyHandle;
}
-keepattributes SourceFile,LineNumberTable
