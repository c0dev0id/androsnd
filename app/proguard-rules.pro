# Keep app components referenced by name in AndroidManifest.xml
-keep class de.codevoid.androsnd.MainActivity { *; }
-keep class de.codevoid.androsnd.MusicService { *; }
-keep class de.codevoid.androsnd.AlbumArtProvider { *; }

# Keep model/data classes — serialized to metadata_cache.json via field access
-keep class de.codevoid.androsnd.model.** { *; }

# Standard Android rules
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
