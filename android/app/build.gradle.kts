plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// Backend base URL (uplink /ingest + downlink /channels, /pubkey, /health).
//
// debug   → emulator host loopback by default; override for a LAN backend (physical
//           phones) with `-PBACKEND_BASE_URL=http://192.168.x.y:3000`. The debug
//           network-security-config permits cleartext to any host.
// release → deployed HTTPS backend. No host exists yet, so it defaults to a
//           non-resolvable placeholder (a release build just fails the best-effort
//           call rather than hitting a wrong host). Override via
//           `-PBACKEND_RELEASE_URL=https://…` or a BACKEND_RELEASE_URL env var.
//           Must be HTTPS — the release network-security-config is HTTPS-only.
fun prop(name: String, fallback: String): String =
    (project.findProperty(name) as String?) ?: System.getenv(name) ?: fallback

val backendDebugUrl: String = prop("BACKEND_BASE_URL", "http://10.0.2.2:3000")
val backendReleaseUrl: String = prop("BACKEND_RELEASE_URL", "https://guacamaya.invalid")

android {
    namespace = "net.guacamaya"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.guacamaya"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Deployed HTTPS backend (placeholder until one exists — see backendReleaseUrl).
            buildConfigField("String", "BACKEND_BASE_URL", "\"$backendReleaseUrl\"")
        }
        debug {
            isMinifyEnabled = false
            // Emulator loopback by default; override with -PBACKEND_BASE_URL for a LAN backend.
            // Cleartext allowed broadly by the debug network-security-config.
            buildConfigField("String", "BACKEND_BASE_URL", "\"$backendDebugUrl\"")
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.bouncycastle)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.play.services.location)
    implementation(libs.androidx.work.runtime)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
