plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Overridable so a release build can take its version from the git tag:
//   ./gradlew assembleRelease -PversionName=1.0.0 -PversionCode=10000
val appVersionName = (findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode = (findProperty("versionCode") as String?)?.toInt() ?: 100
val embeddedAceDir = providers.gradleProperty("embeddedAceDir").orNull?.let(::file)
val embeddedAce = embeddedAceDir != null
val embeddedAceRelease = providers.gradleProperty("embeddedAceRelease").orNull == "true"
val otaGitHubClientId = providers.gradleProperty("otaGitHubClientId").orElse("").get()
require(otaGitHubClientId.isEmpty() || otaGitHubClientId.matches(Regex("[a-zA-Z0-9.]{10,64}"))) {
    "Invalid GitHub App client ID."
}
check(!embeddedAceRelease || (embeddedAce && System.getenv("GITHUB_ACTIONS") != "true")) {
    "An embedded Ace Stream release requires a local payload and local signing."
}

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
        manifestPlaceholders["appLabel"] = "@string/app_name"
        buildConfigField("boolean", "EMBEDDED_ACE", embeddedAce.toString())
        buildConfigField("String", "OTA_GITHUB_CLIENT_ID", "\"$otaGitHubClientId\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += if (embeddedAce) listOf("arm64-v8a") else listOf("arm64-v8a", "armeabi-v7a", "x86_64")
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
        debug {
            if (embeddedAce && !embeddedAceRelease) {
                applicationIdSuffix = ".ace"
                resValue("string", "local_ace_app_name", "AirScreen · Ace")
                manifestPlaceholders["appLabel"] = "@string/local_ace_app_name"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release builds must use the owner's stable key, never an incidental debug key.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    if (embeddedAceDir != null) {
        check(embeddedAceDir.resolve("jniLibs/arm64-v8a/libacepython.so").isFile) {
            "Prepare embeddedAceDir with prepare-embedded-ace.py first."
        }
        sourceSets.getByName(if (embeddedAceRelease) "release" else "debug").apply {
            assets.srcDir(embeddedAceDir.resolve("assets"))
            assets.srcDir("src/embeddedAce/assets")
            jniLibs.srcDir(embeddedAceDir.resolve("jniLibs"))
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
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
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    // Pinned, checksum-verified by fetch-libbox.sh before every CI build.
    implementation(files("libs/libbox.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.24.1")
    implementation("com.google.zxing:core:3.5.4")

    testImplementation("junit:junit:4.13.2")
    // Android supplies org.json at runtime; local JVM parser tests need its implementation.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

// Fail only for release packaging tasks; unit tests do not require a signing secret.
tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") {
        doFirst {
            check(!embeddedAce || embeddedAceRelease) { "Use the explicit local embedded release option." }
            check(android.signingConfigs.findByName("release") != null) {
                "Set SIGNING_KEYSTORE, SIGNING_KEYSTORE_PASSWORD and SIGNING_KEY_ALIAS for release signing."
            }
        }
    }
}
