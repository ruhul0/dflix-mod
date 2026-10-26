import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.dflixtv.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dflixtv.app"
        minSdk = 24
        targetSdk = 36
        // scripts/release.sh sets BUILD_NUMBER so every release is a higher version the in-app updater can detect.
        val buildNumber = (System.getenv("BUILD_NUMBER") ?: System.getenv("GITHUB_RUN_NUMBER"))?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "UPDATE_REPO", "\"ruhul0/dflix-mod\"")
    }

    // A fixed key is required so installed apps accept updates built by CI. CI passes it via
    // environment variables; locally it is read from keystore/keystore.properties (not committed).
    val localSigning = Properties().apply {
        rootProject.file("keystore/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun signing(env: String, key: String): String? = System.getenv(env)?.ifBlank { null } ?: localSigning.getProperty(key)

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(signing("SIGNING_KEYSTORE_PATH", "storeFile") ?: "keystore/release.jks")
            storePassword = signing("SIGNING_STORE_PASSWORD", "storePassword")
            keyAlias = signing("SIGNING_KEY_ALIAS", "keyAlias") ?: "dflixtv"
            keyPassword = signing("SIGNING_KEY_PASSWORD", "keyPassword")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Sideloaded APK: updates itself from GitHub releases.
        create("sideload") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        // Google Play build: Play handles updates, so no self-updater or install permission.
        create("play") {
            dimension = "distribution"
            applicationIdSuffix = ".play"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.ffmpeg)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
}
