plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The release helper supplies version overrides and signing only via environment variables.
val releaseVersionCode = providers.gradleProperty("docscanVersionCode").orNull ?: "1"
val releaseVersionName = providers.gradleProperty("docscanVersionName").orNull ?: "1.0.0"
val parsedVersionCode = releaseVersionCode.toIntOrNull()
    ?: error("docscanVersionCode must be an integer from 1 to 2100000000")
require(parsedVersionCode in 1..2_100_000_000) {
    "docscanVersionCode must be an integer from 1 to 2100000000"
}
require(releaseVersionName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,79}"))) {
    "docscanVersionName must be 1-80 letters, digits, dots, dashes, underscores or plus signs"
}
val signingNames = listOf("DOCSCAN_KEYSTORE_PATH", "DOCSCAN_STORE_PASSWORD", "DOCSCAN_KEY_ALIAS", "DOCSCAN_KEY_PASSWORD")
val signingValues = signingNames.map { providers.environmentVariable(it).orNull }
require(signingValues.all { it.isNullOrBlank() } || signingValues.none { it.isNullOrBlank() }) {
    "Set all DOCSCAN signing environment variables, or none of them"
}

android {
    namespace = "com.dharmawantoxi.docscan"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dharmawantoxi.docscan"
        minSdk = 24
        targetSdk = 36
        versionCode = parsedVersionCode
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseSigning = if (signingValues.all { !it.isNullOrBlank() }) {
        signingConfigs.create("releaseFromEnvironment") {
            storeFile = file(signingValues[0]!!)
            require(storeFile!!.isFile) { "DOCSCAN_KEYSTORE_PATH must point to an existing keystore" }
            storePassword = signingValues[1]
            keyAlias = signingValues[2]
            keyPassword = signingValues[3]
        }
    } else null

    buildTypes {
        release {
            if (releaseSigning != null) signingConfig = releaseSigning
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.android.material:material:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Memodifikasi objek teks PDF asli tanpa meraster seluruh halaman.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Pemindai otomatis memerlukan Google Play Services; kamera bawaan tersedia
    // sebagai alternatif. OCR Latin dibundel dan bekerja di perangkat.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
