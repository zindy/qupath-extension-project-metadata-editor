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
import java.util.ArrayList;
import java.util.Arrays;
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

import javafx.beans.binding.Bindings;
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
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.panes.ProjectBrowser;
import qupath.lib.gui.prefs.SystemMenuBar;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Command to enable editing of project metadata.
 * <p>
 * Features:
 * <ul>
 *   <li>File menu + toolbar buttons: Import CSV/TSV, Export (TSV / comma CSV / semicolon CSV)</li>
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
        colIndex.setSortable(false);
        table.getColumns().add(colIndex);

        TableColumn<ImageEntryWrapper, String> colName = new TableColumn<>(IMAGE_NAME);
        colName.setCellValueFactory(v -> v.getValue().getNameBinding());
        colName.setEditable(false);
        table.getColumns().add(colName);

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

        menuEdit.getItems().addAll(
                miUndo, miRedo,
                new SeparatorMenuItem(),
                miAddCol, miRemoveCol,
                new SeparatorMenuItem(),
                miCopy, miCopyFull, miPaste,
                new SeparatorMenuItem(),
                miSetCells
        );

        menubar.getMenus().addAll(menuFile, menuEdit);
        SystemMenuBar.manageChildMenuBar(menubar);

        // =====================================================================
        // IMPORT / EXPORT TOOLBAR BUTTONS (below the table)
        // =====================================================================
        Button btnImport = new Button("Import\u2026");
        btnImport.setOnAction(e -> importCsv(table, entries, context));
        btnImport.setTooltip(new javafx.scene.control.Tooltip(
                "Import metadata from a CSV or TSV file"));

        // Export button opens a small popup menu so the user can pick the format
        Button btnExport = new Button("Export\u2026");
        btnExport.setTooltip(new javafx.scene.control.Tooltip(
                "Export metadata to a CSV or TSV file"));
        btnExport.setOnAction(e -> {
            // Reuse the same FileChooser-based flow; let the user choose the format
            // via extension filters in the save dialog.
            exportCsvInteractive(table);
        });

        HBox toolbar = new HBox(8, btnImport, btnExport);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(4, 4, 4, 4));

        // =====================================================================
        // LAYOUT & DIALOG
        // =====================================================================
        BorderPane pane = new BorderPane();
        pane.setTop(menubar);
        pane.setCenter(table);
        pane.setBottom(toolbar);

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
     * via extension filters.  Called from the toolbar Export button.
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

        // Record clearing as undoable edit
        MetadataEdit batchEdit = new MetadataEdit();
        for (ImageEntryWrapper wrapper : entries) {
            String oldVal = wrapper.getMetadataValue(key);
            if (oldVal != null)
                batchEdit.addChange(wrapper, key, oldVal, null);
        }
        if (!batchEdit.isEmpty())
            context.execute(batchEdit);

        table.getColumns().removeIf(col -> key.equals(col.getText()));
    }

    private static boolean tableHasColumn(TableView<?> table, String name) {
        return table.getColumns().stream().anyMatch(col -> col.getText().equals(name));
    }

    private static void addTableColumn(TableView<ImageEntryWrapper> table,
                                        String metadataName,
                                        EditorContext context) {
        TableColumn<ImageEntryWrapper, String> col = new TableColumn<>(metadataName);
        col.setCellFactory(TextFieldTableCell.forTableColumn());
        col.setOnEditCommit(e -> {
            String newValue = e.getNewValue();
            String oldValue = e.getOldValue();
            if (newValue == null && oldValue == null) return;
            if (newValue != null && newValue.equals(oldValue)) return;
            MetadataEdit edit = new MetadataEdit();
            edit.addChange(e.getRowValue(), metadataName, oldValue, newValue);
            context.execute(edit);
        });
        col.setCellValueFactory(v -> v.getValue().getProperty(metadataName));
        col.setEditable(true);
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

    private static class EditorContext {
        final Stack<MetadataEdit> undoStack = new Stack<>();
        final Stack<MetadataEdit> redoStack = new Stack<>();
        final TableView<ImageEntryWrapper> table;
        final MenuItem miUndo;
        final MenuItem miRedo;

        EditorContext(TableView<ImageEntryWrapper> table, MenuItem miUndo, MenuItem miRedo) {
            this.table  = table;
            this.miUndo = miUndo;
            this.miRedo = miRedo;
            updateMenuState();
        }

        void execute(MetadataEdit edit) {
            edit.redo();
            undoStack.push(edit);
            redoStack.clear();
            updateMenuState();
            table.refresh();
        }

        void undo() {
            if (undoStack.isEmpty()) return;
            MetadataEdit edit = undoStack.pop();
            edit.undo();
            redoStack.push(edit);
            updateMenuState();
            table.refresh();
        }

        void redo() {
            if (redoStack.isEmpty()) return;
            MetadataEdit edit = redoStack.pop();
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

    private static class MetadataEdit {
        private final List<SingleChange> changes = new ArrayList<>();

        void addChange(ImageEntryWrapper wrapper, String key, String oldValue, String newValue) {
            changes.add(new SingleChange(wrapper, key, oldValue, newValue));
        }

        boolean isEmpty() { return changes.isEmpty(); }

        void undo() {
            for (SingleChange c : changes) {
                if (c.oldValue == null) c.wrapper.removeMetadataValue(c.key);
                else                   c.wrapper.putMetadataValue(c.key, c.oldValue);
            }
        }

        void redo() {
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

        public String getMetadataValue(Object key) { return metadataMap.get(key); }

        public void putMetadataValue(String key, String value) { metadataMap.put(key, value); }

        public void removeMetadataValue(String key) { metadataMap.remove(key); }
    }
}
