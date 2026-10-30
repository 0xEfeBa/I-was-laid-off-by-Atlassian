plugins {
    id("tri-edge.spring-service")
}

dependencies {
    implementation(libs.nimbus.jose.jwt)
    implementation("org.springframework.security:spring-security-crypto")
    runtimeOnly(libs.bouncycastle.bcprov)

    testImplementation(testFixtures(project(":libs:common")))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
