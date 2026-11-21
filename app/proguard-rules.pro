# LiteRT-LM's native library reaches back into its Java classes by name
# (constructors, fields and callbacks from JNI), and its tool support reads
# them by reflection. The library is small, so it is kept whole.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep interface com.google.ai.edge.litertlm.** { *; }
-keepclassmembers enum com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Kotlin metadata, so kotlin-reflect inside LiteRT-LM still works.
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# Line numbers in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
