plugins {
    id("com.android.application")
}

android {
    namespace = "dev.gklc.evmonitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.gklc.evmonitor"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
