package qupath.ext.projectmetadataeditor;

import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * QuPath extension that adds a Project Metadata Editor command to the Extensions menu.
 * <p>
 * This exposes the metadata editing functionality previously bundled inside QuPath core,
 * allowing it to be developed and released independently.
 */
public class ProjectMetadataEditorExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(ProjectMetadataEditorExtension.class);

    private static final String EXTENSION_NAME = "Project Metadata Editor";
    private static final String EXTENSION_DESCRIPTION = "Edit metadata for all images in a QuPath project";
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.6.0");

    private boolean isInstalled = false;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (isInstalled) {
            logger.debug("{} is already installed", getName());
            return;
        }
        isInstalled = true;
        addMenuItem(qupath);
    }

    private void addMenuItem(QuPathGUI qupath) {
        var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
        MenuItem menuItem = new MenuItem("Edit project metadata...");
        menuItem.setOnAction(e ->
                ProjectMetadataEditorCommand.showProjectMetadataEditor(qupath.getProject())
        );
        // Disable the menu item when no project is open
        menuItem.disableProperty().bind(qupath.projectProperty().isNull());
        menu.getItems().add(menuItem);
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDescription() {
        return EXTENSION_DESCRIPTION;
    }

    @Override
    public Version getQuPathVersion() {
        return EXTENSION_QUPATH_VERSION;
    }
}
