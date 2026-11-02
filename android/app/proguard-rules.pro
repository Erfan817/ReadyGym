# kotlinx-serialization (core module's ExerciseLibrary / WorkoutPlan / UserProfile):
# serializers are looked up reflectively, so keep them and their companions.
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.readygym.**$$serializer { *; }
-keepclassmembers class com.readygym.** {
    *** Companion;
}
-keepclasseswithmembers class com.readygym.** {
    kotlinx.serialization.KSerializer serializer(...);
}
