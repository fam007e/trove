plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.trove.privilege"
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

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation(libs.androidxCoreKtx)
    implementation(project(":core:fs"))

    // Shizuku libraries
    implementation(libs.shizukuApi)
    implementation(libs.shizukuProvider)

    testImplementation(libs.junit)
}
