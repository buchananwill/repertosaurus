plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.repertosaurus.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.repertosaurus"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-phase1"

        // Schema-compatibility S12: this module gains a test source set. Two boot crashes in
        // one session both landed in the one layer nothing instantiated — a property
        // initialisation-order NPE, and `no such table: saved_view` — and a test that merely
        // constructs `SessionViewModel` against an empty, a stale and a current database would
        // have caught both. The tests are instrumented rather than local because the thing
        // under test is Android's SQLite and Android's main looper; neither has a JVM stand-in
        // that would have found either bug.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // journal session 11, F18 B1: the shared core's immutable types, declared stable to the
        // Compose compiler, which never compiles `shared` and so cannot infer it.
        freeCompilerArgs += listOf(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:stabilityConfigurationPath=" +
                project.file("compose-stability.conf").absolutePath,        )
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(platform("androidx.compose:compose-bom:2024.02.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")

    // The Session screen's state holder. viewModelScope survives a rotation, which is what
    // keeps a session's optimistic logs and their pending undos alive across one.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Pure JVM checks of the theme's values, such as VI2's contrast, with no device.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.22")
    // Safety review F20 B2: the ratings editor's ViewModel on the JVM, on a dispatcher the test drives.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")

    // S10, S12, S13. `ui-test-manifest` is what supplies the bare ComponentActivity the
    // Compose rule hosts, so the recovery screen can be booted without MainActivity.
    androidTestImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.22")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:core-ktx:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.02"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
