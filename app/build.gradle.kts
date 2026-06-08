plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val releaseStoreFile = providers.environmentVariable("INFRA_SIGNING_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("INFRA_SIGNING_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("INFRA_SIGNING_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("INFRA_SIGNING_KEY_PASSWORD").orNull
val releaseSigningConfigured =
    !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.infraspine.callsync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.infraspine.callsync"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // CI sets GIT_COMMIT_SHA to ${{ github.sha }} so the running build can be
        // compared against the commit SHA embedded in the GitHub release name by
        // UpdateChecker — falls back to a local git lookup for developer builds.
        val gitCommitSha = (System.getenv("GIT_COMMIT_SHA") ?: providers.exec {
            commandLine("git", "rev-parse", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()).ifBlank { "unknown" }

        buildConfigField("String", "GIT_COMMIT_SHA", "\"$gitCommitSha\"")
    }

    signingConfigs {
        create("internalRelease") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("internalRelease")
            }
            buildConfigField("String", "UPDATE_APK_FILE_NAME", "\"app-release.apk\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "UPDATE_APK_FILE_NAME", "\"app-debug.apk\"")
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Core / UI
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Lifecycle / ViewModel / LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.7.1")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager (background sync)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Security - EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Networking (Retrofit + OkHttp) - upload structure
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
