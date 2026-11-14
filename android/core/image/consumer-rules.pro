# :core:image rules, applied by R8 to every app that uses the module (:app, :native). Moved from
# app/proguard-rules.pro unchanged (ADR 0019).

# JNI: native methods are bound by name; the native code constructs LibRawException(int, String)
# and calls RawProgressListener.onProgress(int, int, int) by name.
-keepclasseswithmembernames,includedescriptorclasses class dev.hfps.hoard.image.raw.LibRawNative {
    native <methods>;
}
-keep class dev.hfps.hoard.image.raw.LibRawException { <init>(int, java.lang.String); }
-keep interface dev.hfps.hoard.image.raw.RawProgressListener { boolean onProgress(int, int, int); }
-keep class * implements dev.hfps.hoard.image.raw.RawProgressListener { boolean onProgress(int, int, int); }
