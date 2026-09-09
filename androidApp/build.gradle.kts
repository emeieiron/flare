import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val flareWorkerUrl =
  providers
    .gradleProperty("flareWorkerUrl")
    // Android Emulator routes 10.0.2.2 to the development host. Using
    // 127.0.0.1 here would address the emulator itself.
    .orElse("http://10.0.2.2:8787")

plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.composeCompiler)
}

kotlin {
  compilerOptions {
    jvmTarget = JvmTarget.JVM_11
  }
}

dependencies {
  implementation(project(":shared"))

  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.fragment)
  implementation(libs.androidx.room3.runtime)

  implementation(libs.compose.uiToolingPreview)
  debugImplementation(libs.compose.uiTooling)
  debugImplementation(project(":decibel"))
  debugImplementation(libs.compose.material3)
  debugImplementation(libs.compose.foundation)
}

android {
  namespace = "xyz.mcxross.flare"
  compileSdk {
    version =
      release(libs.versions.android.compileSdk.get().toInt()) {
        minorApiLevel = 0
      }
  }

  defaultConfig {
    applicationId = "xyz.mcxross.flare"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk {
      version = release(libs.versions.android.targetSdk.get().toInt())
    }
    versionCode = 1
    versionName = "1.0"
    resValue("string", "flare_worker_url", flareWorkerUrl.get())
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    resValues = true
  }
}
