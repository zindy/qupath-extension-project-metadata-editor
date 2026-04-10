plugins {
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

// Get version from Environment Variable (GitHub Actions) or fallback to VERSION file
// 1. Get the tag name from GitHub (e.g., "v1.0.3" or "v1.0.3-rc1")
val githubTag = System.getenv("GITHUB_REF_NAME")

// 2. Determine the final version string
val releaseVersion = if (githubTag != null && githubTag.startsWith("v")) {
    githubTag.removePrefix("v") // Use the tag (stripped of 'v')
} else {
    file("VERSION").readText().trim() // Fallback to your SNAPSHOT file
}

// TODO: Configure your extension here (please change the defaults!)
qupathExtension {
    name = "qupath-extension-project-metadata-editor"
    group = "io.github.qupath"
    version = releaseVersion
    description = "Edit metadata for all images in a QuPath project"
    automaticModule = "io.github.qupath.extension.project-metadata-editor"
}

// TODO: Define your dependencies here
dependencies {

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    // Add RichTextFX for CodeArea
    implementation("org.fxmisc.richtext:richtextfx:0.11.2")


    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)

}
