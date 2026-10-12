import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidMultiplatformLibrary)
  alias(libs.plugins.kotlinSerialization)
}

kotlin {
  jvm {
    compilerOptions.jvmTarget = JvmTarget.JVM_11
    testRuns["test"].executionTask.configure { useJUnitPlatform() }
  }

  listOf(iosArm64(), iosSimulatorArm64())

  android {
    namespace = "xyz.mcxross.flare.jev"
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
      api(project(":strategies"))
      api(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
      api(libs.ktor.client.core)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
    }
    jvmTest.dependencies {
      implementation(libs.kotlin.testJunit5)
    }
  }
}
