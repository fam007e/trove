import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
}

// Release signing: reads signing/keystore.properties (gitignored). The keystore
// itself never enters the repo — see signing/RELEASE_SIGNING.md for the
// identity chain binding this cert to the author's GPG key.
val signingPropsFile = rootProject.file("signing/keystore.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) signingPropsFile.inputStream().use { load(it) }
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

    signingConfigs {
        if (signingPropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                storeType = signingProps.getProperty("storeType", "PKCS12")
            }
        }
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
            if (signingPropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
