import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Decibel behind Flare's venue seam: the Koin module that binds Decibel's repositories. The app
// builds that trade on Decibel link this module (Android's decibel flavor, :ios-decibel).
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidMultiplatformLibrary)
  alias(libs.plugins.kotlinSerialization)
}

kotlin {
  listOf(iosArm64(), iosSimulatorArm64())

  android {
    namespace = "xyz.mcxross.flare.venue.decibel"
    compileSdk {
      version =
        release(libs.versions.android.compileSdk.get().toInt()) {
          minorApiLevel = 0
        }
    }
    minSdk = libs.versions.android.minSdk.get().toInt()
    compilerOptions.jvmTarget = JvmTarget.JVM_11
    withHostTest {}
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":shared"))
      implementation(project(":decibel"))
      implementation(libs.koin.core)
      implementation(libs.ktor.client.core)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
      implementation(libs.ktor.client.content.negotiation)
      implementation(libs.ktor.serialization.kotlinx.json)
    }
    getByName("androidHostTest").dependencies {
      implementation(libs.androidx.datastore.preferences)
      implementation(libs.androidx.room3.runtime)
    }
    iosTest.dependencies {
      implementation(libs.androidx.datastore.preferences)
    }
  }
}
