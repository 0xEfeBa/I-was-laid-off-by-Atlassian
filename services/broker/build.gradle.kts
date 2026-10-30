plugins {
    id("tri-edge.spring-service")
}

dependencies {
    implementation(project(":libs:tenant-spec"))

    testImplementation(testFixtures(project(":libs:common")))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
