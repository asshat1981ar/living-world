pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "living-world"

include(":game-core")
include(":game-ai")
include(":game-persistence")
include(":game-ui")
include(":androidApp")
include(":desktopApp")
