import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidMultiplatformLibrary)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.androidxRoom3)
}

kotlin {
  listOf(
      iosArm64(),
      iosSimulatorArm64(),
    )
    .forEach { iosTarget ->
      iosTarget.binaries.framework {
        baseName = "Shared"
        isStatic = true
        binaryOption("bundleId", "xyz.mcxross.flare.shared")
      }
    }

  android {
    namespace = "xyz.mcxross.flare.shared"
    compileSdk {
      version =
        release(libs.versions.android.compileSdk.get().toInt()) {
          minorApiLevel = 0
        }
    }
    minSdk = libs.versions.android.minSdk.get().toInt()

    compilerOptions {
      jvmTarget = JvmTarget.JVM_11
    }
    androidResources {
      enable = true
    }
    withHostTest {
      isIncludeAndroidResources = true
    }
    withDeviceTestBuilder {
      sourceSetTreeName = "test"
    }
      .configure {
        instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
      }
  }

  sourceSets {
    androidMain.dependencies {
      implementation(libs.androidx.biometric)
      implementation(libs.compose.uiToolingPreview)
      implementation(libs.compose.uiTooling)
      implementation(libs.ktor.client.okhttp)
    }
    commonMain.dependencies {
      implementation(project(":decibel"))
      api(libs.kaptos)
      implementation(libs.compose.runtime)
      implementation(libs.compose.foundation)
      implementation(libs.compose.material3)
      implementation(compose.materialIconsExtended)
      implementation(libs.compose.ui)
      implementation(libs.compose.components.resources)
      implementation(libs.compose.uiToolingPreview)
      implementation(libs.androidx.lifecycle.viewmodelCompose)
      implementation(libs.androidx.lifecycle.runtimeCompose)
      implementation(libs.androidx.datastore.preferences)
      implementation(libs.androidx.room3.runtime)
      implementation(libs.androidx.sqlite.bundled)
      implementation(libs.koin.compose)
      implementation(libs.koin.compose.viewmodel)
      implementation(libs.koin.core)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.datetime)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.ktor.client.content.negotiation)
      implementation(libs.ktor.client.core)
      implementation(libs.ktor.client.websockets)
      implementation(libs.ktor.serialization.kotlinx.json)
      implementation(libs.navigation.compose)
      implementation(libs.vico.compose.m3)
      implementation(libs.coil.compose)
      implementation(libs.coil.network.ktor3)
      implementation(libs.coil.svg)
      implementation(libs.compose.shimmer)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
    }
    iosMain.dependencies {
      implementation(libs.ktor.client.darwin)
    }
  }
}

dependencies {
  androidRuntimeClasspath(libs.compose.uiTooling)
  add("kspAndroid", libs.androidx.room3.compiler)
  add("kspIosArm64", libs.androidx.room3.compiler)
  add("kspIosSimulatorArm64", libs.androidx.room3.compiler)
}

room3 {
  schemaDirectory("$projectDir/schemas")
}

// AGP's host-test lint tasks consume Room's KSP output, but the current
// Android-KMP/KSP integration does not declare that relationship itself.
// Keep clean and parallel verification builds deterministic until the plugins do.
afterEvaluate {
  val kspAndroidHostTest = tasks.named("kspAndroidHostTest")
  listOf("generateAndroidHostTestLintModel", "lintAnalyzeAndroidHostTest").forEach { taskName ->
    tasks.named(taskName).configure {
      dependsOn(kspAndroidHostTest)
    }
  }
}
