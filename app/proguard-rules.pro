# Keep MediaSession and MediaBrowser classes
-keep class android.support.v4.media.** { *; }
-keep class androidx.media.** { *; }

# Keep app model classes used in JSON serialization
-keep class com.androsnd.model.** { *; }

# Keep service and activity entry points
-keep class com.androsnd.MusicService { *; }
-keep class com.androsnd.MainActivity { *; }

# Strip verbose and debug log calls in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
