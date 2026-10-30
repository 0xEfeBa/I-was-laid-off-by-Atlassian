plugins {
    id("tri-edge.java-library")
}

dependencies {
    api("tools.jackson.core:jackson-databind")
    api("org.jspecify:jspecify")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    implementation(libs.json.schema.validator)

    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.assertj:assertj-core")
}

val schema = rootProject.file("platform/schemas/tenant-spec.v1.json")
val examples = rootProject.file("platform/schemas/examples")

tasks.processResources {
    from(schema) {
        into("dev/triedge/tenant")
    }
}

tasks.test {
    systemProperty("triedge.examples", examples.absolutePath)
}
