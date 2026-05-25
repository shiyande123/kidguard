# ─── ONNX Runtime ────────────────────────────────────────────
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ─── SeetaFace2 JNI ──────────────────────────────────────────
-keep class com.seeta.sdk.** { *; }
-keepclassmembers class com.seeta.sdk.** { *; }

# ─── Hilt / Dagger ───────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class com.kidguard.di.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep @javax.inject.Inject class * { *; }
-keepclassmembers class * { @javax.inject.Inject *; }
-keep class * extends dagger.hilt.android.internal.ManagedFragmentViewModelFactory { *; }

# ─── Room ────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# ─── ML Kit (bundled, no GMS) ────────────────────────────────
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.** { *; }
-dontwarn com.google.mlkit.**

# ─── CameraX ─────────────────────────────────────────────────
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ─── Kotlin Coroutines ───────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile *; }

# ─── App-specific models ─────────────────────────────────────
-keep class com.kidguard.data.model.** { *; }
-keep class com.kidguard.face.** { *; }
-keep class com.kidguard.lock.** { *; }
-keep class com.kidguard.service.** { *; }

# ─── General Android ─────────────────────────────────────────
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions
-keepattributes AnnotationDefault,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleAnnotations,RuntimeInvisibleParameterAnnotations

-dontwarn javax.annotation.**
-dontwarn org.codehaus.**
