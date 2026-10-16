plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arielux.android"
    compileSdk = 36

    defaultConfig {
        // PROVISIONAL and unchanged. Owner approval is required before choosing
        // the permanent Arielux identity for the first Play upload.
        applicationId = "com.arielux.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Future release signing can use these environment variables after
            // owner approval. The current test workflow sets none of them:
            // release stays unsigned; debug uses Android's standard test signing.
            val storePath = providers.environmentVariable("STORE_PATH").orNull
            if (storePath != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(storePath)
                    storePassword = providers.environmentVariable("STORE_PASSWORD").orNull
                    keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
                    keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Google Play Billing Library 8+/9+ — required for all new apps / updates
    // submitted on/after Aug 31 2026. See PlayBillingInterface.kt.
    implementation("com.android.billingclient:billing:9.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}