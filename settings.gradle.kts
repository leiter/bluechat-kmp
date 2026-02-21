rootProject.name = "Bluechat-KMP"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

includeBuild("../kable")

include(":composeApp")
include(":shared")
