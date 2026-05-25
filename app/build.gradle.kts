import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// Download buffalo_s.onnx into assets during build
val onnxAssetsDir = file("src/main/assets/onnx_models")
val onnxModelFile = file("src/main/assets/onnx_models/buffalo_s.onnx")
val modelZipUrl = "https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_s.zip"

val downloadOnnxModel by tasks.registering {
    group = "build"
    description = "Download buffalo_s.onnx face recognition model"

    doFirst {
        onnxAssetsDir.mkdirs()
        if (onnxModelFile.exists() && onnxModelFile.length() > 10_000_000) {
            logger.info("buffalo_s.onnx already exists (${onnxModelFile.length() / 1024 / 1024} MB) — skipping download")
            return@doFirst
        }
        logger.info("Downloading buffalo_s.onnx from $modelZipUrl ...")
    }

    doLast {
        try {
            val tmpZip = file("${buildDir}/buffalo_s.zip")
            tmpZip.parentFile?.mkdirs()

            // Download via Java URL (no external tools needed)
            URL(modelZipUrl).openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", "KidGuard-Android/1.0")
                val input = getInputStream()
                tmpZip.outputStream().use { out -> input.copyTo(out) }
            }
            logger.info("Downloaded zip: ${tmpZip.length() / 1024 / 1024} MB")

            // Extract .onnx from zip
            java.util.zip.ZipFile(tmpZip).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (!entry.isDirectory && entry.name.endsWith(".onnx")) {
                        logger.info("Extracting ${entry.name}...")
                        zip.getInputStream(entry).use { input ->
                            onnxModelFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        logger.info("buffalo_s.onnx ready: ${onnxModelFile.length() / 1024 / 1024} MB")
                        return@doLast
                    }
                }
            }
            throw GradleException("No .onnx file found in downloaded zip")
        } catch (e: Exception) {
            throw GradleException("Failed to download buffalo_s.onnx: ${e.message}", e)
        }
    }
}

// Run before every build
tasks.matching { it.name.startsWith("pre") && it.name.endsWith("Build") }.configureEach {
    dependsOn(downloadOnnxModel)
}

android {
    namespace = "com.kidguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kidguard"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.0.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // AGP default debug keystore for v3 signing
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "../seetaface2/src")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.navigation:navigation-compose:2.8.5")

    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ML Kit face detection — primary detector (no GMS required for bundled model)
    implementation("com.google.mlkit:face-detection:16.1.7")

    // SeetaFace2 — Java stubs only (native libs unavailable in CI, ML Kit fallback used)
    implementation(fileTree("libs") { include("*.jar", "*.aar") })

    // ONNX Runtime for face recognition
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("com.google.dagger:hilt-android:2.53.1")
    ksp("com.google.dagger:hilt-android-compiler:2.53.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
