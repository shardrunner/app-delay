import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

/**
 * Release-signing coordinates, read from the gitignored `local.properties`.
 *
 * Opt-in: with no `release.storeFile` entry the release build stays unsigned
 * (`app-release-unsigned.apk`), which is enough for R8 size checks. Once the key is
 * declared, all four entries are required — a half-configured keystore fails the build
 * rather than silently producing an unsigned APK.
 */
val releaseKeystore: Map<String, String>? by lazy {
    val properties =
        Properties().apply {
            providers
                .fileContents(layout.settingsDirectory.file("local.properties"))
                .asText
                .orNull
                ?.let { load(it.reader()) }
        }
    if (properties.getProperty("release.storeFile") == null) {
        null
    } else {
        listOf(
            "release.storeFile",
            "release.storePassword",
            "release.keyAlias",
            "release.keyPassword",
        ).associateWith { key ->
            requireNotNull(properties.getProperty(key)) {
                "local.properties declares release signing but is missing '$key'"
            }
        }
    }
}

android {
    namespace = "com.androidpause"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.androidpause"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        releaseKeystore?.let { keystore ->
            create("release") {
                storeFile = file(keystore.getValue("release.storeFile"))
                storePassword = keystore.getValue("release.storePassword")
                keyAlias = keystore.getValue("release.keyAlias")
                keyPassword = keystore.getValue("release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

room {
    // Exported schema JSON is the baseline every future Room migration is written against.
    schemaDirectory("$projectDir/schemas")
}

ktlint {
    android.set(true)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
