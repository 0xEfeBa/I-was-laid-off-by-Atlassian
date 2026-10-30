pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "tri-edge"

include(
    "libs:common",
    "libs:tenant-spec",

    "services:control-plane",
    "services:broker",
    "services:edge-sidecar",
    "services:identity",
    "services:echo",

    "tests:region-loss",
)
