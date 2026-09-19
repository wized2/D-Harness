-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.endroid.dharness.HarnessBridge { *; }
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
