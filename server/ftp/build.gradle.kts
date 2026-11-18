plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.trove.server.ftp"
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
    implementation(libs.jbcrypt)
    implementation(project(":core:fs"))
    implementation(project(":core:crypto"))
    testImplementation(libs.junit)
}
