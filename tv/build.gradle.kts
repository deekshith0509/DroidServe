import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing from a git-ignored keystore.properties (or env). Absent → debug-key signed,
// still installable for personal sideloading; drop in a real keystore for distribution.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = (keystoreProps.getProperty("storeFile") ?: System.getenv("DS_STORE_FILE")) != null

android {
    namespace = "com.deekshith.droidserve.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deekshith.droidserve.tv"
        minSdk = 21
        targetSdk = 35
        versionCode = 11
        versionName = "1.7.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile") ?: System.getenv("DS_STORE_FILE"))
            storePassword = keystoreProps.getProperty("storePassword") ?: System.getenv("DS_STORE_PASSWORD")
            keyAlias = keystoreProps.getProperty("keyAlias") ?: System.getenv("DS_KEY_ALIAS")
            keyPassword = keystoreProps.getProperty("keyPassword") ?: System.getenv("DS_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
        debug {
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // ViewModel + lifecycle for Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM test classpath (android.jar's is a unit-test stub).
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
