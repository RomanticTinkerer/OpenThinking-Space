plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.openthinking.blankassistant.recorder"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // RecorderV2 exposes kotlinx.coroutines.flow.Flow, so consumers need coroutines too.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
}
