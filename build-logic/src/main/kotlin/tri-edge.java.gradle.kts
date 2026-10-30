import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    jacoco
}

val libs = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror", "-parameters"))
}

val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    mockitoAgent(platform(SpringBootPlugin.BOM_COORDINATES))
    mockitoAgent("org.mockito:mockito-core") {
        isTransitive = false
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
    systemProperty("triedge.migrations", rootProject.file("db/migrations").absolutePath)
    val agent: FileCollection = mockitoAgent
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${agent.singleFile.absolutePath}", "-Xshare:off")
    })
    finalizedBy(tasks.jacocoTestReport)
}

val coveredClasses = sourceSets.main.map { main ->
    main.output.classesDirs.asFileTree.matching {
        exclude("**/*Application.class")
    }
}

tasks.jacocoTestReport {
    classDirectories.setFrom(coveredClasses)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    classDirectories.setFrom(coveredClasses)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
