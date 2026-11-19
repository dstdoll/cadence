# Media3 / serialization rules come from the libraries' consumer rules.

# Type-safe navigation resolves route argument enums by their fully qualified name at runtime.
-keep enum app.cadence.** { *; }

# JNI entry points resolve by the declaring class's binary name, so the class
# itself must survive shrinking/obfuscation. Without these, release builds fail
# with UnsatisfiedLinkError on NativeDrm/NativeWrapper/NativeAuth.
-keep class app.cadence.backend.engine.media.NativeDrm { native <methods>; }
-keep class app.cadence.backend.engine.media.NativeWrapper { native <methods>; }
-keep class app.cadence.backend.real.NativeAuth { native <methods>; }
-keepclasseswithmembernames class * { native <methods>; }

# FFmpeg decoder extension: its JNI layer looks classes and members up by name.
-keep class io.github.anilbeesetti.nextlib.media3ext.** { *; }

# libandroidappmusic.so (JavaCPP build) registers these by name at load.
-keep class org.bytedeco.javacpp.** { *; }
-dontwarn org.bytedeco.**
