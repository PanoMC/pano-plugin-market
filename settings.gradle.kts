pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
    }
}

plugins {
    // Downloads the JDK 25 toolchain the Fabric build needs (19 section 2.1); the other toolchains are found locally or fetched too.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "pano-plugin-market"

// The Fabric jar (19 section 2.1, 2.3) is a sibling Gradle subproject that only exists for a standalone build with
// -Pfabric. The platform build never reads this file (it includes the market as one project), so the embedded build
// is unaffected: it has no :mc-fabric and no Loom.
if (providers.gradleProperty("fabric").isPresent) {
    include(":mc-fabric")
}
