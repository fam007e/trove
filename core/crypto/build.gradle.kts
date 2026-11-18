plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.trove.core.crypto"
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
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxAppCompat)
    implementation(libs.androidxFragmentKtx)
    implementation(libs.androidxBiometric)
    implementation(libs.kotlinxCoroutinesAndroid)
    implementation(project(":core:fs"))

    // Crypto libraries
    implementation(libs.bouncycastleProv)
    implementation(libs.conscryptAndroid)

    testImplementation(libs.junit)
}
