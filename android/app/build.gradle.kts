plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// Release /ingest endpoint. No backend is deployed yet, so this defaults to a
// non-resolvable placeholder (a release build just fails the best-effort upload
// rather than hitting a wrong host). Override once the HTTPS host exists, via
// `-PINGEST_RELEASE_URL=https://…` or an INGEST_RELEASE_URL env var. Must be HTTPS:
// the network-security-config only whitelists cleartext for the dev loopback.
val ingestReleaseUrl: String =
    (project.findProperty("INGEST_RELEASE_URL") as String?)
        ?: System.getenv("INGEST_RELEASE_URL")
        ?: "https://guacamaya.invalid"

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
            // Deployed HTTPS backend (placeholder until one exists — see ingestReleaseUrl).
            buildConfigField("String", "INGEST_BASE_URL", "\"$ingestReleaseUrl\"")
        }
        debug {
            isMinifyEnabled = false
            // Emulator host loopback (10.0.2.2 → host's localhost:3000), cleartext
            // allowed by network_security_config. Override per device as needed.
            buildConfigField("String", "INGEST_BASE_URL", "\"http://10.0.2.2:3000\"")
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
