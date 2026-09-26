-keepattributes SourceFile,LineNumberTable
-dontwarn kotlinx.**

# Keep WebView bridge
-keepclassmembers class com.endroid.dharness.HarnessBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.endroid.dharness.HarnessBridge { *; }

# Material: allow R8 to strip unused widgets; keep what settings uses
-keep class com.google.android.material.appbar.MaterialToolbar { *; }
-keep class com.google.android.material.button.MaterialButton { *; }
-keep class com.google.android.material.card.MaterialCardView { *; }
-keep class com.google.android.material.materialswitch.MaterialSwitch { *; }
-keep class com.google.android.material.textfield.** { *; }
-keep class com.google.android.material.divider.MaterialDivider { *; }

# Aggressive: strip unused support
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}


# EncryptedSharedPreferences / Tink
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
