plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        compilations.all {
            compilerOptions.configure {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
    }
    // iOS targets — produce a static Shared.framework that the iOS Xcode
    // project links and `import`s. Static is preferred over dynamic for
    // KMP frameworks: smaller app launch overhead, no embedded-dylib
    // gymnastics, and Swift call-sites resolve through the same symbol
    // table as the rest of the binary. The framework name is the type
    // prefix Swift sees (`import Shared`).
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    // Share iOS source sets
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            // multiplatform-settings (Phase 9B): KMP-friendly key-value store
            // backed by SharedPreferences on Android, NSUserDefaults on iOS.
            // The `coroutines` artifact provides Flow-based observable settings
            // that mirror DataStore's reactive surface.
            api(libs.multiplatform.settings)
            api(libs.multiplatform.settings.coroutines)
            // Ktor HTTP client (Phase 2)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
            // SQLDelight (Phase 3)
            api(libs.sqldelight.coroutines)
            // Koin (Phase 4)
            api(libs.koin.core)
            // KMP crypto — MD5 for Last.fm api_sig (Phase 9E.1.0)
            implementation(libs.kotlincrypto.hash.md5)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            // For runTest in suspend-function tests (e.g. ProtocolInputResolverTest).
            implementation(libs.kotlinx.coroutines.test)
            // In-memory MapSettings-backed KvStore for SettingsStore tests (#289).
            implementation(libs.multiplatform.settings.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            api(libs.sqldelight.android.driver)
            // EncryptedSharedPreferences for AndroidSecureTokenStore (Phase 9B Stage 3).
            // Backed by Android Keystore AES-256-GCM. security: C4.
            implementation(libs.androidx.security.crypto)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
    }
}

sqldelight {
    databases {
        create("ParachordDb") {
            packageName.set("com.parachord.shared.db")
        }
    }
}

android {
    namespace = "com.parachord.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // Return defaults for unmocked Android stubs (e.g. android.util.Log.*) so
        // shared production code paths that log via the platform Log expect/actual
        // don't throw "Method not mocked" RuntimeExceptions in JVM unit tests.
        // Mirrors the same setting on :app — see CLAUDE.md "Common Mistakes".
        unitTests.isReturnDefaultValues = true
    }
}
