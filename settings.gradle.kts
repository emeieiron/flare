rootProject.name = "flare"

val useMavenLocal =
  providers.environmentVariable("FLARE_MAVEN_LOCAL").orNull.toBoolean() ||
    providers.gradleProperty("useMavenLocal").orNull.toBoolean()

pluginManagement {
  repositories {
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    // Only when asked (-PuseMavenLocal=true or FLARE_MAVEN_LOCAL=true), so a locally installed build
    // can never stand in for a published one.
    if (useMavenLocal) mavenLocal()
    mavenCentral()
  }
}

include(":androidApp")

include(":baselineprofile")

include(":decibel")

include(":shared")
