plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Bump ONLY this when you release (tag the GitHub release with the same number)
val appVersionName = "1.0.0"

android {
    namespace = "com.mux.video.player"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mux.video.player"
        minSdk = 24
        targetSdk = 34
        versionName = appVersionName
        versionCode = appVersionName.split(".").let { it[0].toInt() * 10000 + it[1].toInt() * 100 + it[2].toInt() }
    }
    val ks = file("debug.keystore")
    if (ks.exists()) {
        signingConfigs {
            getByName("debug") {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
}
