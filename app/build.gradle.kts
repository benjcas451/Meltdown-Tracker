import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Compose-Compiler; Kotlin selbst kommt ueber AGPs Built-in Kotlin.
    alias(libs.plugins.kotlin.compose)
}

// Signing-Daten aus key.properties im Projekt-Root laden (nicht im Git,
// siehe .gitignore und key.properties.example). Die CI erzeugt die Datei aus
// den Repository-Secrets (.github/workflows/build-release.yml).
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "org.dwarftsch.meltdown"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.dwarftsch.meltdown"
        // API 26: adaptive Icons ohne PNG-Fallback und java.time ohne
        // Library-Desugaring.
        minSdk = 26
        targetSdk = 37
        // Die CI uebergibt -PbuildNumber=<run_number> (siehe
        // .github/workflows/build-release.yml), lokal gilt der Fallback.
        versionCode = (findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                // Absolute Pfade unveraendert, relative relativ zum Root.
                val configured = File(keystoreProperties["storeFile"] as String)
                storeFile = if (configured.isAbsolute) configured else rootProject.file(configured.path)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // R8 verkleinert das Bundle und optimiert den Bytecode; Play
            // verlangt die Codeoptimierung. Keep-Regeln:
            // src/main/keepRules/rules.keep. Die Mapping-Datei legt AGP
            // automatisch ins AAB.
            optimization {
                enable = true
            }
            // Ohne key.properties mit dem Debug-Key signieren, damit sich ein
            // Release-Build lokal auch ohne Keystore erzeugen laesst.
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // REST-API inkl. mTLS (Client-Zertifikat im TLS-Handshake).
    implementation(libs.okhttp)
    // Zertifikats-Ordner via Storage Access Framework.
    implementation(libs.androidx.documentfile)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
