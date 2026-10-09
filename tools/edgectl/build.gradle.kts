plugins {
    id("tri-edge.native-tool")
}

dependencies {
    implementation(libs.picocli)
    annotationProcessor(libs.picocli.codegen)
}

application {
    mainClass = "dev.triedge.edgectl.Main"
}
