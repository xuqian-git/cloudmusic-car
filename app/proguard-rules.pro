# org.json / OkHttp / Media3 ship their own consumer rules.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Loaded reflectively from a signed .ppmusic bundle by Paopao Desktop.
-keep class com.cloudmusic.car.plugin.CloudMusicPlugin { public *; }
