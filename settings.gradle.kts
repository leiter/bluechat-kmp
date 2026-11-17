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

// kable is consumed from a local checkout: Phase A needs AndroidPeripheral.requestMtu
// and maximumWriteValueLengthForType, and this is the build the MTU work was verified
// against on device.
includeBuild("../kable")

include(":composeApp")
include(":shared")
