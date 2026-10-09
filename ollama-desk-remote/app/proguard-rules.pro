# OkHttp ships its own rules. ZXing's scanner is started by class name:
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
