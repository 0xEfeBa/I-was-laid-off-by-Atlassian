import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("tri-edge.java")
    `java-library`
}

dependencies {
    api(platform(SpringBootPlugin.BOM_COORDINATES))
}
