import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.healthtracker.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.healthtracker.watch"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        val localProperties = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            FileInputStream(localPropsFile).use { localProperties.load(it) }
        }
        val watchToken = System.getenv("PROD_WATCH_TOKEN") 
            ?: localProperties.getProperty("PROD_WATCH_TOKEN") 
            ?: "missing-token"
        
        buildConfigField("String", "PROD_WATCH_TOKEN", "\"$watchToken\"")
    }

    signingConfigs {
        create("customDebug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("customDebug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // Core AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // Compose for Wear OS
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Wear OS
    implementation("androidx.wear:wear:1.3.0")

    // Health Services — the whole point of this project
    implementation("androidx.health:health-services-client:1.1.0-alpha03")

    // Background scheduling
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Local buffer DB
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    // (For Kotlin symbol processing, you may prefer KSP; using annotationProcessor keeps setup simple.)

    // Secure storage of the API token
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // HTTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")
}
