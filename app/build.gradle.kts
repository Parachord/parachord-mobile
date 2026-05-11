import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.play.publisher)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localProp(key: String): String =
    (localProps.getProperty(key) ?: System.getenv(key) ?: "").trim()

android {
    namespace = "com.parachord.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.parachord.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API keys — loaded from local.properties or environment variables
        buildConfigField("String", "ACHORDION_BEARER_TOKEN", "\"${localProp("ACHORDION_BEARER_TOKEN")}\"")
        buildConfigField("String", "LASTFM_API_KEY", "\"${localProp("LASTFM_API_KEY")}\"")
        buildConfigField("String", "LASTFM_SHARED_SECRET", "\"${localProp("LASTFM_SHARED_SECRET")}\"")
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${localProp("SPOTIFY_CLIENT_ID")}\"")
        buildConfigField("String", "SOUNDCLOUD_CLIENT_ID", "\"${localProp("SOUNDCLOUD_CLIENT_ID")}\"")
        buildConfigField("String", "SOUNDCLOUD_CLIENT_SECRET", "\"${localProp("SOUNDCLOUD_CLIENT_SECRET")}\"")
        buildConfigField("String", "APPLE_MUSIC_DEVELOPER_TOKEN", "\"${localProp("APPLE_MUSIC_DEVELOPER_TOKEN")}\"")
        buildConfigField("String", "TICKETMASTER_API_KEY", "\"${localProp("TICKETMASTER_API_KEY")}\"")
        buildConfigField("String", "SEATGEEK_CLIENT_ID", "\"${localProp("SEATGEEK_CLIENT_ID")}\"")
    }

    signingConfigs {
        // Release builds require a keystore supplied via environment variables.
        // If CI_KEYSTORE_PATH is unset, no `ciRelease` config is registered and
        // the release buildType's signingConfig is null — causing the release
        // package task to fail with "APK must be signed" rather than silently
        // using the debug keystore (which would break updates for anyone who
        // installed a legitimately-signed build).
        val ciKeystorePath = System.getenv("CI_KEYSTORE_PATH")
        if (ciKeystorePath != null) {
            val ciKeystorePassword = System.getenv("CI_KEYSTORE_PASSWORD")
                ?: error("CI_KEYSTORE_PASSWORD must be set when CI_KEYSTORE_PATH is set")
            create("ciRelease") {
                storeFile = file(ciKeystorePath)
                storePassword = ciKeystorePassword
                keyAlias = System.getenv("CI_KEYSTORE_ALIAS") ?: "ci-release"
                keyPassword = ciKeystorePassword
            }
        }
        named("debug")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                debugSymbolLevel = "FULL"
            }
            // No debug-keystore fallback — release builds without CI_KEYSTORE_PATH
            // fail loudly at package time (security: H8).
            signingConfig = signingConfigs.findByName("ciRelease")
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
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
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Return defaults instead of throwing for unmocked Android framework
            // methods (e.g. Log.d, Uri.parse). Without this, any production code
            // that calls into android.util.Log fails the test — and mocking
            // every such call-site in every test is not worth the churn.
            isReturnDefaultValues = true
            // Robolectric needs Android resources to be available in unit tests.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // KMP shared module
    implementation(project(":shared"))

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // DataStore + encrypted storage
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Browser (Custom Tabs for OAuth)
    implementation(libs.androidx.browser)

    // Media3 (ExoPlayer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media) // MediaStyle notification for external playback

    // Note: Spotify App Remote SDK + Spotify Auth SDK removed — we use
    // Spotify Web API (Connect) via OkHttp for all Spotify interactions.
    // Removing them also removes the libraries' intent-filter injections
    // that conflicted with our OAuth redirect receiver.

    // Koin DI
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // Networking — Retrofit was dropped in the Smart Links Ktor cutover.
    // OkHttp stays: directly used by scrobblers, AI providers, the JS bridge,
    // OAuth flows, and the still-Retrofit-free `MbidEnrichmentService` (~20
    // files). The shared Ktor clients use OkHttp under the hood as their
    // engine, so dropping OkHttp itself isn't a goal.
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Image Loading
    implementation(libs.coil.compose)

    // ML Kit (face detection for artist image centering)
    implementation(libs.mlkit.face.detection)
    implementation(libs.kotlinx.coroutines.play.services)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// ── Play Store publishing (Triple-T gradle-play-publisher) ────────────
// Only usable when the service-account JSON is available via the
// ANDROID_PUBLISHER_CREDENTIALS env var (set in CI). Local dev builds
// don't need this — tasks simply fail with "missing credentials" if
// run without the env var pointing at a readable JSON.
//
// Upload track is `internal` by default — safest for beta testing.
// Promote internal → closed → open → production via the Play Console
// web UI, or override per-build with `-Pplay.track=beta`.
//
// Prerelease version names (anything containing a hyphen, e.g.
// 0.4.0-beta.1) auto-route to `internal` even if the flag says
// `production`, so stable CLI invocations can't accidentally push a
// beta to prod.
//
// resolutionStrategy = IGNORE makes re-pushing the same tag a no-op
// instead of an error when the versionCode is already live on Play.
play {
    serviceAccountCredentials.set(
        System.getenv("ANDROID_PUBLISHER_CREDENTIALS")?.let { rootProject.file(it) }
            ?: rootProject.file("play-service-account.json"),
    )
    defaultToAppBundles.set(true)
    releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.COMPLETED)
    resolutionStrategy.set(com.github.triplet.gradle.androidpublisher.ResolutionStrategy.IGNORE)
    val isPrerelease = android.defaultConfig.versionName?.contains("-") == true
    val requested = project.findProperty("play.track")?.toString() ?: "internal"
    track.set(if (isPrerelease) "internal" else requested)
}
