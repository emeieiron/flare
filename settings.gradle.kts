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
    mavenLocal()
    mavenCentral()
  }
}

include(":androidApp")

include(":decibel")

include(":shared")
