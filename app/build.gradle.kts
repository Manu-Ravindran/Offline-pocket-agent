plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.offline.pocketagent"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.offline.pocketagent"
        minSdk = 31
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { aidl = true }
    val signingStore = System.getenv("POCKET_SIGNING_STORE_FILE")
    val signingPasswordFile = System.getenv("POCKET_SIGNING_PASSWORD_FILE")
    if (!signingStore.isNullOrBlank() && !signingPasswordFile.isNullOrBlank()) {
        signingConfigs.create("persistentRelease") {
            storeFile = file(signingStore)
            storePassword = file(signingPasswordFile).readText().trimEnd('\r', '\n')
            keyAlias = "pocket-agent"
            keyPassword = storePassword
        }
    }
    buildTypes {
        debug {
            isDebuggable = false
            isJniDebuggable = false
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            resValue("string", "app_name", "Pocket Agent Preview")
        }
        release {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = false
            resValue("string", "app_name", "Pocket Agent")
            if (!signingStore.isNullOrBlank() && !signingPasswordFile.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("persistentRelease")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += "META-INF/DEPENDENCIES"
        resources.pickFirsts += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    lint { abortOnError = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(files("libs/geniex-android-v0.9.3.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}
