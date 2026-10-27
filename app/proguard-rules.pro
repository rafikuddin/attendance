# Release shrinking (R8). Everything in OUR package is kept as it is, so nothing the app does by name (WorkManager workers,
# broadcast receivers, activities, the JSON screens) can be renamed or removed. Only the libraries are shrunk, and that is
# where almost all of the size is.
-keep class com.example.attendance.** { *; }

# The face model runs through TensorFlow Lite from Google Play services (reached by name from native code).
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.android.gms.tflite.** { *; }

# ML Kit (face, text) client classes are loaded by Google Play services through the manifest entry.
-keep class com.google.mlkit.** { *; }

# Encrypted sign-in storage (security-crypto / Tink): its protobuf messages are read by reflection.
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }

# Annotation-only libraries the shrinker does not need to find.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.j2objc.annotations.**
