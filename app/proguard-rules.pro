# Keep JNI entry points used by the zxing-cpp Android wrapper.
-keep class zxingcpp.** { *; }

# OkapiBarcode and ZXing are referenced directly; their public model names are
# retained to keep stack traces useful in privacy-preserving local diagnostics.
-keepattributes SourceFile,LineNumberTable
