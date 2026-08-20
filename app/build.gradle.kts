import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// signing creds live only in the git-ignored keystore.properties, never in source.
// loaded up here (not inside android {}) so Properties and stdlib apply don't collide
// with gradle's own apply.
val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}
val hasSigning = keystoreProps.getProperty("storePassword") != null

android {
    namespace = "com.subflow"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.subflow"
        minSdk = 30
        targetSdk = 35
        versionCode = 70
        versionName = "2.39.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DWHISPER_BUILD_TESTS=OFF",
                    "-DWHISPER_BUILD_EXAMPLES=OFF"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "27.0.12077973"

    signingConfigs {
        create("release") {
            if (hasSigning) {
                storeFile = file(keystoreProps.getProperty("storeFile", "subflow-release.jks"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false // keep debug builds fast to compile
        }
        release {
            // R8 fully off. release runs the same code as debug, nothing gets stripped.
            isMinifyEnabled = false
            isShrinkResources = false
            // sign only if a keystore is configured, otherwise ship unsigned so CI
            // without the key still builds
            signingConfig = if (hasSigning) signingConfigs.getByName("release") else null
        }
    }

    bundle { language { enableSplit = true } }

    lint {
        // false positive: MainActivity is a ComponentActivity, the app uses no Fragments
        disable += "InvalidFragmentVersionForActivityResult"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.ui.text.ExperimentalTextApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true // the About screen reads its version from BuildConfig
    }

    // plain output name: SubFlow.apk instead of app-debug.apk
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "SubFlow.apk"
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.animation:animation:1.7.5")
    implementation("androidx.compose.material:material-icons-extended:1.7.5")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.google.mlkit:text-recognition:16.0.0") // SS OCR

    // preview player: HTTP stream with the produced .srt side-loaded
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")     // Background tasks

    // ffmpeg-kit fork that still lives on maven central (arthenica's got removed).
    // "min" keeps the built-in bits we use (matroska demuxer, srt codec, pcm, http)
    // and drops external codecs, about half the size of "full"
    implementation("com.antonkarpenko:ffmpeg-kit-min:2.1.0")

    // pure-java RAR extraction. turkish sources (turkcealtyazi and others) ship .rar
    implementation("com.github.junrar:junrar:7.5.5")

    // pure-java XZ. animetosho serves subs as .ass.xz / .srt.xz
    implementation("org.tukaani:xz:1.9")

    // bittorrent client. pulls a subtitle track from a torrent's MKV via on-demand
    // piece streaming, without downloading the whole video
    implementation("com.frostwire:jlibtorrent:1.2.0.18")
    implementation("com.frostwire:jlibtorrent-android-arm64:1.2.0.18")
    implementation("com.frostwire:jlibtorrent-android-arm:1.2.0.18")

    testImplementation("junit:junit:4.13.2")
}
