plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.onesixromcom.movdow"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.onesixromcom.movdow"
        // 26: MediaMuxer(FileDescriptor) to write mp4 into the chosen folder.
        minSdk = 26
        targetSdk = 35
        versionName = "1.0.0"
        // 1.2.3 -> 10203
        versionCode = versionName!!.split(".").fold(0) { code, part -> code * 100 + part.toInt() }
        base.archivesName = "$applicationId-v.$versionName"
    }

    val releaseKey = listOf("RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
        .associateWith { (findProperty(it) as String?) ?: System.getenv(it) }
    val hasReleaseKey = releaseKey.values.all { !it.isNullOrEmpty() }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseKey.getValue("RELEASE_STORE_FILE")!!)
                storePassword = releaseKey["RELEASE_STORE_PASSWORD"]
                keyAlias = releaseKey["RELEASE_KEY_ALIAS"]
                keyPassword = releaseKey["RELEASE_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (Android's is a stub there).
    testImplementation("org.json:json:20240303")
}
