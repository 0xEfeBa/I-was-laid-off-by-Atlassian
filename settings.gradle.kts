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

    "services:control-plane",
    "services:broker",
    "services:edge-sidecar",
    "services:identity",
    "services:admin-api",
    "services:git-service",
    "services:repo-api",
    "services:ci-runner",
    "services:echo",

    "tools:edgectl",
    "tools:ci-agent",
)
