plugins {
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-project-metadata-editor"
    group = "io.github.qupath"
    version = file("VERSION").readText().trim()
    description = "Edit metadata for all images in a QuPath project"
    automaticModule = "io.github.qupath.extension.project-metadata-editor"
}

// TODO: Define your dependencies here
dependencies {

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)


    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)

}
