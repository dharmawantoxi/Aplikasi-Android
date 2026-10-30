plugins {
    id("com.android.application")
}

android {
    namespace = "com.dharmawantoxi.rapiuang"
    compileSdk = 35

    defaultConfig {
        // Pertahankan ID lama agar pembaruan tetap memakai data lokal yang ada.
        applicationId = "com.dharmawantoxi.rapiuang"
        minSdk = 24
        targetSdk = 35
        versionCode = 12
        versionName = "1.8.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
