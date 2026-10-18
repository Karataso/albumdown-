# Libs nativas (yt-dlp/python/ffmpeg): cargan clases por nombre vía JNI/reflexión;
# R8 rename = crash. Keep conservador, cuesta poco.
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keep class com.yausername.aria2c.** { *; }
-keep class com.arthenica.ffmpegkit.** { *; }
