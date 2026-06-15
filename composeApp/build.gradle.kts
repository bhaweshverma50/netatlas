import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.compose)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
}

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core)
            implementation(libs.room.runtime)
            implementation(libs.room.ktx)
            implementation(libs.androidx.work)
            // M3.2 — MapLibre map for the coverage heatmap.
            implementation(libs.maplibre.android)
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.mockito.core)
            implementation(libs.mockito.kotlin)
        }
        androidInstrumentedTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.room.testing)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
    }
}

// Room codegen for the Android target. In a KMP module the KSP configuration is
// target-specific (kspAndroid), NOT the plain `ksp` config used by pure-Android modules.
dependencies {
    add("kspAndroid", libs.room.compiler)
}

// Release signing config for the POC.
//
// Credentials come from composeApp/keystore.properties when present (NOT committed); otherwise
// we fall back to the documented POC literals. The keystore itself (composeApp/netatlas-release.keystore)
// is gitignored, so a fresh clone won't have it — `hasReleaseKeystore` below guards that case and
// lets the release build fall back to debug signing rather than failing.
val keystorePropsFile = rootProject.file("composeApp/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseStoreFile = rootProject.file(
    keystoreProps.getProperty("storeFile") ?: "composeApp/netatlas-release.keystore",
)
val hasReleaseKeystore = releaseStoreFile.exists()

android {
    namespace = "atlas.netatlas"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")

    defaultConfig {
        applicationId = "atlas.netatlas"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword") ?: "netatlas"
                keyAlias = keystoreProps.getProperty("keyAlias") ?: "netatlas"
                keyPassword = keystoreProps.getProperty("keyPassword") ?: "netatlas"
            }
        }
    }

    buildTypes {
        release {
            // Keep minify OFF for the POC: kotlinx.serialization / Room / Ktor would need
            // keep rules, which are out of scope here.
            isMinifyEnabled = false
            // Sign with the release config when the keystore is present; otherwise fall back
            // to debug signing so anyone without the keystore can still build a runnable APK.
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
