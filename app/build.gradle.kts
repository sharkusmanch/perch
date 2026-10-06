plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.nousresearch.dock"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.sharkusmanch.dock"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.0.0"
        vectorDrawables.useSupportLibrary = true
    }

    // Release signing comes from the environment (CI secrets, or exported
    // locally). Without it a release build is left unsigned.
    val signingKeystoreFile: String? = System.getenv("SIGNING_KEYSTORE_FILE")

    signingConfigs {
        if (signingKeystoreFile != null) {
            create("release") {
                storeFile = file(signingKeystoreFile)
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                // A PKCS12 key shares the store's password.
                keyPassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            applicationVariants.all {
                outputs.all {
                    if (name == "release") {
                        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "perch-$versionName.apk"
                    }
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

    buildFeatures {
        compose = false
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Preferences / Settings
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Widget hosting
    implementation("androidx.appcompat:appcompat:1.6.1")

    // ConstraintLayout
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Material 3 components for the settings screen
    implementation("com.google.android.material:material:1.12.0")

    // Swipeable dream pages and clock faces
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Activity result API (photo picker)
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Glide for image loading (slideshow)
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")

    testImplementation("junit:junit:4.13.2")
}