plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = "com.trove.feature.archive"
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
    implementation(libs.androidxCoreKtx)
    implementation(libs.kotlinxCoroutinesAndroid)

    // Archive engines
    implementation(libs.commonsCompress)
    implementation(libs.tukaaniXz)
    implementation(libs.zip4j)

    testImplementation(libs.junit)
}
