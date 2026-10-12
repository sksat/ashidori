# Scala 3 standard library references JVM classes that do not exist on Android.
-dontwarn scala.**
-dontwarn sun.misc.Unsafe

# Scala 3.8+ lazy vals: a static `x$lzy1$lzyHandle` VarHandle is created in
# <clinit> with MethodHandles.Lookup.findVarHandle(cls, "x$lzy1", ...), i.e.
# the state field is looked up by name. R8 cannot see that, and it removes the
# field outright when it proves the class is never instantiated (e.g.
# scala.math.BigDecimal$), so <clinit> throws NoSuchFieldException.
# Keep both fields, with their names, in every class that has such a handle.
# (`**` matters: `*` does not cross package separators.)
-keepclasseswithmembers,allowobfuscation class ** {
    static java.lang.invoke.VarHandle *$lzyHandle;
    *** *$lzy*;
}
-keepclassmembers class ** {
    static java.lang.invoke.VarHandle *$lzyHandle;
    *** *$lzy*;
}

-keepattributes SourceFile,LineNumberTable
