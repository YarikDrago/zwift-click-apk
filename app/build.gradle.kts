plugins {
    id("com.android.application") version "8.1.2"
    id("org.jetbrains.kotlin.android") version "1.9.0"
}

android {
    namespace = "app.zwiftclick.emulator"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.zwiftclick.emulator"
        minSdk = 23
        targetSdk = 34
        versionCode = 11
        versionName = "0.2.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
