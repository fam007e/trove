plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.trove.ui.browse"
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
    implementation(libs.androidxDocumentFile)
    implementation(libs.androidxRecyclerView)
    implementation(libs.androidxActivityKtx)
    implementation(libs.androidxFragmentKtx)
    implementation(libs.androidxViewPager2)
    implementation(libs.androidxLifecycleRuntimeKtx)
    implementation(project(":ui:widgets"))
    implementation(project(":core:fs"))
    implementation(project(":core:net"))
    implementation(project(":feature:archive"))
    implementation(project(":feature:cleaner"))
    implementation(project(":feature:image"))
    implementation(project(":feature:pdf"))
    implementation(project(":feature:tagger"))
    implementation(project(":feature:media"))
    implementation(project(":server:ftp"))
    implementation(project(":server:webdav"))
    implementation(project(":server:sftp"))
    implementation(project(":privilege"))
    implementation(libs.shizukuApi)

    testImplementation(libs.junit)
}
