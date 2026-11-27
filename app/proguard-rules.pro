# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Julian Digital Products
-keepclassmembers class * { @kotlinx.serialization.* <fields>; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
-keep @kotlinx.serialization.Serializable class de.juliandigitalproducts.notitocal.** { *; }

# AGP 9 / R8 strips constructors that libraries reach via reflection (WorkManager, androidx.startup).
# Not dead: WorkManager keeps its jobs in an internal Room database (WorkDatabase_Impl); without this rule the
# release build crashes at start. (Code review 2026-11-03, C20, had marked it as unused.)
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * implements androidx.startup.Initializer { <init>(); }
-keep class * extends androidx.work.InputMerger { <init>(); }

# MediaPipe LLM inference + ML Kit use JNI / reflection.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# ML Kit Text Recognition (bundled Latin model). The app rules previously kept MediaPipe only;
# these classes are loaded by reflection / JNI and must survive R8 in release builds.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_bundled_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_vision_text_bundled_common.**
-dontwarn com.google.android.gms.internal.mlkit_vision_text_common.**
-dontwarn javax.annotation.processing.**
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
