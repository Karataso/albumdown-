# Libs nativas (yt-dlp/python/ffmpeg): cargan clases por nombre vía JNI/reflexión;
# R8 rename = crash. Keep conservador, cuesta poco.
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keep class com.yausername.aria2c.** { *; }
-keep class com.arthenica.ffmpegkit.** { *; }

# youtubedl-android descomprime su Python embebido con Commons Compress
# (registro de ExtraField por reflexión en <clinit>) y parsea JSON con Jackson;
# sin keep, R8 rompe ambos en runtime.
-keep class org.apache.commons.compress.** { *; }
-keep class com.fasterxml.jackson.** { *; }

# Dependencias opcionales de jackson/commons-compress que no existen en Android
# (se usan solo si están presentes en el classpath; acá nunca).
-dontwarn java.beans.**
-dontwarn org.tukaani.xz.**
-dontwarn org.w3c.dom.bootstrap.**
