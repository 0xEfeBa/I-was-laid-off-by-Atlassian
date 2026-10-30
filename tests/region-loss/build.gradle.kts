plugins {
    id("tri-edge.java")
}

dependencies {
    testImplementation(testFixtures(project(":libs:common")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.postgresql:postgresql")
}

val remote = listOf("eu", "us", "ap", "password", "kill").associateWith {
    providers.gradleProperty("triedge.remote.$it")
}

tasks.test {
    remote.forEach { (name, value) ->
        if (value.isPresent) {
            systemProperty("triedge.remote.$name", value.get())
        }
    }
    testLogging {
        showStandardStreams = remote.getValue("eu").isPresent
    }
}
