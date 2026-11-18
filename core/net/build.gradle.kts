plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.trove.core.net"
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
    implementation(project(":core:fs"))
    
    // Pinned Network Clients
    implementation(libs.sshj)
    implementation(libs.jcifsNg)
    implementation(libs.commonsNet)
    
    // Ktor for WebDAV Client
    implementation(libs.ktorClientCore)
    implementation(libs.ktorClientOkHttp)

    // Testing
    testImplementation(libs.junit)
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.ktor:ktor-client-mock:2.3.12")
}
