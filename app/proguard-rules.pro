# MSR Digital Signage Player — release ProGuard rules (minify disabled by default)

-keep class com.digitalsignage.player.data.api.models.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
