plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.woodlandweave.run"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.woodlandweave.run"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("ciVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("ciVersionName") as String?) ?: "2.0"
    }

    // Release signing: credentials come only from environment variables (GitHub Actions secrets).
    // The same permanent key as the old Capacitor build keeps in-place updates working.
    val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    signingConfigs {
        create("release") {
            if (!keystorePassword.isNullOrEmpty()) {
                storeFile = file("release.keystore")
                storePassword = keystorePassword
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (!keystorePassword.isNullOrEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // The 9 MB loop must reach the device uncompressed so it can be streamed straight from the APK.
    androidResources { noCompress += listOf("wav", "jpg") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
