# Keep MediaSession and MediaBrowser classes
-keep class android.support.v4.media.** { *; }
-keep class androidx.media.** { *; }

# Keep app model classes used in JSON serialization
-keep class com.androsnd.model.** { *; }

# Keep all app classes
-keep class com.androsnd.** { *; }

# Strip verbose and debug log calls in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
