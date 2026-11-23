// ADB Box – von FGSeRaser (https://github.com/FGSeRaser)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val laufNummer = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "de.fgseraser.adbbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.fgseraser.adbbox"
        minSdk = 26
        targetSdk = 34
        versionCode = laufNummer
        versionName = "1.0.$laufNummer"
    }

    signingConfigs {
        create("release") {
            val datei = System.getenv("ADBBOX_KEYSTORE_DATEI")
            if (datei != null && file(datei).exists()) {
                storeFile = file(datei)
                storePassword = System.getenv("ADBBOX_KEYSTORE_PASS")
                keyAlias = "adbbox"
                keyPassword = System.getenv("ADBBOX_KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val sc = signingConfigs.getByName("release")
            signingConfig = if (sc.storeFile != null) sc else signingConfigs.getByName("debug")
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
    packaging {
        resources.excludes += setOf("META-INF/*.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.mwiede:jsch:0.2.20")
    implementation("com.google.zxing:core:3.5.3")   // QR-Code fürs Koppeln
}
