import org.springframework.boot.gradle.plugin.SpringBootPlugin
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("tri-edge.java")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(project(":libs:common"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<BootJar>("bootJar") {
    archiveFileName = "app.jar"
}

tasks.named<Jar>("jar") {
    enabled = false
}
