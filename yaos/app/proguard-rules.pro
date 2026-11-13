# JNI callbacks and native method names are looked up from C++ by their original names.
-keep class com.airtv.receiver.airplay.NativeAirPlay { *; }
-keep interface com.airtv.receiver.airplay.AirPlayCallbacks { *; }
-keep class com.airtv.receiver.airplay.AirPlayReceiver { *; }
-keep class com.airtv.receiver.media.AlacDecoder { *; }
-keepclasseswithmembernames class * { native <methods>; }
