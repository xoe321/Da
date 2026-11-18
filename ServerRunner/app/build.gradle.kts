plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}
android {
    namespace = "com.example.serverrunner"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.serverrunner"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }  // extract libcloudflared.so so it can be executed
}
chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            // sqlite3 is built into Python. Everything else comes from app/requirements.txt
            // (filtered by .github/check_libs.py in CI), and is packed inside the APK.
            install("-r", "requirements.txt")
        }
    }
}
