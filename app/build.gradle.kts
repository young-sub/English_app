import org.gradle.api.GradleException
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasReleaseKeystore = keystorePropertiesFile.exists()

if (hasReleaseKeystore) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

val isReleaseTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}

val repoLayoutProperties = Properties().apply {
    val layoutFile = rootProject.file("gradle/repo-layout.properties")
    if (layoutFile.exists()) {
        layoutFile.inputStream().use(::load)
    }
}

if (isReleaseTaskRequested && !hasReleaseKeystore) {
    throw GradleException(
        "Release signing is not configured. Create keystore.properties at project root (see keystore.properties.example).",
    )
}

fun resolveRepoRelativePath(relativePath: String) = run {
    val direct = rootProject.file(relativePath)
    if (direct.exists()) {
        direct
    } else {
        val repoRootFallback = rootProject.projectDir.parentFile
            ?.takeIf { it.name == ".worktrees" }
            ?.parentFile
            ?.resolve(relativePath)
        repoRootFallback ?: direct
    }
}

val bundledTtsModelSourceDir = resolveRepoRelativePath(
    repoLayoutProperties.getProperty("asset.tts.models", "modules/feature/tts/models"),
)
val bundledTtsAssetsRoot = layout.buildDirectory.dir("generated/tts-assets/main")

val prepareBundledTtsModel by tasks.registering {
    val outputDir = bundledTtsAssetsRoot.map { it.dir("tts-models") }
    inputs.dir(bundledTtsModelSourceDir)
    inputs.file(rootProject.file("scripts/prepare_bundled_tts_assets.py"))
    outputs.dir(outputDir)

    doLast {
        val destinationDir = outputDir.get().asFile
        destinationDir.parentFile?.mkdirs()
        exec {
            commandLine(
                "python",
                rootProject.file("scripts/prepare_bundled_tts_assets.py").absolutePath,
                "--source",
                bundledTtsModelSourceDir.absolutePath,
                "--dest",
                destinationDir.absolutePath,
            )
        }
    }
}

android {
    namespace = "com.example.bookhelper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.bookhelper"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        noCompress += setOf("json")
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(bundledTtsAssetsRoot)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(prepareBundledTtsModel)
}

dependencies {
    implementation(project(":core-contracts"))
    implementation(project(":f1-provisioning"))
    implementation(project(":f2-camera-ocr"))
    implementation(project(":f3-text-postprocess"))
    implementation(project(":f4-dictionary"))
    implementation(project(":f5-tts"))
    implementation(project(":f6-performance"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    implementation("com.google.mlkit:text-recognition:16.0.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(files("libs/sherpa-onnx-1.12.30.aar"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
