plugins {
  alias(libs.plugins.androidTest)
  alias(libs.plugins.baselineprofile)
}

android {
  namespace = "xyz.mcxross.flare.baselineprofile"
  compileSdk = libs.versions.android.compileSdk.get().toInt()

  defaultConfig {
    // Profile generation without root needs Android 13; benchmarks run from Android 7.
    minSdk = 28
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    // Benchmarks on the emulator are for comparing builds, not absolute numbers; a phone runs clean.
    testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
  }

  targetProjectPath = ":androidApp"

  // The same flavors as :androidApp, so each app build gets its own profile and benchmarks.
  flavorDimensions += "venue"
  productFlavors {
    create("decibel") {
      dimension = "venue"
      buildConfigField("String", "TARGET_PACKAGE", "\"xyz.mcxross.flare.decibel\"")
    }
    // No profile is generated for it yet.
    create("hyperliquid") {
      dimension = "venue"
      buildConfigField("String", "TARGET_PACKAGE", "\"xyz.mcxross.flare.hyperliquid\"")
    }
  }

  buildFeatures {
    buildConfig = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

kotlin {
  compilerOptions {
    jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
  }
}

// Runs on whatever device is connected: the emulator, or a phone over USB.
baselineProfile {
  useConnectedDevices = true
}

dependencies {
  implementation(libs.androidx.testExt.junit)
  implementation(libs.androidx.uiautomator)
  implementation(libs.androidx.benchmark.macro.junit4)
}
