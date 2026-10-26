import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// The decibel flavor's settings: the Flare Worker it talks to and the builder that receives
// Flare's fee.
val flareWorkerUrl =
  providers
    .gradleProperty("flareWorkerUrl")
    // Decibel is mainnet-only, so an unqualified build is a real one. For a local Worker pass
    // -PflareWorkerUrl=http://10.0.2.2:8787; the Android Emulator routes 10.0.2.2 to the
    // development host, where 127.0.0.1 would address the emulator itself.
    .orElse("https://api.mcxross.xyz")

val flareBuilderAddress =
  providers
    .gradleProperty("flareBuilderAddress")
    .orElse("0xe05e75a9f25b254fcc354d5a68d7d15f7b97ac6ee56922fac3c1c27009d0c25b")

// The hyperliquid flavor's network: testnet unless -PflareHyperliquidNetwork=mainnet.
val flareHyperliquidNetwork = providers.gradleProperty("flareHyperliquidNetwork").orElse("testnet")

// Flare's builder on Hyperliquid, which receives the optional builder fee; none unless set.
val flareHyperliquidBuilderAddress =
  providers.gradleProperty("flareHyperliquidBuilderAddress").orElse("")

// A distributable release build is signed with the key CI passes in; without it, release builds
// stay unsigned for local use. See .github/workflows/release-android.yml.
val releaseKeystore = providers.gradleProperty("flareKeystoreFile")
val releaseSigned = releaseKeystore.isPresent

// The app version lives in version.properties at the repository root, which iOS reads too.
// 1.2.3 builds versionCode 10203.
val flareVersionName: String =
  providers
    .fileContents(rootProject.layout.projectDirectory.file("version.properties"))
    .asText
    .map { Properties().apply { load(it.reader()) }.getProperty("FLARE_VERSION").orEmpty().trim() }
    .get()
val flareVersionCode: Int =
  Regex("""(\d+)\.(\d{1,2})\.(\d{1,2})""")
    .matchEntire(flareVersionName)
    ?.destructured
    ?.let { (major, minor, patch) -> major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt() }
    ?: error("FLARE_VERSION in version.properties must look like 1.2.3 with minor and patch below 100, got '$flareVersionName'")

plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.baselineprofile)
}

kotlin {
  compilerOptions {
    jvmTarget = JvmTarget.JVM_11
  }
}

dependencies {
  implementation(project(":shared"))

  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.fragment)
  implementation(libs.androidx.room3.runtime)
  // Hands the shipped baseline profile to ART on installs that don't apply it themselves, like sideloads.
  implementation(libs.androidx.profileinstaller)
  baselineProfile(project(":baselineprofile"))

  implementation(libs.compose.uiToolingPreview)
  debugImplementation(libs.compose.uiTooling)
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
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk {
      version = release(libs.versions.android.targetSdk.get().toInt())
    }
    versionCode = flareVersionCode
    versionName = flareVersionName
  }
  // One app per exchange: each flavor links its :venue-* module, provides VenueEntry from
  // src/<flavor>, and installs under its own application ID.
  flavorDimensions += "venue"
  productFlavors {
    create("decibel") {
      dimension = "venue"
      applicationId = "xyz.mcxross.flare.decibel"
      resValue("string", "flare_worker_url", flareWorkerUrl.get())
      resValue("string", "flare_builder_address", flareBuilderAddress.get())
      if (releaseSigned) {
        // A signed build is one people install: it must reach a real, encrypted Worker.
        check(flareWorkerUrl.get().startsWith("https://")) {
          "A signed release needs an HTTPS flareWorkerUrl, not ${flareWorkerUrl.get()}"
        }
      }
    }
    create("hyperliquid") {
      dimension = "venue"
      applicationId = "xyz.mcxross.flare.hyperliquid"
      check(flareHyperliquidNetwork.get() in setOf("testnet", "mainnet")) {
        "flareHyperliquidNetwork must be testnet or mainnet"
      }
      resValue("string", "flare_hyperliquid_network", flareHyperliquidNetwork.get())
      resValue("string", "flare_hyperliquid_builder_address", flareHyperliquidBuilderAddress.get())
    }
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
  signingConfigs {
    if (releaseSigned) {
      create("release") {
        storeFile = file(releaseKeystore.get())
        storePassword = providers.gradleProperty("flareKeystorePassword").get()
        keyAlias = providers.gradleProperty("flareKeyAlias").get()
        keyPassword = providers.gradleProperty("flareKeyPassword").get()
      }
    }
  }
  buildTypes {
    release {
      if (releaseSigned) {
        signingConfig = signingConfigs.getByName("release")
      }
      // Phones only: 64- and 32-bit ARM. The x86 and obsolete ABIs some libraries carry add ~14 MB.
      ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
      // R8 shrinks, optimises and obfuscates the release build; resources nothing references go too.
      isMinifyEnabled = true
      isShrinkResources = true
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

// After android {}: a flavor's configurations exist once the flavor is declared.
dependencies {
  // Each flavor links the exchange it trades on.
  "decibelImplementation"(project(":venue-decibel"))
  "hyperliquidImplementation"(project(":venue-hyperliquid"))
}

// The baseline profile plugin's build types don't read their own src/<buildType> folders, so the
// overlay that lets them reach the local worker is added to those variants here. Only the decibel
// flavor has a local worker. With flavors, the plugin generates and reads each flavor's profiles in
// src/<flavor>Release/generated/baselineProfiles (generateDecibelReleaseBaselineProfile).
androidComponents {
  onVariants { variant ->
    if (
      variant.flavorName == "decibel" &&
        variant.buildType in setOf("nonMinifiedRelease", "benchmarkRelease")
    ) {
      variant.sources.manifests.addStaticManifestFile(file("src/benchmark/AndroidManifest.xml").path)
    }
  }
}
