-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,InnerClasses

-if interface * { @retrofit2.http.* <methods>; }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>

-keep class com.edgehybrid.agent.data.model.**$$serializer { *; }
-keepclassmembers class com.edgehybrid.agent.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.edgehybrid.agent.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}