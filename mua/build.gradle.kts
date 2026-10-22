import com.google.gms.googleservices.GoogleServicesPlugin
import com.google.gms.googleservices.GoogleServicesTask
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services") version "4.4.4"
}

android {
    namespace = "moe.reimu.mua"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "moe.reimu.mua"
        minSdk = 31
        targetSdk = 37
        versionCode = 4
        versionName = "1.0.2"
    }
    flavorDimensions += "push"
    productFlavors {
        create("foss") { dimension = "push" }
        create("google") { dimension = "push" }
    }
    signingConfigs {
        create("release") {
            val propertiesFile = rootProject.file("signing.properties")
            if (propertiesFile.canRead()) {
                val properties = Properties().apply {
                    propertiesFile.inputStream().use { load(it) }
                }
                storeFile = file(properties.getProperty("KEYSTORE_FILE"))
                storePassword = properties.getProperty("KEYSTORE_PASSWORD")
                keyAlias = properties.getProperty("SIGNING_KEY_ALIAS")
                keyPassword = properties.getProperty("SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("gradle/ktor-rules.pro"),
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    "googleImplementation"(platform("com.google.firebase:firebase-bom:34.19.0"))
    "googleImplementation"("com.google.firebase:firebase-messaging")
    "googleImplementation"("androidx.work:work-runtime-ktx:2.11.2")
    implementation(project(":display-control"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.shizuku.provider)
    implementation(libs.mcp.server)
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.websockets)
}

// Only create FCM variants with configuration in a location supported by the plugin.
androidComponents {
    beforeVariants(selector().withFlavor("push" to "google")) { variant ->
        variant.enable = GoogleServicesPlugin.getJsonFiles(
            variant.buildType.orEmpty(),
            variant.productFlavors.map { it.second },
            projectDir,
        ).any { it.isFile }
    }
    onVariants(selector().withFlavor("push" to "foss")) { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
        tasks.named<GoogleServicesTask>("process${variantName}GoogleServices") {
            enabled = false
        }
    }
}
