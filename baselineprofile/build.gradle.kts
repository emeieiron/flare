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
