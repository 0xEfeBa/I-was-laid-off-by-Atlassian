plugins {
    id("tri-edge.java-library")
    `java-test-fixtures`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-opentelemetry")
    api("org.springframework:spring-jdbc")
    api("com.zaxxer:HikariCP")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")

    testFixturesApi(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    testFixturesApi("org.testcontainers:testcontainers-postgresql")
    testFixturesApi("org.flywaydb:flyway-database-postgresql")
    testFixturesApi("com.zaxxer:HikariCP")
    testFixturesApi("org.springframework:spring-web")
}
