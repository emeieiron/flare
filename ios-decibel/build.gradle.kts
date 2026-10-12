// The iOS app's Kotlin framework for the Decibel build. It links :shared with :venue-decibel and
// exports both, so Swift sees the same `Shared` framework whichever exchange the scheme builds.
// Xcode runs :ios-${FLARE_VENUE}:embedAndSignAppleFrameworkForXcode; see iosApp/Configuration.
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
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
        export(project(":shared"))
        export(project(":venue-decibel"))
      }
    }

  sourceSets {
    commonMain.dependencies {
      api(project(":shared"))
      api(project(":venue-decibel"))
      implementation(libs.compose.runtime)
      implementation(libs.compose.ui)
    }
  }
}
