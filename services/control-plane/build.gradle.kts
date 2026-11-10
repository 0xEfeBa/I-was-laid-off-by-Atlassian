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
    implementation("io.grpc:grpc-services")

    testImplementation(testFixtures(project(":libs:common")))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

val systemTenants = rootProject.file("deploy/system-tenants")

tasks.test {
    systemProperty("triedge.system-tenants", systemTenants.absolutePath)
    systemProperty("triedge.envoy-bootstrap", rootProject.file("envoy/bootstrap.yaml").absolutePath)
    systemProperty("triedge.tenant-egress", rootProject.file("deploy/hetzner/tenant-egress.sh").absolutePath)
    systemProperty("jdk.httpclient.allowRestrictedHeaders", "host")
}
