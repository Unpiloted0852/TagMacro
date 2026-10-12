import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/*
 * Release signing. Values come from keystore.properties in the project root (local builds,
 * never committed) or from environment variables (the GitHub release workflow). When neither
 * is present, as on F-Droid's build server, the release APK is built unsigned and F-Droid signs it.
 */
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    signingProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }

android {
    namespace = "dev.tagmacro"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.tagmacro"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        val store = signingValue("storeFile", "TAGMACRO_KEYSTORE")
        if (store != null) {
            create("release") {
                storeFile = file(store)
                storePassword = signingValue("storePassword", "TAGMACRO_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "TAGMACRO_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "TAGMACRO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // F-Droid can't verify Google's encrypted dependency list in the APK signing block,
    // so it rejects APKs that contain it. Nothing is lost by leaving it out.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// No dependencies block on purpose. The app uses only the Android framework and the
// Kotlin standard library, which the Kotlin plugin adds automatically.
