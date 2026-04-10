package qupath.ext.projectmetadataeditor;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;

import qupath.fx.dialogs.Dialogs;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controller for Pattern-based metadata extraction with column selection and undo/redo support.
 * Allows users to select a source column and extract metadata into new columns using regex patterns.
 */
public class PatternController extends BorderPane {
    private static final Logger logger = LoggerFactory.getLogger(PatternController.class);
    
    // Color palette for capture groups
    private static final String[] GROUP_COLORS = {
        "group-color-1", "group-color-2", "group-color-3",
        "group-color-4", "group-color-5", "group-color-6"
    };

    private CodeArea patternArea;
    //private TextField convertedField;
    private TextField groupsField;
    private ComboBox<String> sourceColumnCombo;
    private TableView<PreviewRow> resultsTable;
    private Button applyButton;
    private Button cancelButton;

    private final List<ProjectMetadataEditorCommand.ImageEntryWrapper> allEntries;
    private final ProjectMetadataEditorCommand.EditorContext context;

    /**
     * Creates a new PatternController dialog.
     * 
     * @param allEntries All image entries from the project
     * @param availableColumns List of available metadata column names
     * @param context The editor context for undo/redo support
     */
    public static void showPatternDialog(
            List<ProjectMetadataEditorCommand.ImageEntryWrapper> allEntries,
            List<String> availableColumns,
            ProjectMetadataEditorCommand.EditorContext context) {
        
        PatternController controller = new PatternController(allEntries, availableColumns, context);
        
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Extract Metadata from Pattern");
        dialog.setHeaderText("Apply a regex pattern to extract metadata into new columns");
        dialog.getDialogPane().setContent(controller);
        dialog.getDialogPane().setPrefSize(900, 600);
        
        ButtonType okButton = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(okButton, cancelButton);
        
        // Handle OK button
        dialog.setResultConverter(buttonType -> {
            if (buttonType == okButton) {
                controller.applyPattern();
            }
            return buttonType;
        });
        
        dialog.showAndWait();
    }

    private PatternController(
            List<ProjectMetadataEditorCommand.ImageEntryWrapper> allEntries,
            List<String> availableColumns,
            ProjectMetadataEditorCommand.EditorContext context) {
        
        this.allEntries = allEntries;
        this.context = context;

        initializeUI(availableColumns);
        
        // Set default pattern
        // (?P<Timepoint>\S+)\s+(?P<Treatment>[^_]+)_(?P<Marker>.+)
        String defaultPattern = "(?P<Prefix>[A-Z]+)_(?P<Number>\\d+)_(?P<Suffix>.*)";
        patternArea.replaceText(defaultPattern);
        
        // Add listeners
        patternArea.textProperty().addListener((obs, oldText, newText) -> updateUI());
        sourceColumnCombo.valueProperty().addListener((obs, oldVal, newVal) -> updateUI());
        
        // Apply CSS for syntax highlighting
        applySyntaxHighlightingCSS();
        
        // Initial update
        updateUI();
    }

    private void initializeUI(List<String> availableColumns) {
        VBox mainLayout = new VBox(10);
        mainLayout.setPadding(new Insets(10));
        
        // Column selection
        HBox columnBox = new HBox(10);
        columnBox.setAlignment(Pos.CENTER_LEFT);
        Label sourceLabel = new Label("Source Column:");
        sourceColumnCombo = new ComboBox<>(FXCollections.observableArrayList(availableColumns));
        if (!availableColumns.isEmpty()) {
            sourceColumnCombo.setValue(availableColumns.get(0));
        }
        sourceColumnCombo.setPrefWidth(200);
        columnBox.getChildren().addAll(sourceLabel, sourceColumnCombo);
        
        // Pattern input area
        Label patternLabel = new Label("Pattern (Python-style named groups):");
        patternArea = new CodeArea();
        patternArea.setPrefHeight(80);
        patternArea.setWrapText(true);
        
        // Converted pattern display
        /* 
        HBox convertedBox = new HBox(10);
        convertedBox.setAlignment(Pos.CENTER_LEFT);
        Label convertedLabel = new Label("Java Pattern:");
        convertedField = new TextField();
        convertedField.setEditable(false);
        convertedField.setPrefWidth(400);
        convertedBox.getChildren().addAll(convertedLabel, convertedField);
        */
        
        // Groups display
        HBox groupsBox = new HBox(10);
        groupsBox.setAlignment(Pos.CENTER_LEFT);
        Label groupsLabel = new Label("Capture Groups:");
        groupsField = new TextField();
        groupsField.setEditable(false);
        groupsField.setPrefWidth(400);
        groupsBox.getChildren().addAll(groupsLabel, groupsField);
        
        // Results table
        Label resultsLabel = new Label("Preview (first 100 rows):");
        resultsTable = new TableView<>();
        resultsTable.setPrefHeight(300);
        resultsTable.setFixedCellSize(28); // Standard row height is ~24-28px
        resultsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        
        // Add all components
        mainLayout.getChildren().addAll(
            columnBox,
            new Separator(),
            patternLabel,
            patternArea,
            //convertedBox,
            groupsBox,
            new Separator(),
            resultsLabel,
            resultsTable
        );
        
        this.setCenter(mainLayout);
    }

    /**
     * Main method to update all UI elements based on the current pattern and source column.
     */
    private void updateUI() {
        String cpPattern = patternArea.getText();
        String javaPattern = convertPattern(cpPattern);
        
        // Update pattern area syntax highlighting
        patternArea.setStyleSpans(0, computePatternHighlighting(cpPattern).create());
        
        // Update converted and groups fields
        //convertedField.setText(javaPattern);
        List<String> groups = extractGroupNames(javaPattern);
        groupsField.setText(groups.isEmpty() ? "No groups found" : String.join(", ", groups));
        
        // Update the results TableView
        updateTableView(javaPattern, groups);
    }

    private void updateTableView(String javaPattern, List<String> groupNames) {
        resultsTable.getColumns().clear();
        
        String sourceColumn = sourceColumnCombo.getValue();
        if (sourceColumn == null || allEntries.isEmpty()) {
            resultsTable.setPlaceholder(new Label("No data available."));
            return;
        }

        Pattern pattern;
        try {
            pattern = Pattern.compile(javaPattern);
        } catch (Exception e) {
            resultsTable.setPlaceholder(new Label("Invalid pattern: " + e.getMessage()));
            return;
        }

        // Create columns
        TableColumn<PreviewRow, String> sourceCol = new TableColumn<>(sourceColumn);
        sourceCol.setCellValueFactory(cellData -> cellData.getValue().sourceValueProperty());
        sourceCol.setCellFactory(col -> new HighlightedTableCell(pattern, groupNames));
        sourceCol.setPrefWidth(250);
        resultsTable.getColumns().add(sourceCol);

        // Add a column for each capture group
        for (String groupName : groupNames) {
            TableColumn<PreviewRow, String> col = new TableColumn<>(groupName);
            col.setCellValueFactory(cellData -> 
                new SimpleStringProperty(cellData.getValue().getExtractedValue(groupName))
            );
            col.setPrefWidth(150); // Gives the resize policy a baseline ratio
            col.setMinWidth(50);   // Prevents the columns from crushing completely

            resultsTable.getColumns().add(col);
        }

        // Populate data (limit to first 100 for performance)
        ObservableList<PreviewRow> data = FXCollections.observableArrayList();
        int limit = Math.min(100, allEntries.size());
        
        for (int i = 0; i < limit; i++) {
            var wrapper = allEntries.get(i);
            String sourceValue = wrapper.getMetadataValue(sourceColumn);
            if (sourceValue == null) {
                sourceValue = "";
            }
            
            Map<String, String> extracted = new HashMap<>();
            Matcher matcher = pattern.matcher(sourceValue);
            if (matcher.find()) {
                for (String group : groupNames) {
                    try {
                        String value = matcher.group(group);
                        extracted.put(group, value != null ? value : "");
                    } catch (IllegalArgumentException e) {
                        extracted.put(group, "");
                    }
                }
            }
            data.add(new PreviewRow(sourceValue, extracted));
        }
        
        resultsTable.setItems(data);
        
        if (allEntries.size() > 100) {
            resultsTable.setPlaceholder(new Label("Showing first 100 of " + allEntries.size() + " rows"));
        }
    }

    /**
     * Apply the pattern to all entries and create new columns.
     */
    private void applyPattern() {
        String sourceColumn = sourceColumnCombo.getValue();
        if (sourceColumn == null) {
            logger.warn("No source column selected");
            return;
        }
        
        String cpPattern = patternArea.getText();
        String javaPattern = convertPattern(cpPattern);
        List<String> groupNames = extractGroupNames(javaPattern);
        
        if (groupNames.isEmpty()) {
            logger.warn("No capture groups found in pattern. No metadata to apply.");
            Dialogs.showWarningNotification("Pattern Error", "No capture groups found in the pattern.");
            return;
        }
        
        Pattern pattern;
        try {
            pattern = Pattern.compile(javaPattern);
        } catch (Exception e) {
            logger.error("Invalid pattern: " + e.getMessage());
            Dialogs.showErrorNotification("Pattern Error", "Invalid pattern: " + e.getMessage());
            return;
        }
        
        // Create and execute the undoable edit
        ColumnPatternEdit edit = new ColumnPatternEdit(
            allEntries,
            sourceColumn,
            groupNames,
            pattern,
            context
        );
        
        context.execute(edit);
        
        logger.info("Pattern '{}' applied to column '{}'. Created {} new columns: {}", 
                   cpPattern, sourceColumn, groupNames.size(), String.join(", ", groupNames));
    }

    // =========================================================================
    // PATTERN CONVERSION AND HIGHLIGHTING
    // =========================================================================

    private String convertPattern(String cpPattern) {
        return cpPattern.replaceAll("\\(\\?P<", "(?<");
    }

    private List<String> extractGroupNames(String pattern) {
        List<String> groupNames = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\(\\?<(\\w+)>").matcher(pattern);
        while (matcher.find()) {
            groupNames.add(matcher.group(1));
        }
        return groupNames;
    }

    private StyleSpansBuilder<Collection<String>> computePatternHighlighting(String pattern) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        
        if (pattern == null || pattern.isEmpty()) {
            spansBuilder.add(Collections.singleton("plain"), 0);
            return spansBuilder;
        }

        Matcher matcher = Pattern.compile(
                "(?<GROUP>\\(\\?P<\\w+>)|" +
                "(?<SYNTAX>[\\\\\\.\\^\\$\\*\\+\\?\\{\\}\\(\\)\\|\\[\\]])|" +
                "(?<CHARCLASS>\\[.*?\\])|" +
                "(?<QUANTIFIER>\\{.*?\\})"
        ).matcher(pattern);

        int lastKwEnd = 0;
        int groupCount = 0;
        
        while (matcher.find()) {
            String styleClass = "plain";
            if (matcher.group("GROUP") != null) {
                styleClass = GROUP_COLORS[groupCount % GROUP_COLORS.length];
                groupCount++;
            } else if (matcher.group("SYNTAX") != null) {
                styleClass = "regex-syntax";
            } else if (matcher.group("CHARCLASS") != null) {
                styleClass = "char-class";
            } else if (matcher.group("QUANTIFIER") != null) {
                styleClass = "quantifier";
            }

            spansBuilder.add(Collections.singleton("plain"), matcher.start() - lastKwEnd);
            spansBuilder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(Collections.singleton("plain"), pattern.length() - lastKwEnd);
        return spansBuilder;
    }

    private void applySyntaxHighlightingCSS() {
        String css = """
            .code-area {
                -fx-font-family: 'Consolas', 'Monaco', monospace;
                -fx-font-size: 12px;
            }
            .code-area .regex-syntax {
                -fx-fill: #808080;
                -fx-font-weight: bold;
            }
            .code-area .char-class {
                -fx-fill: #0066cc;
                -fx-font-weight: bold;
            }
            .code-area .quantifier {
                -fx-fill: #cc6600;
                -fx-font-weight: bold;
            }
            .code-area .plain {
                -fx-fill: #000000;
            }
            .code-area .group-color-1 {
                -fx-fill: #22c55e;
                -fx-font-weight: bold;
            }
            .code-area .group-color-2 {
                -fx-fill: #f97316;
                -fx-font-weight: bold;
            }
            .code-area .group-color-3 {
                -fx-fill: #a855f7;
                -fx-font-weight: bold;
            }
            .code-area .group-color-4 {
                -fx-fill: #3b82f6;
                -fx-font-weight: bold;
            }
            .code-area .group-color-5 {
                -fx-fill: #ec4899;
                -fx-font-weight: bold;
            }
            .code-area .group-color-6 {
                -fx-fill: #14b8a6;
                -fx-font-weight: bold;
            }
            """;
        
        try {
            String encoded = Base64.getEncoder().encodeToString(css.getBytes());
            this.getStylesheets().add("data:text/css;base64," + encoded);
        } catch (Exception e) {
            logger.error("Could not apply CSS: " + e.getMessage());
        }
    }

    // =========================================================================
    // CUSTOM TABLE CELL FOR HIGHLIGHTED PREVIEW
    // =========================================================================

    private class HighlightedTableCell extends TableCell<PreviewRow, String> {
        private final Pattern pattern;
        private final TextFlow textFlow = new TextFlow();

        public HighlightedTableCell(Pattern pattern, List<String> groupNames) {
            this.pattern = pattern;
            this.textFlow.setMaxHeight(50);
            this.textFlow.setStyle("-fx-background-color: transparent;");
        }

        @Override
        protected void updateItem(String value, boolean empty) {
            super.updateItem(value, empty);
            if (empty || value == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            textFlow.getChildren().clear();
            Matcher matcher = pattern.matcher(value);

            if (matcher.find()) {
                int lastEnd = 0;
                for (int i = 1; i <= matcher.groupCount(); i++) {
                    int start = matcher.start(i);
                    int end = matcher.end(i);
                    if (start >= 0) {
                        if (start > lastEnd) {
                            textFlow.getChildren().add(new Text(value.substring(lastEnd, start)));
                        }
                        Text groupText = new Text(value.substring(start, end));
                        groupText.setStyle("-fx-fill: " + getHexColorForGroup(i - 1) + "; -fx-font-weight: bold;");
                        textFlow.getChildren().add(groupText);
                        lastEnd = end;
                    }
                }
                if (lastEnd < value.length()) {
                    textFlow.getChildren().add(new Text(value.substring(lastEnd)));
                }
            } else {
                textFlow.getChildren().add(new Text(value));
            }
            
            setGraphic(textFlow);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setPadding(new Insets(2, 5, 2, 5));
        }

        private String getHexColorForGroup(int index) {
            String[] hexColors = {"#22c55e", "#f97316", "#a855f7", "#3b82f6", "#ec4899", "#14b8a6"};
            return hexColors[index % hexColors.length];
        }
    }

    // =========================================================================
    // PREVIEW ROW DATA CLASS
    // =========================================================================

    private static class PreviewRow {
        private final String sourceValue;
        private final Map<String, String> extractedValues;

        PreviewRow(String sourceValue, Map<String, String> extractedValues) {
            this.sourceValue = sourceValue;
            this.extractedValues = extractedValues;
        }

        public SimpleStringProperty sourceValueProperty() {
            return new SimpleStringProperty(sourceValue);
        }

        public String getExtractedValue(String groupName) {
            return extractedValues.getOrDefault(groupName, "");
        }
    }

    // =========================================================================
    // UNDOABLE EDIT FOR PATTERN APPLICATION
    // =========================================================================

    /**
     * UndoableEdit that applies a pattern to a source column and creates new columns
     * with the extracted values. Can be fully undone and redone.
     */
    static class ColumnPatternEdit implements ProjectMetadataEditorCommand.UndoableEdit {
        private final List<ProjectMetadataEditorCommand.ImageEntryWrapper> allEntries;
        private final String sourceColumn;
        private final List<String> groupNames;
        private final Pattern pattern;
        private final ProjectMetadataEditorCommand.EditorContext context;
        private final TableView<ProjectMetadataEditorCommand.ImageEntryWrapper> table;

        // Store the created columns and their original index for undo
        private final Map<String, TableColumn<ProjectMetadataEditorCommand.ImageEntryWrapper, String>> createdColumns = new LinkedHashMap<>();
        private int insertIndex;

        ColumnPatternEdit(
                List<ProjectMetadataEditorCommand.ImageEntryWrapper> allEntries,
                String sourceColumn,
                List<String> groupNames,
                Pattern pattern,
                ProjectMetadataEditorCommand.EditorContext context) {
            
            this.allEntries = allEntries;
            this.sourceColumn = sourceColumn;
            this.groupNames = groupNames;
            this.pattern = pattern;
            this.context = context;
            this.table = context.getTable();
            this.insertIndex = table.getColumns().size();
        }

        @Override
        public void redo() {
            // Create columns for each group if they don't exist yet
            if (createdColumns.isEmpty()) {
                for (String groupName : groupNames) {
                    TableColumn<ProjectMetadataEditorCommand.ImageEntryWrapper, String> col = 
                        ProjectMetadataEditorCommand.createMetadataColumn(groupName);

                    ProjectMetadataEditorCommand.bindColumnToKey(col, groupName, context);
                    createdColumns.put(groupName, col);
                }
            }
            
            // Add columns to table
            for (TableColumn<ProjectMetadataEditorCommand.ImageEntryWrapper, String> col : createdColumns.values()) {
                if (!table.getColumns().contains(col)) {
                    table.getColumns().add(col);
                }
            }
            
            // Apply pattern to all entries
            for (ProjectMetadataEditorCommand.ImageEntryWrapper wrapper : allEntries) {
                String sourceValue = wrapper.getMetadataValue(sourceColumn);
                if (sourceValue == null) continue;
                
                Matcher matcher = pattern.matcher(sourceValue);
                if (matcher.find()) {
                    for (String groupName : groupNames) {
                        try {
                            String value = matcher.group(groupName);
                            if (value != null) {
                                wrapper.putMetadataValue(groupName, value);
                            }
                        } catch (IllegalArgumentException e) {
                            // Group not found in this match
                        }
                    }
                }
            }
            
            table.refresh();
        }

        @Override
        public void undo() {
            // Remove the created columns from the table
            for (TableColumn<ProjectMetadataEditorCommand.ImageEntryWrapper, String> col : createdColumns.values()) {
                table.getColumns().remove(col);
            }
            
            // Remove the metadata values from all entries
            for (ProjectMetadataEditorCommand.ImageEntryWrapper wrapper : allEntries) {
                for (String groupName : groupNames) {
                    wrapper.removeMetadataValue(groupName);
                }
            }
            
            table.refresh();
        }
    }
}
