plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.trove.feature.tagger"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:fs"))

    // Audio tagging — ID3v1/v2, Vorbis Comment, MP4 atoms, WAV INFO
    // LGPL-2.1: same source-link policy as jcifs-ng (see NOTICE)
    implementation(libs.jaudiotagger)

    // Image/video metadata reading — EXIF, XMP, IPTC, MP4 boxes
    // Apache-2.0
    implementation(libs.metadataExtractor)

    // EXIF write-back for images (Android-blessed, lighter than raw EXIF patching)
    // Apache-2.0
    implementation(libs.androidxExifinterface)

    // PDF metadata (read/write via PDFBox-Android, already in :feature:pdf)
    // Apache-2.0
    implementation(libs.pdfboxAndroid)

    // Coroutines for suspend-based staging
    implementation(libs.kotlinxCoroutinesAndroid)

    testImplementation(libs.junit)
}
