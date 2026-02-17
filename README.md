# qupath-extension-project-metadata-editor

A QuPath extension for viewing and editing metadata across all images in a project, extracted from QuPath core to allow independent development and release.

The metadata editor was previously bundled inside QuPath itself. Moving it to a standalone extension means it can be improved, versioned, and released independently — without waiting for a full QuPath release cycle.

Once installed, the editor is available under **Extensions > Project Metadata Editor > Edit project metadata...**. It opens a table showing all images in the current project alongside their metadata fields, which can be edited, copied, pasted, and cleared directly in the table.

## Build the extension

You don't need to install Gradle separately — the [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html) handles that.

Open a command prompt, navigate to the project root, and run:

```bash
gradlew build
```

The built extension jar will be in `build/libs`. Drag it onto QuPath to install it — you'll be prompted to create a user directory if you don't already have one.

If the extension has external dependencies beyond what QuPath includes, you can bundle them into a single jar with:

```bash
gradlew shadowJar
```

Otherwise you'd need to drag each extra dependency onto QuPath separately.

## Configure the extension

The QuPath version compatibility is set in `settings.gradle.kts`:

```kotlin
qupath {
    version = "0.6.0"
}
```

Extension metadata (name, version, description) is in `build.gradle.kts`:

```kotlin
qupathExtension {
    name = "qupath-extension-project-metadata-editor"
    group = "io.github.qupath"
    version = "0.1.0-SNAPSHOT"
    description = "Edit metadata for all images in a QuPath project"
    automaticModule = "io.github.qupath.extension.project-metadata-editor"
}
```

## Run QuPath + the extension during development

### 1. Make sure you have Java installed

QuPath uses Java 21. Download it from https://adoptium.net/

### 2. Get QuPath's source code

Instructions at https://qupath.readthedocs.io/en/stable/docs/reference/building.html

### 3. Create an `include-extra` file

In the root of the QuPath source (not this extension), create a file called `include-extra` with:

```
[includeBuild]
../qupath-extension-project-metadata-editor

[dependencies]
io.github.qupath:qupath-extension-project-metadata-editor
```

### 4. Run QuPath

```bash
gradlew run
```

QuPath will launch with the extension installed. Check **Extensions** in the menu bar to confirm.

## IDE setup

QuPath is developed in IntelliJ. You can import this extension the same way, and create a [Run configuration](https://www.jetbrains.com/help/idea/run-debug-configuration.html) pointing to `gradlew run`.

## Releases

When ready to publish a new version, use the `github_release.yml` workflow included in `.github/workflows/`:

`Actions → Make draft release → Run workflow`

This builds the extension and creates a draft release with the jar, sources, and javadoc attached. Once published, users can install it automatically via QuPath's extension manager.

See https://qupath.readthedocs.io/en/0.5/docs/intro/extensions.html#installing-extensions for details.

## Getting help

For questions about QuPath and creating extensions, use the forum at https://forum.image.sc/tag/qupath

## License

This extension is derived from [QuPath](https://github.com/qupath/qupath), which is available under the GPL v3. This extension is therefore also licensed under the [GPL v3](https://www.gnu.org/licenses/gpl-3.0.html).
