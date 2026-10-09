plugins {
    id("tri-edge.java")
    application
    id("org.graalvm.buildtools.native")
}

graalvmNative {
    binaries.named("main") {
        imageName = project.name
    }
}
