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
        // Pine's Xposed-compatibility API artifact is published here, not on Maven Central.
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "DualApp"
include(":app")
