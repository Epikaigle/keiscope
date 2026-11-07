import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.epikaigle.keiscope"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.epikaigle.keiscope"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64")) }
    }

    signingConfigs {
        create("keiscopeRelease") {
            val keystore = System.getenv("KEYSTORE_PATH") ?: System.getProperty("KEYSTORE_PATH")
            if (!keystore.isNullOrBlank()) {
                storeFile = File(keystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: System.getProperty("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: System.getProperty("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val keystore = System.getenv("KEYSTORE_PATH") ?: System.getProperty("KEYSTORE_PATH")
            if (!keystore.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("keiscopeRelease")
            }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
