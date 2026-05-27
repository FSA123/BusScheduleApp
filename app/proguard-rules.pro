-keepattributes Signature
-keepattributes *Annotation*

# OkHttp + Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Gson model classes
-keep class ro.smarttrans.busschedule.model.** { *; }

# Geniatech AIDL interface
-keep class com.geniatech.el133sdk.** { *; }
-keep interface com.geniatech.el133sdk.** { *; }
