-keepattributes SourceFile,LineNumberTable
-dontwarn kotlinx.**
-dontwarn javax.annotation.**

-keepclassmembers class com.endroid.dharness.HarnessBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.endroid.dharness.HarnessBridge { *; }

-keep class com.google.android.material.appbar.MaterialToolbar { *; }
-keep class com.google.android.material.button.MaterialButton { *; }
-keep class com.google.android.material.card.MaterialCardView { *; }
-keep class com.google.android.material.materialswitch.MaterialSwitch { *; }
-keep class com.google.android.material.textfield.** { *; }
-keep class com.google.android.material.divider.MaterialDivider { *; }

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Drop unused Material components aggressively
-dontwarn com.google.android.material.**
