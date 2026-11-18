plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = "com.trove.feature.media"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}

dependencies {
    implementation(project(":core:fs"))
    implementation(project(":ui:widgets"))
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppCompat)
    implementation(libs.androidxActivityKtx)
    implementation(libs.kotlinxCoroutinesAndroid)

    // Media3 / ExoPlayer
    implementation(libs.androidxMedia3ExoPlayer)
    implementation(libs.androidxMedia3Ui)
    implementation(libs.androidxMedia3Session)
    implementation(libs.androidxMedia3Common)

    testImplementation(libs.junit)
}
