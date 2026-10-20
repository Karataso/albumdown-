import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firma release: android/keystore.properties + keystore/albumdown-release.jks.
// Si el archivo no existe (otro dev), la release sale sin firmar — no rompe el build.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.albumdown"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.albumdown"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // ponytail: solo arm64 + arm32 en splits, nada de x86 (nadie corre esto en emulador).
    // Si alguien pide x86, se agrega una línea.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    // youtubedl-android exige libs nativas extraídas (unzipea python en runtime).
    // Equivale al viejo android:extractNativeLibs="true" del manifest.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // yt-dlp + python + ffmpeg empaquetados (GPL-3.0: esta app también es GPL-3.0).
    val ytdlp = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdlp")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdlp")
    // Comandos ffmpeg custom (convertir + taggear + portada en 1 pasada).
    // El com.arthenica original se retiró de Central (abril 2025); este es el
    // fork mantenido, drop-in: mismo paquete com.arthenica.ffmpegkit.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9")

    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // AppCompat solo por setApplicationLocales: selector de idioma in-app con
    // persistencia (autoStoreLocales) sin tocar la config del sistema.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("io.coil-kt:coil-compose:2.6.0")

    testImplementation("junit:junit:4.13.2")
}
