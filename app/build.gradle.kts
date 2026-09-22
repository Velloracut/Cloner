plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.vellora.dualapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vellora.dualapp"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        getByName("debug") {
            // Fixed keystore (checked into the repo as app/debug.keystore)
            // so every build — CI or local — signs with the SAME key.
            // Without this, each fresh machine/runner generates its own
            // random debug key, and Android refuses to install a new APK
            // over an old one signed with a different key ("package
            // conflicts with an existing package").
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // Maintained hidden-API-restriction bypass (Unsafe-based, not the
    // fragile meta-reflection trick — some Android versions now
    // force-blacklist the exact method our old hand-rolled version relied on).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // Needed so we can proactively call WorkManager.initialize() ourselves
    // for cloned apps — their own auto-init (a ContentProvider Android
    // creates automatically during a NORMAL app launch) never runs here
    // since we skip full ContentProvider bootstrapping. Confirmed crash:
    // "WorkManager is not initialized properly" in Gallery app's onCreate().
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Pine: real ART-level method hooking (Xposed-API compatible), used
    // ONLY for android.content.res.ApkAssets.loadOverlayFromPath — a
    // static method our normal reflection-subclass trick can't intercept.
    // Confirmed root cause (via BlackBox's source + our own crash logs
    // across 3 different apps) of the recurring Resources.NotFoundException
    // crashes: the framework tries to apply an OEM Runtime Resource Overlay
    // that doesn't correctly map onto our virtualized AssetManager. Pine
    // does NOT replace anything else in the engine — it's scoped to this
    // one confirmed problem.
    compileOnly("de.robv.android.xposed:api:82")
    implementation("top.canyie.pine:core:0.3.0")
    implementation("top.canyie.pine:xposed:0.2.0")
}
