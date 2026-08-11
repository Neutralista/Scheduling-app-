plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.waypoint.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.waypoint.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 183
        versionName = "0.13.29"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("ci") {
            storeFile = System.getenv("SIGNING_STORE_PATH")?.let { file(it).takeIf { f -> f.exists() } }
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            val ciConfig = signingConfigs.getByName("ci")
            if (ciConfig.storeFile != null) signingConfig = ciConfig
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

// Kotlin 2.1 merged kotlin-stdlib-jdk7/jdk8 into kotlin-stdlib; redirect legacy
// transitive references (e.g. from material → fragment → appcompat) to avoid
// DexArchiveMergerException caused by duplicate classes at dex merge time.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" &&
            (requested.name == "kotlin-stdlib-jdk7" || requested.name == "kotlin-stdlib-jdk8")
        ) {
            useTarget("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
            because("Kotlin 2.1+ merged jdk7/jdk8 into kotlin-stdlib")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.health.connect:connect-client:1.1.0-rc01")

    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("org.mozilla:rhino:1.7.14")
}
