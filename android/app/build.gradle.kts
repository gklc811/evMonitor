import java.util.Properties

plugins {
    id("com.android.application")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.gklc.evmonitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.gklc.evmonitor"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.2"
    }
    flavorDimensions += "ui"
    productFlavors {
        create("core") {
            dimension = "ui"
            manifestPlaceholders["appLabel"] = "evMonitor"
        }
        create("rich") {
            dimension = "ui"
            applicationIdSuffix = ".rich"
            versionNameSuffix = "-rich"
            manifestPlaceholders["appLabel"] = "evMonitor Rich"
        }
    }
    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
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

// pack the web dashboard (repo root index.html) into the rich flavor's assets
tasks.register<Copy>("copyWebUi") {
    from("../../index.html")
    into("src/rich/assets")
}
tasks.named("preBuild") { dependsOn("copyWebUi") }
