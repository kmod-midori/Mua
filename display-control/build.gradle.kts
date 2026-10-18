plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "moe.reimu.displaycontrol"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { aidl = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.serialization.json)
    api(libs.shizuku.api)
    testImplementation(libs.junit)
}
