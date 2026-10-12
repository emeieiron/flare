import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidMultiplatformLibrary)
  alias(libs.plugins.kotlinSerialization)
}

kotlin {
  jvm {
    compilerOptions.jvmTarget = JvmTarget.JVM_11
    testRuns["test"].executionTask.configure {
      useJUnitPlatform()
      // The live testnet tests skip without these; changing them must re-run the tests.
      listOf(
          "FLARE_HYPERLIQUID_LIVE",
          "FLARE_HYPERLIQUID_TEST_KEY_FILE",
          "FLARE_HYPERLIQUID_TEST_VAULT",
          "FLARE_HYPERLIQUID_TEST_BUILDER",
        )
        .forEach { name ->
          inputs.property(name, providers.environmentVariable(name).orElse("")).optional(true)
        }
    }
  }

  listOf(iosArm64(), iosSimulatorArm64())

  android {
    namespace = "xyz.mcxross.flare.hyperliquid"
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
      api(libs.kotlinx.coroutines.core)
      implementation(libs.fastkrypto)
      implementation(libs.kotlinx.datetime)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.ktor.client.content.negotiation)
      implementation(libs.ktor.client.core)
      implementation(libs.ktor.client.websockets)
      implementation(libs.ktor.serialization.kotlinx.json)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
    }
    androidMain.dependencies {
      implementation(libs.ktor.client.okhttp)
    }
    iosMain.dependencies {
      implementation(libs.ktor.client.darwin)
    }
    jvmMain.dependencies {
      implementation(libs.ktor.client.cio)
    }
    jvmTest.dependencies {
      implementation(libs.kotlin.testJunit5)
    }
  }
}
