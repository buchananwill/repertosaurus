plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("app.cash.sqldelight")
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("app.cash.sqldelight:runtime:2.0.1")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            dependencies {
                api("app.cash.sqldelight:android-driver:2.0.1")
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                // Plain JVM SQLite driver: Android unit tests run on the host JVM, so the
                // generated schema and every .sq query can be exercised without a device.
                implementation("app.cash.sqldelight:sqlite-driver:2.0.1")
            }
        }
    }
}

android {
    namespace = "dev.repertosaurus.shared"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("RepertosaurusDatabase") {
            packageName.set("dev.repertosaurus.db")
        }
    }
}
