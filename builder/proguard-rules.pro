# apksig's ASN.1 code reads annotated fields through reflection (certificates,
# signature blocks), so its classes must keep their names, fields and annotations.
-keep class com.android.apksig.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn javax.annotation.**
