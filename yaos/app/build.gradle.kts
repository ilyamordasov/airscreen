plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Overridable so a release build can take its version from the git tag:
//   ./gradlew assembleRelease -PversionName=1.0.0 -PversionCode=10000
val appVersionName = (findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode = (findProperty("versionCode") as String?)?.toInt() ?: 100

android {
    namespace = "com.airtv.receiver"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "ru.freecast.tv"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.api.ApkVariantOutput).outputFileName =
                "FreeCast-TV-${appVersionName}.apk"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // A stable release key, supplied by CI (or locally) through these properties. Without
    // it every build would be signed with a throwaway debug key, and releases could never
    // be installed as updates over one another.
    val keystorePath = (findProperty("signingKeystore") as String?)
        ?: System.getenv("SIGNING_KEYSTORE")
    val keystorePassword = (findProperty("signingKeystorePassword") as String?)
        ?: System.getenv("SIGNING_KEYSTORE_PASSWORD")
    val keyAlias = (findProperty("signingKeyAlias") as String?)
        ?: System.getenv("SIGNING_KEY_ALIAS") ?: "freecast"
    val keyPassword = (findProperty("signingKeyPassword") as String?)
        ?: System.getenv("SIGNING_KEY_PASSWORD") ?: keystorePassword

    signingConfigs {
        if (keystorePath != null && keystorePassword != null && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release builds must use the owner's stable key, never an incidental debug key.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        // libbox contains large native libraries. Compression keeps OTA updates
        // below the 100 MiB limit in already-installed FreeCast versions.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // Pinned, checksum-verified by fetch-libbox.sh before every CI build.
    implementation(files("libs/libbox.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.4")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

// Fail only for release packaging tasks; unit tests do not require a signing secret.
tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") {
        doFirst {
            check(android.signingConfigs.findByName("release") != null) {
                "Set SIGNING_KEYSTORE, SIGNING_KEYSTORE_PASSWORD and SIGNING_KEY_ALIAS for release signing."
            }
        }
    }
}
