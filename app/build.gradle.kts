plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Opt-in diagnostic fixtures exercise the unchanged ci2296 implementation.
// They include desired-behavior assertions which may expose existing defects;
// they are not part of the ordinary green release-test gate.
val phoneDiagnostics = providers.gradleProperty("phoneDiagnostics").orNull == "true"

android {
    namespace = "com.lumena.android"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.lumena.android"
        minSdk = 28
        targetSdk = 35
        versionCode = 64
        val sourceCommit = providers.environmentVariable("GITHUB_SHA").orNull
            ?.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: "UNKNOWN"
        buildConfigField("String", "SOURCE_COMMIT", "\"$sourceCommit\"")
        versionName = "0.12.30" + providers.environmentVariable("GITHUB_RUN_NUMBER")
            .orNull?.let { "-ci$it" }.orEmpty()
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-O3")
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Google's LiteRT-LM 0.18 AAR was built with newer Kotlin metadata.
        // The official Google AI Edge Gallery uses this compatibility flag.
        // No blanket AGP/Kotlin upgrade until isolated CI proves necessary.
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
    buildFeatures { compose = true; buildConfig = true }
    if (phoneDiagnostics) {
        sourceSets.getByName("test").java.srcDir("src/phoneDiagnostics/java")
        testOptions.unitTests.isIncludeAndroidResources = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
    // Pinned to the upstream Google AI Edge Gallery and LiteRT-LM 0.18.0
    // native embedding API. No model weights bundled or downloaded.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
    if (phoneDiagnostics) {
        testImplementation("org.robolectric:robolectric:4.15.1")
        testImplementation("androidx.compose.ui:ui-test-junit4")
        testImplementation("androidx.work:work-testing:2.9.1")
        debugImplementation("androidx.compose.ui:ui-test-manifest")
    }
    debugImplementation("androidx.compose.ui:ui-tooling")
}
