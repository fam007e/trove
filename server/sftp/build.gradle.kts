plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.trove.server.sftp"
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
    implementation(libs.kotlinxCoroutinesAndroid)
    implementation(project(":core:fs"))
    implementation(project(":core:crypto"))
    implementation(libs.jbcrypt)
    implementation(libs.bouncycastleProv)

    // SFTP Server libraries
    implementation(libs.minaSshdCore)
    implementation(libs.minaSshdSftp)

    testImplementation(libs.junit)
}
