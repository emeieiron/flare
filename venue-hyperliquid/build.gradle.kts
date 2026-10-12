import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Hyperliquid behind Flare's venue seam: the Koin module that binds Hyperliquid's repositories. The
// app builds that trade on Hyperliquid link this module (Android's hyperliquid flavor,
// :ios-hyperliquid).
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidMultiplatformLibrary)
  alias(libs.plugins.kotlinSerialization)
}

kotlin {
  listOf(iosArm64(), iosSimulatorArm64())

  android {
    namespace = "xyz.mcxross.flare.venue.hyperliquid"
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
      implementation(project(":strategies"))
      implementation(project(":hyperliquid"))
      implementation(libs.koin.core)
      implementation(libs.ktor.client.core)
      // The market cache lives in the app's database.
      implementation(libs.androidx.room3.runtime)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
      implementation(libs.ktor.client.content.negotiation)
      implementation(libs.ktor.serialization.kotlinx.json)
      implementation(libs.ktor.client.websockets)
      implementation(libs.androidx.datastore.preferences)
    }
  }
}
