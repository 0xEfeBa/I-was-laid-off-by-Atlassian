plugins {
    id("tri-edge.spring-service")
}

dependencies {
    implementation(project(":libs:tenant-spec"))
    implementation(libs.envoy.api)
    implementation(libs.envoy.control.plane.server)
    implementation(libs.envoy.control.plane.cache)
    implementation("com.google.protobuf:protobuf-java-util")
    implementation("io.grpc:grpc-netty-shaded")

    testImplementation(testFixtures(project(":libs:common")))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

val systemTenants = rootProject.file("deploy/system-tenants")

tasks.test {
    systemProperty("triedge.system-tenants", systemTenants.absolutePath)
    systemProperty("jdk.httpclient.allowRestrictedHeaders", "host")
}
