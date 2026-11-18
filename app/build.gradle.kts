plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.trove.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.trove.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 100
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                file("${rootDir}/proguard-rules.pro")
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    androidResources {
        additionalParameters += listOf("--stable-ids", "${rootDir}/stable_ids.txt")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.addAll(
                "-Xno-param-assertions",
                "-Xno-call-assertions"
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxWorkRuntime)
    implementation(libs.conscryptAndroid)
    implementation(libs.shizukuApi)
    implementation(libs.shizukuProvider)

    // Sub-module dependencies
    implementation(project(":ui:widgets"))
    implementation(project(":ui:browse"))
    implementation(project(":core:fs"))
    implementation(project(":core:net"))
    implementation(project(":core:crypto"))
    implementation(project(":server:ftp"))
    implementation(project(":server:webdav"))
    implementation(project(":server:sftp"))
    implementation(project(":feature:archive"))
    implementation(project(":feature:image"))
    implementation(project(":feature:pdf"))
    implementation(project(":feature:tagger"))
    implementation(project(":feature:media"))
    implementation(project(":privilege"))
}
