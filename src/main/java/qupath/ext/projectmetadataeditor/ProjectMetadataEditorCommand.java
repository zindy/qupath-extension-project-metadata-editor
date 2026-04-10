/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2020 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.projectmetadataeditor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.Comparator;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ObservableStringValue;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.panes.ProjectBrowser;
import qupath.lib.gui.panes.ProjectEntryPredicate;
import qupath.lib.gui.prefs.SystemMenuBar;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Command to enable editing of project metadata.
 * <p>
 * Features:
 * <ul>
 *   <li>File menu: Import CSV/TSV, Export (TSV / comma CSV / semicolon CSV — format chosen in save dialog)</li>
 *   <li>Edit: Undo / Redo for all cell changes</li>
 *   <li>Edit: Add and Remove metadata columns</li>
 *   <li>Edit: Excel-compatible multi-cell Copy / Paste</li>
 * </ul>
 * <p>
 * Import behaviour:
 * <ul>
 *   <li>Rows matched by "Image name"; unmatched rows are skipped.</li>
 *   <li>New columns in the CSV are always added to the table.</li>
 *   <li>When an existing column already has values the user is asked once:
 *       Overwrite / Skip existing / Cancel.</li>
 *   <li>Results are reported via a non-blocking notification and a QuPath log entry.</li>
 * </ul>
 *
 * @author Pete Bankhead (original), extended with additional features
 */
public class ProjectMetadataEditorCommand {

    private static final Logger logger = LoggerFactory.getLogger(ProjectMetadataEditorCommand.class);

    /** Column header for the row-index column. */
    private static final String INDEX      = "#";
    /** Column header for the image-name column. */
    private static final String IMAGE_NAME = "Image name";

    // =========================================================================
    // ENTRY POINT
    // =========================================================================

    public static void showProjectMetadataEditor(Project<?> project) {
        if (project == null) {
            logger.warn("No project available!");
            return;
        }

        Set<String> metadataNameSet = new TreeSet<>();
        List<ImageEntryWrapper> entries = new ArrayList<>();
        int idx = 1;
        for (ProjectImageEntry<?> entry : project.getImageList()) {
            entries.add(new ImageEntryWrapper(entry, idx++));
            metadataNameSet.addAll(entry.getMetadataKeys());
        }

        TableView<ImageEntryWrapper> table = new TableView<>();
        table.setEditable(true);
        table.getSelectionModel().setCellSelectionEnabled(true);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // Fixed columns
        TableColumn<ImageEntryWrapper, Number> colIndex = new TableColumn<>(INDEX);
        colIndex.setCellValueFactory(v -> v.getValue().indexProperty);
        colIndex.setPrefWidth(40);
        colIndex.setEditable(false);
        colIndex.setSortable(true);
        table.getColumns().add(colIndex);

        TableColumn<ImageEntryWrapper, String> colName = new TableColumn<>(IMAGE_NAME);
        colName.setCellValueFactory(v -> v.getValue().getNameBinding());
        colName.setEditable(false);
        table.getColumns().add(colName);

        // Clicking # resets rows to natural (project) order and clears any sort arrow.
        // All other column sorts use JavaFX's built-in combined comparator unchanged.
        table.setSortPolicy(t -> {
            var order = t.getSortOrder();
            if (order.size() == 1 && order.get(0) == colIndex) {
                t.getItems().sort(Comparator.comparingInt(w -> w.indexProperty.get()));
                Platform.runLater(order::clear); // clears the sort arrow after rendering
            } else if (t.getComparator() != null) {
                // Guard against null comparator: the runLater above fires a second
                // sort event with an empty sort order, which would make getComparator()
                // return null and cause FXCollections.sort() to attempt natural ordering
                // (which crashes because ImageEntryWrapper does not implement Comparable).
                FXCollections.sort(t.getItems(), t.getComparator());
            }
            return true;
        });

        // Undo/Redo menu items — created early so EditorContext can hold them
        MenuItem miUndo = new MenuItem("Undo");
        MenuItem miRedo = new MenuItem("Redo");
        EditorContext context = new EditorContext(table, miUndo, miRedo);

        // Metadata columns from existing project data
        for (String metadataName : metadataNameSet) {
            addTableColumn(table, metadataName, context);
        }
        table.getItems().setAll(entries);

        // Keyboard shortcuts
        table.addEventHandler(KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() == KeyCode.BACK_SPACE || e.getCode() == KeyCode.DELETE) {
                handleDelete(table, context);
                e.consume();
            } else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN).match(e)) {
                context.undo();
                e.consume();
            } else if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN).match(e)
                    || new KeyCodeCombination(KeyCode.Y, KeyCombination.SHORTCUT_DOWN).match(e)) {
                context.redo();
                e.consume();
            }
        });

        BooleanBinding noSelection = Bindings.createBooleanBinding(
                () -> table.getSelectionModel().selectedItemProperty().get() == null,
                table.getSelectionModel().selectedItemProperty());

        // =====================================================================
        // MENU BAR
        // =====================================================================
        MenuBar menubar = new MenuBar();

        // ----- FILE menu ---------------------------------------------------
        Menu menuFile = new Menu("File");

        MenuItem miImport = new MenuItem("Import\u2026");
        miImport.setAccelerator(new KeyCodeCombination(KeyCode.I, KeyCombination.SHORTCUT_DOWN));
        miImport.setOnAction(e -> importCsv(table, entries, context));

        Menu menuExport = new Menu("Export");
        MenuItem miExportTsv      = new MenuItem("Tab separated (.tsv)");
        MenuItem miExportCsvComma = new MenuItem("Comma separated (.csv)");
        MenuItem miExportCsvSemi  = new MenuItem("Semicolon separated (.csv)");
        miExportTsv.setOnAction(e       -> exportCsv(table, '\t', "metadata.tsv"));
        miExportCsvComma.setOnAction(e  -> exportCsv(table, ',',  "metadata.csv"));
        miExportCsvSemi.setOnAction(e   -> exportCsv(table, ';',  "metadata.csv"));
        menuExport.getItems().addAll(miExportTsv, miExportCsvComma, miExportCsvSemi);

        menuFile.getItems().addAll(miImport, new SeparatorMenuItem(), menuExport);

        // ----- EDIT menu ---------------------------------------------------
        Menu menuEdit = new Menu("Edit");

        miUndo.setAccelerator(new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN));
        miUndo.setOnAction(e -> context.undo());

        miRedo.setAccelerator(new KeyCodeCombination(KeyCode.Y, KeyCombination.SHORTCUT_DOWN));
        miRedo.setOnAction(e -> context.redo());

        MenuItem miAddCol = new MenuItem("Add column\u2026");
        miAddCol.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN));
        miAddCol.setOnAction(e -> addColumn(table, context));

        MenuItem miRemoveCol = new MenuItem("Remove column\u2026");
        miRemoveCol.setOnAction(e -> removeColumn(table, entries, context));

        MenuItem miAddPathFileCols = new MenuItem("Add PathName \u0026 FileName columns");

        MenuItem miPattern = new MenuItem("Extract metadata from pattern...");
        miPattern.setOnAction(e -> showPatternDialog(table, context));
        menuEdit.getItems().add(miPattern);

        MenuItem miAssignSplit = new MenuItem("Assign train/validation/test split\u2026");
        miAssignSplit.setOnAction(e -> assignTrainValTestSplit(table, entries, context));
        miAddPathFileCols.setOnAction(e -> addPathNameFileNameColumns(table, entries, context));

        MenuItem miCopyCol = new MenuItem("Copy column\u2026");
        miCopyCol.setOnAction(e -> copyColumn(table, entries, context));

        MenuItem miRenameCol = new MenuItem("Rename column\u2026");
        miRenameCol.setOnAction(e -> renameColumn(table, entries, context));

        MenuItem miCopy = new MenuItem("Copy");
        miCopy.disableProperty().bind(noSelection);
        miCopy.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN));
        miCopy.setOnAction(e -> copySelectedCellsToClipboard(table, true));

        MenuItem miCopyFull = new MenuItem("Copy full table");
        miCopyFull.setOnAction(e -> copyEntireTableToClipboard(table));

        MenuItem miPaste = new MenuItem("Paste");
        miPaste.setAccelerator(new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN));
        miPaste.setOnAction(e -> pasteClipboardContentsToTable(table, context));

        MenuItem miSetCells = new MenuItem("Set cell contents\u2026");
        miSetCells.disableProperty().bind(noSelection);
        miSetCells.setOnAction(e -> {
            String input = Dialogs.showInputDialog("Set metadata cells", "Value to set in selected cells:", "");
            if (input == null) return;
            applyBatchChange(table.getSelectionModel().getSelectedCells(), input, context);
        });

        MenuItem miSearchReplace = new MenuItem("Search & Replace\u2026");
        miSearchReplace.setAccelerator(new KeyCodeCombination(KeyCode.H, KeyCombination.SHORTCUT_DOWN));
        miSearchReplace.setOnAction(e -> showSearchReplaceDialog(table, context));

        menuEdit.getItems().addAll(
                miUndo, miRedo,
                new SeparatorMenuItem(),
                miAddCol, miRemoveCol, miCopyCol, miRenameCol, miAddPathFileCols, miAssignSplit,
                new SeparatorMenuItem(),
                miCopy, miCopyFull, miPaste,
                new SeparatorMenuItem(),
                miSetCells,
                miSearchReplace
        );

        menubar.getMenus().addAll(menuFile, menuEdit);
        SystemMenuBar.manageChildMenuBar(menubar);

        // =====================================================================
        // FILTER BAR (above the table)
        // =====================================================================
        //
        // Filtering is display-only: `entries` (the full list) always remains the
        // source of truth for Save, Undo/Redo, Remove Column, etc.
        // ProjectEntryPredicate.createIgnoreCase() searches image name AND all
        // metadata values so the user can filter on either.
        Label lblFilter = new Label("Filter:");
        TextField tfFilter = new TextField();
        tfFilter.setPromptText("Image name or metadata\u2026");
        tfFilter.setTooltip(new Tooltip(
                "Filter rows by image name or any metadata value (case-insensitive).\n"
                + "Hidden rows are not deleted \u2014 clear the filter to show all images."));
        HBox.setHgrow(tfFilter, Priority.ALWAYS);

        Button btnClearFilter = new Button("\u2715");
        btnClearFilter.setTooltip(new Tooltip("Clear filter"));
        btnClearFilter.visibleProperty().bind(tfFilter.textProperty().isNotEmpty());
        btnClearFilter.managedProperty().bind(tfFilter.textProperty().isNotEmpty());
        btnClearFilter.setOnAction(e -> tfFilter.clear());

        // Status label: "Showing X of Y" only while a filter is active
        Label lblFilterStatus = new Label();
        lblFilterStatus.setStyle("-fx-text-fill: -fx-accent; -fx-font-size: 0.9em;");

        HBox filterBar = new HBox(6, lblFilter, tfFilter, btnClearFilter, lblFilterStatus);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setPadding(new Insets(2, 4, 2, 4));

        // Rebuild table.getItems() on every keystroke
        tfFilter.textProperty().addListener((obs, oldText, newText) ->
                applyEntryFilter(newText, entries, table, lblFilterStatus));

        // =====================================================================
        // LAYOUT & DIALOG
        // =====================================================================
        // Import and Export are accessed from the File menu.
        // The filter bar sits directly below the table.
        BorderPane pane = new BorderPane();
        pane.setTop(menubar);
        pane.setCenter(table);
        pane.setBottom(filterBar);

        Dialog<ButtonType> dialog = new Dialog<>();
        var qupath = QuPathGUI.getInstance();
        if (qupath != null)
            dialog.initOwner(qupath.getStage());
        dialog.setTitle("Project metadata");
        dialog.setHeaderText(null);
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(pane);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(640);
        dialog.getDialogPane().setPrefHeight(480);

        // Confirm before saving / discarding
        Button btnOk = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        btnOk.addEventFilter(ActionEvent.ACTION, e -> {
            if (entries.stream().anyMatch(ImageEntryWrapper::hasChanges)) {
                if (!Dialogs.showConfirmDialog("Save changes", "Save changes to project metadata?"))
                    e.consume();
            }
        });

        Button btnCancel = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        btnCancel.addEventFilter(ActionEvent.ACTION, e -> {
            if (entries.stream().anyMatch(ImageEntryWrapper::hasChanges)) {
                if (!Dialogs.showConfirmDialog("Discard changes", "Discard unsaved changes?"))
                    e.consume();
            }
        });

        dialog.setOnCloseRequest(e -> {
            if (dialog.getResult() == ButtonType.OK) return;
            if (entries.stream().anyMatch(ImageEntryWrapper::hasChanges)) {
                if (!Dialogs.showConfirmDialog("Discard changes", "Discard unsaved changes?"))
                    e.consume();
            }
        });

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get().getButtonData() == ButtonData.OK_DONE) {
            for (ImageEntryWrapper wrapper : entries)
                wrapper.commitChanges();
            ProjectBrowser.syncProject(project);
        }
    }

    // =========================================================================
    // IMPORT CSV
    // =========================================================================

    /**
     * Import policy for columns that already contain values.
     */
    private enum OverwritePolicy { OVERWRITE, SKIP, CANCEL }

    /**
     * Imports a CSV or TSV file into the table.
     * <p>
     * Rows are matched to project entries by the "Image name" column.
     * New columns are always added.  When an already-populated column
     * would be affected the user is asked once: Overwrite / Skip / Cancel.
     * A summary is written to the QuPath log and shown as a notification.
     */
    private static void importCsv(TableView<ImageEntryWrapper> table,
                                   List<ImageEntryWrapper> entries,
                                   EditorContext context) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import metadata file");
        chooser.getExtensionFilters().addAll(
                new ExtensionFilter("Tabular files", "*.tsv", "*.csv", "*.txt"),
                new ExtensionFilter("All files", "*.*"));
        var qupath = QuPathGUI.getInstance();
        File file = chooser.showOpenDialog(qupath == null ? null : qupath.getStage());
        if (file == null) return;

        // ---- Read and parse ------------------------------------------------
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null)
                if (!line.isBlank()) lines.add(line);
        } catch (IOException ex) {
            logger.error("Error reading import file: {}", file, ex);
            Dialogs.showErrorMessage("Import", "Could not read file:\n" + ex.getMessage());
            return;
        }

        if (lines.isEmpty()) {
            Dialogs.showWarningNotification("Import", "File is empty.");
            return;
        }

        char sep = detectSeparator(lines.get(0));
        String[] headers = splitCsvLine(lines.get(0), sep);

        // Find the image-name column
        int nameColIndex = -1;
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim();
            if (IMAGE_NAME.equalsIgnoreCase(h) || "name".equalsIgnoreCase(h)) {
                nameColIndex = i;
                break;
            }
        }
        if (nameColIndex == -1) {
            Dialogs.showErrorMessage("Import",
                    "Could not find an '" + IMAGE_NAME + "' column.\n" +
                    "The file must have a column named '" + IMAGE_NAME + "' or 'name'.");
            return;
        }

        // Build name → wrapper lookup
        Map<String, ImageEntryWrapper> byName = new LinkedHashMap<>();
        for (ImageEntryWrapper w : entries)
            byName.put(w.entry.getImageName(), w);

        // Collect metadata columns from the file (skip # and Image name)
        List<Integer> fileColIndices = new ArrayList<>();
        List<String>  fileColNames   = new ArrayList<>();
        for (int i = 0; i < headers.length; i++) {
            if (i == nameColIndex) continue;
            String h = headers[i].trim();
            if (h.equals(INDEX)) continue;
            fileColIndices.add(i);
            fileColNames.add(h);
        }

        // ---- Determine overwrite policy ------------------------------------
        // Check whether any existing table column with values would be touched
        boolean existingPopulatedColumnsAffected = false;
        outer:
        for (String colName : fileColNames) {
            if (!tableHasColumn(table, colName)) continue;   // new column — always fine
            for (ImageEntryWrapper w : entries) {
                String v = w.getMetadataValue(colName);
                if (v != null && !v.isEmpty()) {
                    existingPopulatedColumnsAffected = true;
                    break outer;
                }
            }
        }

        OverwritePolicy policy = OverwritePolicy.OVERWRITE;
        if (existingPopulatedColumnsAffected) {
            ButtonType btnOverwrite = new ButtonType("Overwrite");
            ButtonType btnSkip      = new ButtonType("Skip existing");
            ButtonType btnCancel    = ButtonType.CANCEL;

            Dialog<ButtonType> d = new Dialog<>();
            if (QuPathGUI.getInstance() != null)
                d.initOwner(QuPathGUI.getInstance().getStage());
            d.setTitle("Import — existing values");
            d.setHeaderText("Some columns already contain values.");
            d.setContentText(
                    "What should happen to cells that already have a value?\n\n" +
                    "  Overwrite   — replace existing values with the imported ones\n" +
                    "  Skip existing — leave cells that already have a value unchanged\n" +
                    "  Cancel   — abort the import");
            d.getDialogPane().getButtonTypes().setAll(btnOverwrite, btnSkip, btnCancel);

            Optional<ButtonType> choice = d.showAndWait();
            if (choice.isEmpty() || choice.get() == btnCancel)
                return;
            policy = (choice.get() == btnSkip) ? OverwritePolicy.SKIP : OverwritePolicy.OVERWRITE;
        }

        // ---- Ensure new columns exist in the table -------------------------
        int newColumnsAdded = 0;
        for (String colName : fileColNames) {
            if (!tableHasColumn(table, colName)) {
                addTableColumn(table, colName, context);
                newColumnsAdded++;
            }
        }

        // ---- Build the batch edit -----------------------------------------
        MetadataEdit batchEdit = new MetadataEdit();
        int rowsMatched   = 0;
        int rowsUnmatched = 0;
        int valuesUpdated = 0;
        int valuesSkipped = 0;

        for (int row = 1; row < lines.size(); row++) {
            String[] cells = splitCsvLine(lines.get(row), sep);
            if (nameColIndex >= cells.length) continue;
            String imageName = cells[nameColIndex].trim();
            ImageEntryWrapper wrapper = byName.get(imageName);
            if (wrapper == null) {
                rowsUnmatched++;
                continue;
            }
            rowsMatched++;

            for (int ci = 0; ci < fileColIndices.size(); ci++) {
                int   fi     = fileColIndices.get(ci);
                String key   = fileColNames.get(ci);
                String newVal = (fi < cells.length) ? cells[fi].trim() : "";
                String oldVal = wrapper.getMetadataValue(key);
                String oldValOrEmpty = (oldVal == null) ? "" : oldVal;

                if (newVal.equals(oldValOrEmpty)) continue;  // no actual change

                boolean hasExistingValue = (oldVal != null && !oldVal.isEmpty());
                if (hasExistingValue && policy == OverwritePolicy.SKIP) {
                    valuesSkipped++;
                    continue;
                }
                batchEdit.addChange(wrapper, key, oldVal, newVal);
                valuesUpdated++;
            }
        }

        // ---- Apply and report ---------------------------------------------
        if (!batchEdit.isEmpty()) {
            context.execute(batchEdit);
        }

        // Summary for log and notification
        String summary = buildImportSummary(
                file.getName(), rowsMatched, rowsUnmatched,
                newColumnsAdded, valuesUpdated, valuesSkipped, policy);

        logger.info(summary);
        Dialogs.showInfoNotification("Import complete", summary);
    }

    private static String buildImportSummary(String fileName,
                                              int rowsMatched, int rowsUnmatched,
                                              int newColumnsAdded, int valuesUpdated,
                                              int valuesSkipped, OverwritePolicy policy) {
        StringBuilder sb = new StringBuilder();
        sb.append("Import from ").append(fileName).append('\n');
        sb.append("  Rows matched:       ").append(rowsMatched);
        if (rowsUnmatched > 0)
            sb.append("  (").append(rowsUnmatched).append(" image name(s) not found in project)");
        sb.append('\n');
        sb.append("  New columns added:  ").append(newColumnsAdded).append('\n');
        sb.append("  Values updated:     ").append(valuesUpdated).append('\n');
        if (valuesSkipped > 0)
            sb.append("  Values skipped:     ").append(valuesSkipped)
              .append("  (existing values preserved — \"Skip existing\" mode)\n");
        return sb.toString().trim();
    }

    // =========================================================================
    // EXPORT CSV
    // =========================================================================

    /**
     * Exports with a specific separator and a suggested filename.
     * Called from the File menu sub-items.
     */
    private static void exportCsv(TableView<ImageEntryWrapper> table, char sep, String defaultName) {
        File file = showSaveDialog(sep, defaultName);
        if (file != null) writeCsv(table, file, sep);
    }

    /**
     * Exports with a single FileChooser that offers all three format choices
     * via extension filters.  Called from the File > Export menu.
     */
    private static void exportCsvInteractive(TableView<ImageEntryWrapper> table) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export metadata");
        ExtensionFilter tsv   = new ExtensionFilter("Tab separated (.tsv)",           "*.tsv");
        ExtensionFilter csvC  = new ExtensionFilter("Comma separated (.csv)",         "*.csv");
        ExtensionFilter csvS  = new ExtensionFilter("Semicolon separated (.csv — EU)","*.csv");
        chooser.getExtensionFilters().addAll(tsv, csvC, csvS);
        chooser.setSelectedExtensionFilter(tsv);
        chooser.setInitialFileName("metadata.tsv");

        var qupath = QuPathGUI.getInstance();
        File file = chooser.showSaveDialog(qupath == null ? null : qupath.getStage());
        if (file == null) return;

        ExtensionFilter chosen = chooser.getSelectedExtensionFilter();
        char sep = (chosen == csvS) ? ';' : (chosen == csvC) ? ',' : '\t';
        writeCsv(table, file, sep);
    }

    private static File showSaveDialog(char sep, String defaultName) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export metadata");
        chooser.setInitialFileName(defaultName);
        if (sep == '\t')
            chooser.getExtensionFilters().add(new ExtensionFilter("Tab separated", "*.tsv"));
        else
            chooser.getExtensionFilters().add(new ExtensionFilter("CSV", "*.csv"));
        chooser.getExtensionFilters().add(new ExtensionFilter("All files", "*.*"));
        var qupath = QuPathGUI.getInstance();
        return chooser.showSaveDialog(qupath == null ? null : qupath.getStage());
    }

    private static void writeCsv(TableView<ImageEntryWrapper> table, File file, char sep) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            List<TableColumn<ImageEntryWrapper, ?>> cols = table.getColumns();

            // Header row
            StringBuilder header = new StringBuilder();
            for (int ci = 0; ci < cols.size(); ci++) {
                if (ci > 0) header.append(sep);
                header.append(escapeCsvField(cols.get(ci).getText(), sep));
            }
            pw.println(header);

            // Data rows
            List<ImageEntryWrapper> items = table.getItems();
            for (int ri = 0; ri < items.size(); ri++) {
                StringBuilder row = new StringBuilder();
                for (int ci = 0; ci < cols.size(); ci++) {
                    if (ci > 0) row.append(sep);
                    Object data = cols.get(ci).getCellData(ri);
                    row.append(escapeCsvField(data == null ? "" : data.toString(), sep));
                }
                pw.println(row);
            }
            logger.info("Exported metadata to {}", file.getAbsolutePath());
        } catch (IOException ex) {
            logger.error("Error exporting CSV to {}", file, ex);
            Dialogs.showErrorMessage("Export", "Could not write file:\n" + ex.getMessage());
        }
    }

    private static String escapeCsvField(String value, char sep) {
        if (value == null) return "";
        if (value.indexOf(sep) >= 0 || value.contains("\"") || value.contains("\n"))
            return "\"" + value.replace("\"", "\"\"") + "\"";
        return value;
    }

    // =========================================================================
    // SEPARATOR DETECTION & CSV PARSING
    // =========================================================================

    /** Detect separator by counting occurrences in the header line. */
    private static char detectSeparator(String headerLine) {
        long tabs       = headerLine.chars().filter(c -> c == '\t').count();
        long commas     = headerLine.chars().filter(c -> c == ',').count();
        long semicolons = headerLine.chars().filter(c -> c == ';').count();
        if (tabs >= commas && tabs >= semicolons) return '\t';
        if (semicolons > commas)                  return ';';
        return ',';
    }

    /** Simple CSV splitter that handles RFC-4180 double-quoted fields. */
    private static String[] splitCsvLine(String line, char sep) {
        List<String> result = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"'); i++;           // escaped quote inside quoted field
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == sep && !inQuotes) {
                result.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        result.add(sb.toString());
        return result.toArray(new String[0]);
    }

    // =========================================================================
    // COLUMN MANAGEMENT
    // =========================================================================

    private static void addColumn(TableView<ImageEntryWrapper> table, EditorContext context) {
        String newKey = Dialogs.showInputDialog("Add column", "New metadata column name:", "");
        if (newKey == null || newKey.isBlank()) return;
        newKey = newKey.trim();
        if (tableHasColumn(table, newKey)) {
            Dialogs.showErrorMessage("Add column", "A column named '" + newKey + "' already exists.");
            return;
        }
        addTableColumn(table, newKey, context);
    }

    @SuppressWarnings("unchecked")
    private static void removeColumn(TableView<ImageEntryWrapper> table,
                                     List<ImageEntryWrapper> entries,
                                     EditorContext context) {
        List<String> removable = new ArrayList<>();
        for (TableColumn<ImageEntryWrapper, ?> col : table.getColumns()) {
            String h = col.getText();
            if (!INDEX.equals(h) && !IMAGE_NAME.equals(h))
                removable.add(h);
        }
        if (removable.isEmpty()) {
            Dialogs.showInfoNotification("Remove column", "No metadata columns to remove.");
            return;
        }

        String key = Dialogs.showChoiceDialog(
                "Remove column", "Select column to remove:", removable, removable.get(0));
        if (key == null) return;

        if (!Dialogs.showConfirmDialog("Remove column",
                "Remove column '" + key + "' and clear its values from all entries?"))
            return;

        TableColumn<ImageEntryWrapper, String> col = (TableColumn<ImageEntryWrapper, String>)
                table.getColumns().stream().filter(c -> key.equals(c.getText())).findFirst().orElse(null);
        if (col == null) return;

        context.execute(new ColumnRemoveEdit(table, col, entries));
    }

    @SuppressWarnings("unchecked")
    private static void copyColumn(TableView<ImageEntryWrapper> table,
                                    List<ImageEntryWrapper> entries,
                                    EditorContext context) {
        List<String> copyable = new ArrayList<>();
        for (TableColumn<ImageEntryWrapper, ?> col : table.getColumns()) {
            String h = col.getText();
            if (!INDEX.equals(h) && !IMAGE_NAME.equals(h))
                copyable.add(h);
        }
        if (copyable.isEmpty()) {
            Dialogs.showInfoNotification("Copy column", "No metadata columns to copy.");
            return;
        }

        // ---- Single dialog: source dropdown + destination text field --------
        javafx.scene.control.ComboBox<String> cboSource = new javafx.scene.control.ComboBox<>();
        cboSource.getItems().setAll(copyable);
        // Pre-select whichever column the user has focused in the table
        String focusedCopy = focusedColumnName(table, copyable);
        cboSource.setValue(focusedCopy != null ? focusedCopy : copyable.get(0));

        TextField tfNewName = new TextField();
        tfNewName.setPromptText("New column name");

        // Auto-suggest "<source>_copy" whenever the source selection changes,
        // but only while the user hasn't typed anything of their own yet.
        cboSource.valueProperty().addListener((obs, oldSrc, newSrc) -> {
            String current = tfNewName.getText().trim();
            if (current.isEmpty() || (oldSrc != null && current.equals(oldSrc + "_copy")))
                tfNewName.setText(newSrc + "_copy");
        });
        tfNewName.setText(cboSource.getValue() + "_copy");

        // Error label shown inline when name is duplicate — dialog stays open
        Label lblError = new Label();
        lblError.setStyle("-fx-text-fill: -fx-error; -fx-font-size: 0.9em;");
        lblError.setVisible(false);
        // Clear error as soon as the user edits the name
        tfNewName.textProperty().addListener((obs, o, n) -> lblError.setVisible(false));

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12, 16, 4, 16));
        javafx.scene.layout.ColumnConstraints ccLabel = new javafx.scene.layout.ColumnConstraints();
        ccLabel.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.layout.ColumnConstraints ccField = new javafx.scene.layout.ColumnConstraints();
        ccField.setHgrow(Priority.ALWAYS);
        ccField.setMinWidth(200);
        grid.getColumnConstraints().addAll(ccLabel, ccField);

        grid.add(new Label("Column to copy:"), 0, 0);
        grid.add(cboSource,                    1, 0);
        grid.add(new Label("New column name:"), 0, 1);
        grid.add(tfNewName,                    1, 1);
        grid.add(lblError,                     1, 2);

        ButtonType btnCopy = new ButtonType("Copy", ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = new Dialog<>();
        var qupath = QuPathGUI.getInstance();
        if (qupath != null) dialog.initOwner(qupath.getStage());
        dialog.setTitle("Copy column");
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().setAll(btnCopy, ButtonType.CANCEL);

        // Keep Copy disabled while the name field is empty
        Button btnCopyNode = (Button) dialog.getDialogPane().lookupButton(btnCopy);
        btnCopyNode.disableProperty().bind(tfNewName.textProperty().isEmpty()
                .or(Bindings.createBooleanBinding(
                        () -> tfNewName.getText().isBlank(), tfNewName.textProperty())));

        // Intercept the OK button to validate before closing
        btnCopyNode.addEventFilter(ActionEvent.ACTION, e -> {
            String dstKey = tfNewName.getText().trim();
            if (tableHasColumn(table, dstKey)) {
                lblError.setText("“" + dstKey + "” already exists.");
                lblError.setVisible(true);
                e.consume(); // keep dialog open
            }
        });

        dialog.setOnShown(ev -> tfNewName.requestFocus());

        if (dialog.showAndWait().map(b -> b.getButtonData() != ButtonData.OK_DONE).orElse(true))
            return;

        String srcKey = cboSource.getValue();
        String dstKey = tfNewName.getText().trim();

        // Build the new column (not yet added to the table — ColumnCopyEdit.redo() does that)
        TableColumn<ImageEntryWrapper, String> newCol = new TableColumn<>(dstKey);
        newCol.setCellFactory(TextFieldTableCell.forTableColumn());
        newCol.setEditable(true);
        bindColumnToKey(newCol, dstKey, context);

        context.execute(new ColumnCopyEdit(table, newCol, entries, srcKey, dstKey));
    }

    @SuppressWarnings("unchecked")
    private static void renameColumn(TableView<ImageEntryWrapper> table,
                                      List<ImageEntryWrapper> entries,
                                      EditorContext context) {
        List<String> renameable = new ArrayList<>();
        for (TableColumn<ImageEntryWrapper, ?> col : table.getColumns()) {
            String h = col.getText();
            if (!INDEX.equals(h) && !IMAGE_NAME.equals(h))
                renameable.add(h);
        }
        if (renameable.isEmpty()) {
            Dialogs.showInfoNotification("Rename column", "No metadata columns to rename.");
            return;
        }

        // ---- Single dialog: column dropdown + new-name text field -----------
        javafx.scene.control.ComboBox<String> cboCol = new javafx.scene.control.ComboBox<>();
        cboCol.getItems().setAll(renameable);
        // Pre-select whichever column the user has focused in the table
        String focusedRename = focusedColumnName(table, renameable);
        cboCol.setValue(focusedRename != null ? focusedRename : renameable.get(0));

        TextField tfNewName = new TextField(cboCol.getValue());
        tfNewName.setPromptText("New column name");
        tfNewName.selectAll(); // convenient: user can just start typing the new name

        // Keep the text field in sync when the source selection changes,
        // but only if the user hasn't already edited it away from the old column name.
        cboCol.valueProperty().addListener((obs, oldCol, newCol) -> {
            if (tfNewName.getText().trim().equals(oldCol))
                tfNewName.setText(newCol);
        });

        // Inline error label — shown without closing the dialog
        Label lblError = new Label();
        lblError.setStyle("-fx-text-fill: -fx-error; -fx-font-size: 0.9em;");
        lblError.setVisible(false);
        lblError.managedProperty().bind(lblError.visibleProperty());
        tfNewName.textProperty().addListener((obs, o, n) -> lblError.setVisible(false));

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12, 16, 4, 16));
        javafx.scene.layout.ColumnConstraints ccLabel = new javafx.scene.layout.ColumnConstraints();
        ccLabel.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.layout.ColumnConstraints ccField = new javafx.scene.layout.ColumnConstraints();
        ccField.setHgrow(Priority.ALWAYS);
        ccField.setMinWidth(200);
        grid.getColumnConstraints().addAll(ccLabel, ccField);

        grid.add(new Label("Column to rename:"), 0, 0);
        grid.add(cboCol,                          1, 0);
        grid.add(new Label("New name:"),          0, 1);
        grid.add(tfNewName,                       1, 1);
        grid.add(lblError,                        1, 2);

        ButtonType btnRename = new ButtonType("Rename", ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = new Dialog<>();
        var qupath = QuPathGUI.getInstance();
        if (qupath != null) dialog.initOwner(qupath.getStage());
        dialog.setTitle("Rename column");
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().setAll(btnRename, ButtonType.CANCEL);

        // Keep Rename disabled while the name field is blank
        Button btnRenameNode = (Button) dialog.getDialogPane().lookupButton(btnRename);
        btnRenameNode.disableProperty().bind(
                Bindings.createBooleanBinding(
                        () -> tfNewName.getText().isBlank(), tfNewName.textProperty()));

        // Intercept to validate without closing
        btnRenameNode.addEventFilter(ActionEvent.ACTION, e -> {
            String oldKey = cboCol.getValue();
            String newKey = tfNewName.getText().trim();
            if (newKey.equals(oldKey)) {
                // Silently close — nothing to do is not an error
                return;
            }
            if (tableHasColumn(table, newKey)) {
                lblError.setText("“" + newKey + "” already exists.");
                lblError.setVisible(true);
                e.consume(); // keep dialog open
            }
        });

        dialog.setOnShown(ev -> { tfNewName.requestFocus(); tfNewName.selectAll(); });

        if (dialog.showAndWait().map(b -> b.getButtonData() != ButtonData.OK_DONE).orElse(true))
            return;

        String oldKey = cboCol.getValue();
        String newKey = tfNewName.getText().trim();
        if (newKey.equals(oldKey)) return;

        TableColumn<ImageEntryWrapper, String> col = (TableColumn<ImageEntryWrapper, String>)
                table.getColumns().stream().filter(c -> oldKey.equals(c.getText())).findFirst().orElse(null);
        if (col == null) return;

        context.execute(new ColumnRenameEdit(col, entries, oldKey, newKey, context));
    }

    /**
     * Returns the metadata column name that is currently focused/selected in the
     * table, or {@code null} if the focused column is not in {@code candidates}.
     * Used to pre-select the relevant column in Copy / Rename dialogs.
     */
    private static String focusedColumnName(TableView<ImageEntryWrapper> table,
                                             List<String> candidates) {
        var selected = table.getSelectionModel().getSelectedCells();
        if (selected.isEmpty()) return null;
        var col = selected.get(0).getTableColumn();
        if (col == null) return null;
        String name = col.getText();
        return candidates.contains(name) ? name : null;
    }

    private static boolean tableHasColumn(TableView<?> table, String name) {
        return table.getColumns().stream().anyMatch(col -> col.getText().equals(name));
    }

    // CellProfiler-compatible column names
    private static final String COL_PATHNAME = "PathName";
    private static final String COL_FILENAME  = "FileName";

    /**
     * Shows a dialog to assign "training", "validation", and "test" labels to
     * every image entry, written into a user-named metadata column.
     * <p>
     * The user specifies:
     * <ul>
     *   <li>Column name (default "Split")</li>
     *   <li>Random seed (default 42)</li>
     *   <li>Training % and Validation % — Test % is derived as 100 − train − val</li>
     * </ul>
     * Images are shuffled with the given seed, then assigned labels by cumulative
     * proportion so the result is fully reproducible.  The operation is a single
     * undoable batch.
     */
    private static void assignTrainValTestSplit(TableView<ImageEntryWrapper> table,
                                                 List<ImageEntryWrapper> entries,
                                                 EditorContext context) {
        var visibleEntries = table.getItems();
        if (visibleEntries.isEmpty()) {
            Dialogs.showInfoNotification("Train/val/test split", "No images in project.");
            return;
        }

        // ---- Dialog layout --------------------------------------------------
        TextField tfColName = new TextField("Split");
        tfColName.setPromptText("Column name");

        javafx.scene.control.Spinner<Integer> spnSeed =
                new javafx.scene.control.Spinner<>(Integer.MIN_VALUE, Integer.MAX_VALUE, 42);
        spnSeed.setEditable(true);
        spnSeed.setPrefWidth(100);

        javafx.scene.control.Spinner<Integer> spnTrain =
                new javafx.scene.control.Spinner<>(0, 100, 80);
        spnTrain.setEditable(true);
        spnTrain.setPrefWidth(80);

        javafx.scene.control.Spinner<Integer> spnVal =
                new javafx.scene.control.Spinner<>(0, 100, 10);
        spnVal.setEditable(true);
        spnVal.setPrefWidth(80);

        // Test % = 100 - train - val, shown read-only
        Label lblTest = new Label("10 %");
        lblTest.setPrefWidth(80);
        lblTest.setStyle("-fx-font-weight: bold;");

        Label lblError = new Label();
        lblError.setStyle("-fx-text-fill: -fx-error; -fx-font-size: 0.9em;");
        lblError.setVisible(false);
        lblError.managedProperty().bind(lblError.visibleProperty());

        // Update the derived test % and error state whenever train or val change
        Runnable updateTest = () -> {
            int train = spnTrain.getValue();
            int val   = spnVal.getValue();
            int test  = 100 - train - val;
            if (test < 0) {
                lblTest.setText("–");
                lblTest.setStyle("-fx-font-weight: bold; -fx-text-fill: -fx-error;");
                lblError.setText("Training + validation exceeds 100 %.");
                lblError.setVisible(true);
            } else {
                lblTest.setText(test + " %");
                lblTest.setStyle("-fx-font-weight: bold;");
                lblError.setVisible(false);
            }
        };
        spnTrain.valueProperty().addListener((obs, o, n) -> updateTest.run());
        spnVal.valueProperty().addListener((obs, o, n) -> updateTest.run());
        updateTest.run();

        // Column-name error (separate label, reused)
        Label lblColError = new Label();
        lblColError.setStyle("-fx-text-fill: -fx-accent; -fx-font-size: 0.9em;");
        lblColError.setVisible(false);
        lblColError.managedProperty().bind(lblColError.visibleProperty());
        tfColName.textProperty().addListener((obs, o, n) -> lblColError.setVisible(false));

        // Layout
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12, 16, 4, 16));
        javafx.scene.layout.ColumnConstraints cc0 = new javafx.scene.layout.ColumnConstraints();
        cc0.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.layout.ColumnConstraints cc1 = new javafx.scene.layout.ColumnConstraints();
        cc1.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(cc0, cc1);

        int r = 0;
        grid.add(new Label("Column name:"), 0, r);   grid.add(tfColName, 1, r++);
        grid.add(lblColError,               1, r++);
        grid.add(new javafx.scene.control.Separator(), 0, r++, 2, 1);
        grid.add(new Label("Random seed:"), 0, r);   grid.add(spnSeed,   1, r++);
        grid.add(new javafx.scene.control.Separator(), 0, r++, 2, 1);

        // Split % rows with inline description
        grid.add(new Label("Training %:"),   0, r);  grid.add(spnTrain,  1, r++);
        grid.add(new Label("Validation %:"), 0, r);  grid.add(spnVal,    1, r++);

        HBox testRow = new HBox(6, new Label("Test %:"), lblTest,
                new Label("(derived)"));
        testRow.setAlignment(Pos.CENTER_LEFT);
        testRow.setStyle("-fx-text-fill: -fx-mid-text-color;");
        grid.add(new Label("Test %:"), 0, r);        grid.add(
                new HBox(6, lblTest, new Label("(derived)")), 1, r++);
        grid.add(lblError, 0, r++, 2, 1);

        // n-images summary so the user knows what 80% means in practice
        Label lblSummary = new Label();
        lblSummary.setText(visibleEntries.size() + " images — "
                + "approx. " + Math.round(visibleEntries.size() * 0.8) + " / "
                + Math.round(visibleEntries.size() * 0.1) + " / "
                + Math.round(visibleEntries.size() * 0.1) + " at 80/10/10");
        lblSummary.setStyle("-fx-text-fill: -fx-mid-text-color; -fx-font-size: 0.9em;");

        // Update summary when spinners change
        Runnable updateSummary = () -> {
            double tr = spnTrain.getValue() / 100.0;
            double v  = spnVal.getValue()   / 100.0;
            double te = 1.0 - tr - v;
            if (te < 0) { lblSummary.setText(""); return; }
            int n = visibleEntries.size();
            lblSummary.setText(n + " images — approx. "
                    + Math.round(n * tr) + " training / "
                    + Math.round(n * v)  + " validation / "
                    + Math.round(n * te) + " test");
        };
        spnTrain.valueProperty().addListener((obs, o, n2) -> updateSummary.run());
        spnVal.valueProperty().addListener((obs, o, n2) -> updateSummary.run());
        updateSummary.run();

        grid.add(lblSummary, 0, r++, 2, 1);

        ButtonType btnAssign = new ButtonType("Assign", ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = new Dialog<>();
        var qupath = QuPathGUI.getInstance();
        if (qupath != null) dialog.initOwner(qupath.getStage());
        dialog.setTitle("Assign train/validation/test split");
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().setAll(btnAssign, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(400);

        Button btnAssignNode = (Button) dialog.getDialogPane().lookupButton(btnAssign);

        // Disable Assign when: column name blank, or train+val > 100
        btnAssignNode.disableProperty().bind(
                Bindings.createBooleanBinding(
                        () -> tfColName.getText().isBlank()
                                || (spnTrain.getValue() + spnVal.getValue()) > 100,
                        tfColName.textProperty(),
                        spnTrain.valueProperty(),
                        spnVal.valueProperty()));

        // Intercept to handle existing column without closing dialog
        btnAssignNode.addEventFilter(ActionEvent.ACTION, ev -> {
            String colName = tfColName.getText().trim();
            if (tableHasColumn(table, colName)) {
                // Ask overwrite/cancel — if cancel, keep dialog open
                ButtonType btnOverwrite = new ButtonType("Overwrite");
                Dialog<ButtonType> confirm = new Dialog<>();
                if (qupath != null) confirm.initOwner(qupath.getStage());
                confirm.setTitle("Column exists");
                confirm.setContentText(
                        "Column \u201c" + colName + "\u201d already exists.\nOverwrite its values?");
                confirm.getDialogPane().getButtonTypes().setAll(btnOverwrite, ButtonType.CANCEL);
                var choice = confirm.showAndWait();
                if (choice.isEmpty() || choice.get() != btnOverwrite) {
                    ev.consume(); // keep main dialog open
                }
            }
        });

        dialog.setOnShown(ev -> tfColName.requestFocus());

        if (dialog.showAndWait().map(b -> b.getButtonData() != ButtonData.OK_DONE).orElse(true))
            return;

        // ---- Compute split -------------------------------------------------
        String colName = tfColName.getText().trim();
        int    seed    = spnSeed.getValue();
        int    train   = spnTrain.getValue();
        int    val     = spnVal.getValue();
        // Operate on visible (filtered) rows only — same as Search & Replace
        int    n       = visibleEntries.size();

        // Shuffle indices with the seed; assign labels by cumulative threshold
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < n; i++) indices.add(i);
        Collections.shuffle(indices, new Random(seed));

        int nTrain = (int) Math.round(n * train / 100.0);
        int nVal   = (int) Math.round(n * val   / 100.0);
        // Clamp so nTrain + nVal <= n (rounding edge case)
        if (nTrain + nVal > n) nVal = n - nTrain;

        String[] labels = new String[n];
        for (int i = 0; i < n; i++) {
            int idx = indices.get(i);
            if      (i < nTrain)         labels[idx] = "training";
            else if (i < nTrain + nVal)  labels[idx] = "validation";
            else                         labels[idx] = "test";
        }

        // Ensure the column exists in the table
        if (!tableHasColumn(table, colName))
            addTableColumn(table, colName, context);

        // Build undoable batch
        MetadataEdit batchEdit = new MetadataEdit();
        for (int i = 0; i < n; i++) {
            ImageEntryWrapper w = visibleEntries.get(i);
            String oldVal = w.getMetadataValue(colName);
            String newVal = labels[i];
            if (!newVal.equals(oldVal))
                batchEdit.addChange(w, colName, oldVal, newVal);
        }
        if (!batchEdit.isEmpty())
            context.execute(batchEdit);

        Dialogs.showInfoNotification("Train/val/test split",
                "Assigned split to " + n + " images in column \u201c" + colName + "\u201d\n"
                + "  training: " + nTrain + "  validation: " + nVal
                + "  test: " + (n - nTrain - nVal));
    }

    private static void showPatternDialog(TableView<ImageEntryWrapper> table, EditorContext context) {
        // Get all current metadata column names (excluding fixed columns)
        List<String> availableColumns = new ArrayList<>();
        for (TableColumn<ImageEntryWrapper, ?> col : table.getColumns()) {
            String colName = col.getText();
            if (!INDEX.equals(colName)) {  //&& !IMAGE_NAME.equals(colName)) {
                availableColumns.add(colName);
            }
        }
        
        if (availableColumns.isEmpty()) {
            Dialogs.showWarningNotification("No Columns", 
                "No metadata columns available. Add some columns first.");
            return;
        }
        
        PatternController.showPatternDialog(table.getItems(), availableColumns, context);
    }

        /**
     * Populates {@code PathName} and {@code FileName} metadata columns from each
     * entry's first URI, using CellProfiler naming conventions.
     * <p>
     * For {@code file://} URIs the native filesystem path is used so the values
     * are immediately usable in CellProfiler pipelines.  For non-file URIs (e.g.
     * OMERO) the URI's path component is split on {@code '/'} as a best-effort
     * fallback.
     * <p>
     * If either column already contains values the user is asked whether to
     * overwrite or skip those cells.  The entire operation is a single undoable
     * batch.
     */
    private static void addPathNameFileNameColumns(TableView<ImageEntryWrapper> table,
                                                    List<ImageEntryWrapper> entries,
                                                    EditorContext context) {
        // Check whether either column already has values
        boolean hasExisting = false;
        for (String col : List.of(COL_PATHNAME, COL_FILENAME)) {
            if (!tableHasColumn(table, col)) continue;
            for (ImageEntryWrapper w : entries) {
                String v = w.getMetadataValue(col);
                if (v != null && !v.isEmpty()) { hasExisting = true; break; }
            }
            if (hasExisting) break;
        }

        boolean overwrite = true;
        if (hasExisting) {
            ButtonType btnOverwrite = new ButtonType("Overwrite");
            ButtonType btnSkip      = new ButtonType("Skip existing");
            Dialog<ButtonType> d = new Dialog<>();
            var qupath = QuPathGUI.getInstance();
            if (qupath != null) d.initOwner(qupath.getStage());
            d.setTitle("PathName \u0026 FileName columns");
            d.setHeaderText("One or both columns already contain values.");
            d.setContentText(
                    "Overwrite   \u2014 replace all existing values\n" +
                    "Skip existing \u2014 leave cells that already have a value unchanged");
            d.getDialogPane().getButtonTypes().setAll(btnOverwrite, btnSkip, ButtonType.CANCEL);
            var choice = d.showAndWait();
            if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) return;
            overwrite = (choice.get() == btnOverwrite);
        }

        // Ensure columns exist in the table (creates them if absent)
        for (String col : List.of(COL_PATHNAME, COL_FILENAME)) {
            if (!tableHasColumn(table, col))
                addTableColumn(table, col, context);
        }

        final boolean doOverwrite = overwrite;
        MetadataEdit batchEdit = new MetadataEdit();
        int skipped = 0;

        for (ImageEntryWrapper wrapper : entries) {
            String[] parts = resolvePathAndFile(wrapper.entry);
            String newPath = parts[0];
            String newFile = parts[1];

            for (String[] pair : new String[][]{{COL_PATHNAME, newPath}, {COL_FILENAME, newFile}}) {
                String col    = pair[0];
                String newVal = pair[1];
                String oldVal = wrapper.getMetadataValue(col);
                boolean hasValue = oldVal != null && !oldVal.isEmpty();
                if (hasValue && !doOverwrite) { skipped++; continue; }
                if (newVal.equals(oldVal == null ? "" : oldVal)) continue;
                batchEdit.addChange(wrapper, col, oldVal, newVal);
            }
        }

        if (!batchEdit.isEmpty())
            context.execute(batchEdit);

        String msg = "PathName and FileName set for " + entries.size() + " image(s).";
        if (skipped > 0)
            msg += "\n" + skipped + " cell(s) skipped (already had a value).";
        Dialogs.showInfoNotification("Add PathName \u0026 FileName", msg);
    }

    /**
     * Returns {@code [pathName, fileName]} for the first URI of {@code entry}.
     * <ul>
     *   <li>For {@code file://} URIs the native path separator is used.</li>
     *   <li>For other URIs (e.g. OMERO {@code http://}) the URI path component
     *       is split on {@code '/'} as a best-effort fallback.</li>
     *   <li>If the entry has no URIs both values are empty strings.</li>
     * </ul>
     */
    private static String[] resolvePathAndFile(ProjectImageEntry<?> entry) {
        Collection<URI> uris;
        try {
            uris = entry.getURIs();
        } catch (IOException ex) {
            logger.warn("Could not retrieve URIs for entry '{}': {}", entry.getImageName(), ex.getMessage());
            return new String[]{"", ""};
        }
        if (uris == null || uris.isEmpty()) return new String[]{"", ""};

        URI uri = uris.iterator().next();
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                // Paths.get(URI) decodes percent-encoding automatically, so spaces
                // in directory or file names are preserved as spaces, never as %20.
                java.nio.file.Path p = Paths.get(uri);
                String fileName = p.getFileName() != null ? p.getFileName().toString() : "";
                String pathName = p.getParent()   != null ? p.getParent().toString()   : "";
                return new String[]{pathName, fileName};
            }
        } catch (Exception ex) {
            logger.debug("Could not resolve file path for URI {}: {}", uri, ex.getMessage());
        }

        // Non-file URI fallback: split the URI path component.
        // URI.getPath() returns the decoded path (unlike getRawPath()), so %20
        // is already converted to a space here too.
        String uriPath = uri.getPath();
        if (uriPath == null || uriPath.isEmpty()) return new String[]{uri.toString(), ""};
        int lastSlash = uriPath.lastIndexOf('/');
        if (lastSlash < 0) return new String[]{"", uriPath};
        return new String[]{uriPath.substring(0, lastSlash), uriPath.substring(lastSlash + 1)};
    }

    /**
     * Binds a column's cell-value factory and edit-commit handler to the given
     * metadata key.  Called once on creation and again whenever a column is
     * renamed (redo) or a rename is undone, so the column always reads from and
     * writes to the correct key.
     */
    static void bindColumnToKey(TableColumn<ImageEntryWrapper, String> col,
                                         String key,
                                         EditorContext context) {
        col.setCellValueFactory(v -> v.getValue().getProperty(key));
        col.setOnEditCommit(e -> {
            String newValue = e.getNewValue();
            String oldValue = e.getOldValue();
            if (newValue == null && oldValue == null) return;
            if (newValue != null && newValue.equals(oldValue)) return;
            MetadataEdit edit = new MetadataEdit();
            edit.addChange(e.getRowValue(), key, oldValue, newValue);
            context.execute(edit);
        });
    }

    static TableColumn<ImageEntryWrapper, String> createMetadataColumn(String columnName) {
        TableColumn<ImageEntryWrapper, String> col = new TableColumn<>(columnName);
        col.setEditable(true);
        col.setSortable(true);
        col.setCellFactory(TextFieldTableCell.forTableColumn());
        return col;
    }

    private static void addTableColumn(TableView<ImageEntryWrapper> table,
                                        String metadataName,
                                        EditorContext context) {
        TableColumn<ImageEntryWrapper, String> col = createMetadataColumn(metadataName);
        bindColumnToKey(col, metadataName, context);
        table.getColumns().add(col);
    }

    // =========================================================================
    // COPY / PASTE
    // =========================================================================

    private static void copySelectedCellsToClipboard(TableView<?> table, boolean warnIfDiscontinuous) {
        var positions = table.getSelectionModel().getSelectedCells();
        if (positions.isEmpty()) return;

        int[] rows = positions.stream().mapToInt(TablePosition::getRow).sorted().toArray();
        int[] cols = positions.stream().mapToInt(TablePosition::getColumn).sorted().toArray();
        boolean isContinuous =
                (long)(rows[rows.length-1] - rows[0] + 1) * (cols[cols.length-1] - cols[0] + 1)
                        == positions.size();
        if (!isContinuous) {
            if (warnIfDiscontinuous)
                Dialogs.showWarningNotification("Copy", "Cannot copy a discontinuous selection.");
            return;
        }
        copyToClipboard(positions);
    }

    private static <T> void copyEntireTableToClipboard(TableView<T> table) {
        List<TablePosition> positions = new ArrayList<>();
        for (TableColumn<?, ?> column : table.getColumns())
            for (int row = 0; row < table.getItems().size(); row++)
                positions.add(new TablePosition(table, row, column));
        copyToClipboard(positions);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void copyToClipboard(List<TablePosition> positions) {
        positions = new ArrayList<>(positions);
        positions.sort((p1, p2) -> {
            int row = Integer.compare(p1.getRow(), p2.getRow());
            return row != 0 ? row : Integer.compare(p1.getColumn(), p2.getColumn());
        });
        StringBuilder sb = new StringBuilder();
        int lastRow = -1;
        for (TablePosition tp : positions) {
            int row = tp.getRow();
            Object data = tp.getTableColumn().getCellData(row);
            if (row == lastRow)    sb.append('\t');
            else if (lastRow >= 0) sb.append('\n');
            if (data != null) sb.append(data);
            lastRow = row;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(content);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void pasteClipboardContentsToTable(TableView<ImageEntryWrapper> table,
                                                       EditorContext context) {
        String s = Clipboard.getSystemClipboard().getString();
        if (s == null || s.isEmpty()) return;

        var selectedCells = table.getSelectionModel().getSelectedCells();
        if (selectedCells.isEmpty()) {
            Dialogs.showWarningNotification("Paste", "Please select a start cell to paste into.");
            return;
        }

        TablePosition anchor = (TablePosition) selectedCells.stream()
                .min((p1, p2) -> {
                    int r = Integer.compare(p1.getRow(), p2.getRow());
                    return r != 0 ? r : Integer.compare(p1.getColumn(), p2.getColumn());
                }).get();

        int startRow = anchor.getRow();
        int startCol = anchor.getColumn();

        String[] clipRows = s.split("\\r?\\n", -1);
        // Remove the trailing empty element that Excel/LibreOffice may add
        if (clipRows.length > 0 && clipRows[clipRows.length - 1].isBlank())
            clipRows = Arrays.copyOf(clipRows, clipRows.length - 1);
        if (clipRows.length == 0) return;

        // Single-value fill: paste one value into all selected cells
        if (clipRows.length == 1 && !clipRows[0].contains("\t") && selectedCells.size() > 1) {
            applyBatchChange(selectedCells, stripQuotes(clipRows[0].trim()), context);
            return;
        }

        // Standard anchor-paste
        int rowsAvailable = table.getItems().size() - startRow;
        if (clipRows.length > rowsAvailable) {
            Dialogs.showErrorNotification("Paste error",
                    "Not enough project entries below the selected cell.\n" +
                    "Data rows: " + clipRows.length + ", available: " + rowsAvailable);
            return;
        }

        MetadataEdit batchEdit = new MetadataEdit();
        boolean attemptedReadOnly = false;
        List<TableColumn<ImageEntryWrapper, ?>> columns = table.getColumns();

        for (int r = 0; r < clipRows.length; r++) {
            String[] rowData = clipRows[r].split("\t", -1);
            int targetRow = startRow + r;
            for (int c = 0; c < rowData.length; c++) {
                int targetColIndex = startCol + c;
                if (targetColIndex >= columns.size()) break;
                String key = columns.get(targetColIndex).getText();
                if (INDEX.equals(key) || IMAGE_NAME.equals(key)) {
                    attemptedReadOnly = true;
                    continue;
                }
                ImageEntryWrapper wrapper = table.getItems().get(targetRow);
                String newVal = stripQuotes(rowData[c].trim());
                String oldVal = wrapper.getMetadataValue(key);
                batchEdit.addChange(wrapper, key, oldVal, newVal);
            }
        }

        if (!batchEdit.isEmpty()) {
            context.execute(batchEdit);
        } else if (attemptedReadOnly) {
            Dialogs.showWarningNotification("Paste", "Cannot modify the '#' or 'Image name' columns.");
        }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\""))
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        return s;
    }

    // =========================================================================
    // ENTRY FILTER
    // =========================================================================

    /**
     * Filters the table's visible rows to those whose image name or any metadata
     * value matches {@code text} (case-insensitive).
     * <p>
     * This is purely a view operation: the authoritative {@code entries} list is
     * never modified, so Save, Undo/Redo, Remove Column, and Export all continue
     * to operate on the full project data regardless of the current filter.
     */
    private static void applyEntryFilter(String text,
                                          List<ImageEntryWrapper> entries,
                                          TableView<ImageEntryWrapper> table,
                                          Label statusLabel) {
        if (text == null || text.isBlank()) {
            table.getItems().setAll(entries);
            statusLabel.setText("");
            return;
        }
        var predicate = ProjectEntryPredicate.createIgnoreCase(text);
        List<ImageEntryWrapper> filtered = entries.stream()
                .filter(w -> predicate.test(w.entry))
                .toList();
        table.getItems().setAll(filtered);
        int shown = filtered.size();
        int total = entries.size();
        statusLabel.setText(shown == total
                ? ""
                : "Showing " + shown + " of " + total);
    }

    // =========================================================================
    // SEARCH & REPLACE
    // =========================================================================

    /**
     * Shows a Search &amp; Replace dialog scoped to a single user-selected metadata column.
     * <p>
     * All replacements are recorded as a single {@link MetadataEdit} and pushed onto the
     * undo/redo stack, so they can be reversed with a single Ctrl+Z.
     */
    private static void showSearchReplaceDialog(TableView<ImageEntryWrapper> table,
                                                 EditorContext context) {
        // Collect editable (metadata) columns only
        List<String> metadataColumns = new ArrayList<>();
        for (TableColumn<ImageEntryWrapper, ?> col : table.getColumns()) {
            String h = col.getText();
            if (!INDEX.equals(h) && !IMAGE_NAME.equals(h))
                metadataColumns.add(h);
        }
        if (metadataColumns.isEmpty()) {
            Dialogs.showInfoNotification("Search & Replace", "No metadata columns to search.");
            return;
        }

        // ---- Build dialog layout -------------------------------------------
        javafx.scene.control.ComboBox<String> cboColumn = new javafx.scene.control.ComboBox<>();
        cboColumn.getItems().setAll(metadataColumns);

        // Pre-select the column that is currently focused in the table, if any
        var selectedCells = table.getSelectionModel().getSelectedCells();
        if (!selectedCells.isEmpty()) {
            String focusedCol = selectedCells.get(0).getTableColumn().getText();
            if (metadataColumns.contains(focusedCol))
                cboColumn.setValue(focusedCol);
        }
        if (cboColumn.getValue() == null)
            cboColumn.setValue(metadataColumns.get(0));

        javafx.scene.control.TextField tfSearch  = new javafx.scene.control.TextField();
        javafx.scene.control.TextField tfReplace = new javafx.scene.control.TextField();
        javafx.scene.control.CheckBox  cbCase    = new javafx.scene.control.CheckBox("Case sensitive");
        javafx.scene.control.CheckBox  cbWhole   = new javafx.scene.control.CheckBox("Match whole cell");

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12, 16, 4, 16));

        int row = 0;
        grid.add(new javafx.scene.control.Label("Column:"),      0, row);
        grid.add(cboColumn,                                        1, row++);
        grid.add(new javafx.scene.control.Label("Search for:"),  0, row);
        grid.add(tfSearch,                                         1, row++);
        grid.add(new javafx.scene.control.Label("Replace with:"), 0, row);
        grid.add(tfReplace,                                        1, row++);
        grid.add(cbCase,  1, row++);
        grid.add(cbWhole, 1, row);

        javafx.scene.layout.ColumnConstraints cc1 = new javafx.scene.layout.ColumnConstraints();
        cc1.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.layout.ColumnConstraints cc2 = new javafx.scene.layout.ColumnConstraints();
        cc2.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        cc2.setMinWidth(220);
        grid.getColumnConstraints().addAll(cc1, cc2);

        // Live preview label showing match count
        javafx.scene.control.Label lblPreview = new javafx.scene.control.Label();
        lblPreview.setStyle("-fx-text-fill: -fx-accent;");
        grid.add(lblPreview, 0, ++row, 2, 1);

        // Update preview whenever any relevant field changes
        Runnable updatePreview = () -> {
            String colKey      = cboColumn.getValue();
            String searchText  = tfSearch.getText();
            boolean caseSens   = cbCase.isSelected();
            boolean wholeCell  = cbWhole.isSelected();
            if (colKey == null || searchText.isEmpty()) {
                lblPreview.setText("");
                return;
            }
            long count = table.getItems().stream().filter(w -> {
                String val = w.getMetadataValue(colKey);
                if (val == null) return false;
                return matches(val, searchText, caseSens, wholeCell);
            }).count();
            lblPreview.setText(count == 0 ? "No matches found."
                    : count + " cell" + (count == 1 ? "" : "s") + " will be updated.");
        };

        tfSearch.textProperty().addListener((obs, o, n) -> updatePreview.run());
        cboColumn.valueProperty().addListener((obs, o, n) -> updatePreview.run());
        cbCase.selectedProperty().addListener((obs, o, n) -> updatePreview.run());
        cbWhole.selectedProperty().addListener((obs, o, n) -> updatePreview.run());

        // ---- Show dialog ---------------------------------------------------
        ButtonType btnReplace = new ButtonType("Replace All", ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = new Dialog<>();
        var qupath = QuPathGUI.getInstance();
        if (qupath != null) dialog.initOwner(qupath.getStage());
        dialog.setTitle("Search & Replace");
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().setAll(btnReplace, ButtonType.CANCEL);

        // Disable Replace All when search field is empty
        Button btnReplaceNode =
                (Button) dialog.getDialogPane().lookupButton(btnReplace);
        btnReplaceNode.disableProperty().bind(tfSearch.textProperty().isEmpty());

        // Focus the search field when the dialog opens
        dialog.setOnShown(ev -> tfSearch.requestFocus());

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get().getButtonData() != ButtonData.OK_DONE)
            return;

        // ---- Apply replacements as a single undoable batch -----------------
        String colKey      = cboColumn.getValue();
        String searchText  = tfSearch.getText();
        String replaceText = tfReplace.getText();
        boolean caseSens   = cbCase.isSelected();
        boolean wholeCell  = cbWhole.isSelected();

        // Operates on table.getItems() — if a filter is active, only visible rows
        // are affected. This is intentional: filter first, then replace.
        MetadataEdit batchEdit = new MetadataEdit();
        for (ImageEntryWrapper wrapper : table.getItems()) {
            String oldVal = wrapper.getMetadataValue(colKey);
            if (oldVal == null) continue;
            if (!matches(oldVal, searchText, caseSens, wholeCell)) continue;
            String newVal = wholeCell
                    ? replaceText
                    : replaceAll(oldVal, searchText, replaceText, caseSens);
            if (!newVal.equals(oldVal))
                batchEdit.addChange(wrapper, colKey, oldVal, newVal);
        }

        if (batchEdit.isEmpty()) {
            Dialogs.showInfoNotification("Search & Replace", "No matching cells found.");
        } else {
            context.execute(batchEdit);
            Dialogs.showInfoNotification("Search & Replace",
                    "Replaced values in " + countChanges(batchEdit) + " cell(s) in column \u201c" + colKey + "\u201d.");
        }
    }

    /** Returns true when {@code cellValue} is considered a match for the search term. */
    private static boolean matches(String cellValue, String searchText,
                                    boolean caseSensitive, boolean wholeCell) {
        if (wholeCell) {
            return caseSensitive
                    ? cellValue.equals(searchText)
                    : cellValue.equalsIgnoreCase(searchText);
        } else {
            return caseSensitive
                    ? cellValue.contains(searchText)
                    : cellValue.toLowerCase().contains(searchText.toLowerCase());
        }
    }

    /** Replaces all occurrences of {@code search} in {@code value} with {@code replacement}. */
    private static String replaceAll(String value, String search,
                                      String replacement, boolean caseSensitive) {
        if (caseSensitive) {
            return value.replace(search, replacement);
        }
        // Case-insensitive replace that preserves the rest of the string
        StringBuilder sb = new StringBuilder();
        String lowerValue  = value.toLowerCase();
        String lowerSearch = search.toLowerCase();
        int start = 0;
        int idx;
        while ((idx = lowerValue.indexOf(lowerSearch, start)) >= 0) {
            sb.append(value, start, idx);
            sb.append(replacement);
            start = idx + search.length();
        }
        sb.append(value, start, value.length());
        return sb.toString();
    }

    /** Counts the number of individual changes in a {@link MetadataEdit}. */
    private static int countChanges(MetadataEdit edit) {
        return edit.changes.size();
    }

    // =========================================================================
    // DELETE / BATCH EDIT
    // =========================================================================

    private static void handleDelete(TableView<ImageEntryWrapper> table, EditorContext context) {
        var positions = table.getSelectionModel().getSelectedCells();
        if (!positions.isEmpty())
            applyBatchChange(positions, null, context);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyBatchChange(List<TablePosition> positions,
                                          String newValue,
                                          EditorContext context) {
        MetadataEdit batchEdit = new MetadataEdit();
        boolean attemptedReadOnly = false;

        for (TablePosition tp : positions) {
            String key = tp.getTableColumn().getText();
            if (INDEX.equals(key) || IMAGE_NAME.equals(key)) {
                attemptedReadOnly = true;
                continue;
            }
            ImageEntryWrapper wrapper = (ImageEntryWrapper) tp.getTableView().getItems().get(tp.getRow());
            String oldVal = wrapper.getMetadataValue(key);
            if (newValue == null && oldVal == null) continue;
            if (newValue != null && newValue.equals(oldVal)) continue;
            batchEdit.addChange(wrapper, key, oldVal, newValue);
        }

        if (!batchEdit.isEmpty()) {
            context.execute(batchEdit);
        } else if (attemptedReadOnly && batchEdit.isEmpty()) {
            Dialogs.showWarningNotification("Metadata", "The '#' and 'Image name' columns cannot be changed.");
        }
    }

    // =========================================================================
    // UNDO / REDO
    // =========================================================================

    /**
     * Common interface for all undoable operations in the editor.
     * <p>
     * {@link MetadataEdit} handles cell-level changes.
     * {@link ColumnRenameEdit}, {@link ColumnCopyEdit}, and {@link ColumnRemoveEdit}
     * handle column-level structural changes (header + data together).
     */
    public interface UndoableEdit {
        void undo();
        void redo();
    }

    public static class EditorContext {
        final Stack<UndoableEdit> undoStack = new Stack<>();
        final Stack<UndoableEdit> redoStack = new Stack<>();
        final TableView<ImageEntryWrapper> table;
        final MenuItem miUndo;
        final MenuItem miRedo;

        EditorContext(TableView<ImageEntryWrapper> table, MenuItem miUndo, MenuItem miRedo) {
            this.table  = table;
            this.miUndo = miUndo;
            this.miRedo = miRedo;
            updateMenuState();
        }

        public TableView<ImageEntryWrapper> getTable() {
            return table;
        }

        void execute(UndoableEdit edit) {
            edit.redo();
            undoStack.push(edit);
            redoStack.clear();
            updateMenuState();
            table.refresh();
        }

        void undo() {
            if (undoStack.isEmpty()) return;
            UndoableEdit edit = undoStack.pop();
            edit.undo();
            redoStack.push(edit);
            updateMenuState();
            table.refresh();
        }

        void redo() {
            if (redoStack.isEmpty()) return;
            UndoableEdit edit = redoStack.pop();
            edit.redo();
            undoStack.push(edit);
            updateMenuState();
            table.refresh();
        }

        void updateMenuState() {
            miUndo.setDisable(undoStack.isEmpty());
            miUndo.setText(undoStack.isEmpty() ? "Undo" : "Undo (" + undoStack.size() + ")");
            miRedo.setDisable(redoStack.isEmpty());
            miRedo.setText(redoStack.isEmpty() ? "Redo" : "Redo (" + redoStack.size() + ")");
        }
    }

    // =========================================================================
    // METADATA EDIT MODEL
    // =========================================================================

    private static class MetadataEdit implements UndoableEdit {
        final List<SingleChange> changes = new ArrayList<>();

        void addChange(ImageEntryWrapper wrapper, String key, String oldValue, String newValue) {
            changes.add(new SingleChange(wrapper, key, oldValue, newValue));
        }

        boolean isEmpty() { return changes.isEmpty(); }

        public void undo() {
            for (SingleChange c : changes) {
                if (c.oldValue == null) c.wrapper.removeMetadataValue(c.key);
                else                   c.wrapper.putMetadataValue(c.key, c.oldValue);
            }
        }

        public void redo() {
            for (SingleChange c : changes) {
                if (c.newValue == null) c.wrapper.removeMetadataValue(c.key);
                else                   c.wrapper.putMetadataValue(c.key, c.newValue);
            }
        }
    }

    private static class SingleChange {
        final ImageEntryWrapper wrapper;
        final String key;
        final String oldValue;
        final String newValue;

        SingleChange(ImageEntryWrapper wrapper, String key, String oldValue, String newValue) {
            this.wrapper  = wrapper;
            this.key      = key;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
    }

    // =========================================================================
    // COLUMN-LEVEL UNDOABLE EDITS
    // =========================================================================

    /**
     * Renames a metadata column: updates the column header, rebinds the cell
     * factory, and migrates every wrapper's data from the old key to the new key.
     * Fully reversible.
     */
    private static class ColumnRenameEdit implements UndoableEdit {
        private final TableColumn<ImageEntryWrapper, String> column;
        private final List<ImageEntryWrapper> allEntries;
        private final String oldName;
        private final String newName;
        private final EditorContext context;

        ColumnRenameEdit(TableColumn<ImageEntryWrapper, String> column,
                          List<ImageEntryWrapper> allEntries,
                          String oldName, String newName, EditorContext context) {
            this.column     = column;
            this.allEntries = allEntries;
            this.oldName    = oldName;
            this.newName    = newName;
            this.context    = context;
        }

        @Override
        public void redo() {
            column.setText(newName);
            bindColumnToKey(column, newName, context);
            for (ImageEntryWrapper w : allEntries) {
                String val = w.getMetadataValue(oldName);
                w.removeMetadataValue(oldName);
                if (val != null) w.putMetadataValue(newName, val);
            }
        }

        @Override
        public void undo() {
            column.setText(oldName);
            bindColumnToKey(column, oldName, context);
            for (ImageEntryWrapper w : allEntries) {
                String val = w.getMetadataValue(newName);
                w.removeMetadataValue(newName);
                if (val != null) w.putMetadataValue(oldName, val);
            }
        }
    }

    /**
     * Copies a metadata column: adds a new column to the table and populates
     * it with the source column's values.  Undo removes the column and clears
     * the copied values.
     */
    private static class ColumnCopyEdit implements UndoableEdit {
        private final TableView<ImageEntryWrapper> table;
        private final TableColumn<ImageEntryWrapper, String> newColumn;
        private final List<ImageEntryWrapper> allEntries;
        private final String srcKey;
        private final String dstKey;

        ColumnCopyEdit(TableView<ImageEntryWrapper> table,
                        TableColumn<ImageEntryWrapper, String> newColumn,
                        List<ImageEntryWrapper> allEntries,
                        String srcKey, String dstKey) {
            this.table      = table;
            this.newColumn  = newColumn;
            this.allEntries = allEntries;
            this.srcKey     = srcKey;
            this.dstKey     = dstKey;
        }

        @Override
        public void redo() {
            if (!table.getColumns().contains(newColumn))
                table.getColumns().add(newColumn);
            for (ImageEntryWrapper w : allEntries) {
                String val = w.getMetadataValue(srcKey);
                if (val != null) w.putMetadataValue(dstKey, val);
            }
        }

        @Override
        public void undo() {
            table.getColumns().remove(newColumn);
            for (ImageEntryWrapper w : allEntries)
                w.removeMetadataValue(dstKey);
        }
    }

    /**
     * Removes a metadata column: saves the column's position and all its values
     * so they can be fully restored by undo.
     */
    private static class ColumnRemoveEdit implements UndoableEdit {
        private final TableView<ImageEntryWrapper> table;
        private final TableColumn<ImageEntryWrapper, String> column;
        private final Map<ImageEntryWrapper, String> savedValues = new LinkedHashMap<>();
        private final int columnIndex;

        ColumnRemoveEdit(TableView<ImageEntryWrapper> table,
                          TableColumn<ImageEntryWrapper, String> column,
                          List<ImageEntryWrapper> allEntries) {
            this.table       = table;
            this.column      = column;
            this.columnIndex = table.getColumns().indexOf(column);
            String key = column.getText();
            for (ImageEntryWrapper w : allEntries) {
                String val = w.getMetadataValue(key);
                if (val != null) savedValues.put(w, val);
            }
        }

        @Override
        public void redo() {
            String key = column.getText();
            table.getColumns().remove(column);
            for (ImageEntryWrapper w : savedValues.keySet())
                w.removeMetadataValue(key);
        }

        @Override
        public void undo() {
            String key = column.getText();
            int insertAt = Math.min(columnIndex, table.getColumns().size());
            table.getColumns().add(insertAt, column);
            for (Map.Entry<ImageEntryWrapper, String> e : savedValues.entrySet())
                e.getKey().putMetadataValue(key, e.getValue());
        }
    }

    // =========================================================================
    // IMAGE ENTRY WRAPPER
    // =========================================================================

    static class ImageEntryWrapper {

        final ProjectImageEntry<?> entry;
        final SimpleIntegerProperty indexProperty;
        private final Map<String, String> metadataMap = new TreeMap<>();

        ImageEntryWrapper(ProjectImageEntry<?> entry, int index) {
            this.entry         = entry;
            this.indexProperty = new SimpleIntegerProperty(index);
            this.metadataMap.putAll(entry.getMetadataMap());
        }

        public ObservableStringValue getNameBinding() {
            return Bindings.createStringBinding(() -> entry.getImageName());
        }

        public void commitChanges() {
            if (metadataMap.equals(entry.getMetadataMap())) return;
            entry.clearMetadata();
            for (Entry<String, String> e : metadataMap.entrySet())
                entry.putMetadataValue(e.getKey(), e.getValue());
        }

        public boolean hasChanges() {
            return !metadataMap.equals(entry.getMetadataMap());
        }

        public ObservableStringValue getProperty(String columnName) {
            return Bindings.createStringBinding(() -> {
                String v = metadataMap.get(columnName);
                return v == null ? "" : v;
            });
        }

        public String getMetadataValue(Object key) {
            // If the requested key is "Image Name", return the actual entry name
            if (IMAGE_NAME.equals(key)) {
                return entry.getImageName();
            }
            return metadataMap.get(key);
        }

        public void putMetadataValue(String key, String value) { metadataMap.put(key, value); }

        public void removeMetadataValue(String key) { metadataMap.remove(key); }
    }
}