plugins {
    id("tri-edge.spring-service")
}

dependencies {
    implementation(libs.envoy.api)
    implementation(libs.nimbus.jose.jwt)
    implementation("io.grpc:grpc-netty-shaded")
    implementation("io.grpc:grpc-stub")
    implementation("io.grpc:grpc-protobuf")

    testImplementation(testFixtures(project(":libs:common")))
    testImplementation("io.grpc:grpc-inprocess")
}
