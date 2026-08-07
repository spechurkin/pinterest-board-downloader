package com.svyat.pinterestdownloader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

public final class PinterestDownloaderApp extends Application {

    private static final String PREF_OAUTH_TOKEN = "oauthAccessToken";
    private static final String PREF_LANGUAGE = "language";
    private static final String PREF_THEME = "theme";

    private final PinterestClient client = new PinterestClient();
    private final Preferences preferences = Preferences.userNodeForPackage(PinterestDownloaderApp.class);

    private PasswordField tokenPassword;
    private TextField tokenVisible;
    private SplitMenuButton tokenActionButton;
    private ComboBox<Board> boardBox;
    private TextField directoryField;
    private CheckBox includeVideo;
    private CheckBox overwrite;
    private Button refreshButton;
    private Button downloadButton;
    private ProgressBar progressBar;
    private Label statusLabel;
    private Label progressLabel;
    private TextArea logArea;

    private Scene scene;
    private Language currentLanguage;
    private Theme currentTheme;

    private Label subtitleLabel;
    private Label tokenLabel;
    private Label boardLabel;
    private Label folderLabel;
    private Label optionsLabel;
    private Label languageLabel;
    private Label themeLabel;
    private Button chooseDirectoryButton;
    private ComboBox<Language> languageBox;
    private ComboBox<Theme> themeBox;

    private static String tr(String key, Object... args) {
        return I18n.text(key, args);
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    private static void pasteFromClipboard(TextInputControl field) {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (!clipboard.hasString()) {
            return;
        }
        field.replaceSelection(clipboard.getString());
    }

    private static void startTask(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static void warning(String title, String message) {
        alert(Alert.AlertType.WARNING, title, message);
    }

    private static void error(String title, String message) {
        alert(Alert.AlertType.ERROR, title, message);
    }

    private static void information(String title, String message) {
        alert(Alert.AlertType.INFORMATION, title, message);
    }

    private static void alert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * JavaFX bootstrap. For IDE/Maven startup use {@link Launcher} as the
     * main class instead of launching this Application subclass directly.
     */
    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        currentLanguage = Language.fromCode(
                preferences.get(PREF_LANGUAGE, Language.systemDefault().code())
        );
        currentTheme = Theme.fromCode(
                preferences.get(PREF_THEME, Theme.DARK.code())
        );
        I18n.setLanguage(currentLanguage);

        tokenPassword = new PasswordField();
        tokenVisible = new TextField();
        tokenPassword.getStyleClass().add("form-control");
        tokenVisible.getStyleClass().add("form-control");
        tokenVisible.textProperty().bindBidirectional(tokenPassword.textProperty());

        String envToken = System.getenv("PINTEREST_ACCESS_TOKEN");
        if (envToken != null && !envToken.isBlank()) {
            tokenPassword.setText(envToken.trim());
        } else {
            String cachedToken = preferences.get(PREF_OAUTH_TOKEN, "");
            if (!cachedToken.isBlank()) {
                tokenPassword.setText(cachedToken);
            }
        }

        installEditingSupport(tokenPassword);
        installEditingSupport(tokenVisible);

        MenuItem forgetTokenItem = new MenuItem();
        forgetTokenItem.setOnAction(e -> forgetCachedToken());

        tokenActionButton = new SplitMenuButton(forgetTokenItem);
        tokenActionButton.getStyleClass().addAll("secondary-button", "form-action-button");
        tokenActionButton.setOnAction(e -> updateTokenVisibility(!tokenVisible.isVisible()));

        StackPane tokenStack = new StackPane(tokenPassword, tokenVisible);
        tokenStack.getStyleClass().add("field-stack");
        tokenVisible.setVisible(false);
        tokenVisible.setManaged(false);
        HBox.setHgrow(tokenStack, Priority.ALWAYS);

        boardBox = new ComboBox<>();
        boardBox.getStyleClass().add("form-control");
        boardBox.setMaxWidth(Double.MAX_VALUE);
        boardBox.setCellFactory(list -> boardCell());
        boardBox.setButtonCell(boardCell());

        refreshButton = new Button();
        refreshButton.getStyleClass().addAll("secondary-button", "form-action-button");
        refreshButton.setOnAction(e -> refreshBoards());

        directoryField = new TextField(
                Path.of(System.getProperty("user.home"), "Downloads", "Pinterest").toString()
        );
        directoryField.getStyleClass().add("form-control");
        installEditingSupport(directoryField);

        chooseDirectoryButton = new Button();
        chooseDirectoryButton.getStyleClass().addAll("secondary-button", "form-action-button");
        chooseDirectoryButton.setOnAction(e -> chooseDirectory(stage));

        includeVideo = new CheckBox();
        overwrite = new CheckBox();

        GridPane form = new GridPane();
        form.getStyleClass().addAll("card", "form-card");
        form.setHgap(14);
        form.setVgap(12);
        form.setPadding(new Insets(22));

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(145);
        labelCol.setPrefWidth(145);
        labelCol.setMaxWidth(145);

        ColumnConstraints inputCol = new ColumnConstraints();
        inputCol.setHgrow(Priority.ALWAYS);
        inputCol.setFillWidth(true);

        ColumnConstraints buttonCol = new ColumnConstraints();
        buttonCol.setMinWidth(128);
        buttonCol.setPrefWidth(128);
        buttonCol.setMaxWidth(128);

        form.getColumnConstraints().addAll(labelCol, inputCol, buttonCol);

        tokenLabel = fieldLabel("");
        boardLabel = fieldLabel("");
        folderLabel = fieldLabel("");
        optionsLabel = fieldLabel("");

        GridPane.setValignment(tokenLabel, javafx.geometry.VPos.CENTER);
        GridPane.setValignment(boardLabel, javafx.geometry.VPos.CENTER);
        GridPane.setValignment(folderLabel, javafx.geometry.VPos.CENTER);
        GridPane.setValignment(optionsLabel, javafx.geometry.VPos.CENTER);

        form.add(tokenLabel, 0, 0);
        form.add(tokenStack, 1, 0);
        form.add(tokenActionButton, 2, 0);

        form.add(boardLabel, 0, 1);
        form.add(boardBox, 1, 1);
        form.add(refreshButton, 2, 1);

        form.add(folderLabel, 0, 2);
        form.add(directoryField, 1, 2);
        form.add(chooseDirectoryButton, 2, 2);

        HBox options = new HBox(26, includeVideo, overwrite);
        options.getStyleClass().add("options-panel");
        options.setAlignment(Pos.CENTER_LEFT);
        options.setMaxWidth(Double.MAX_VALUE);

        form.add(optionsLabel, 0, 3);
        form.add(options, 1, 3, 2, 1);

        statusLabel = new Label();
        statusLabel.getStyleClass().add("status-label");

        progressLabel = new Label("0 / 0");
        progressLabel.getStyleClass().add("muted-label");

        HBox progressHeader = new HBox(statusLabel, spacer(), progressLabel);
        progressHeader.setAlignment(Pos.CENTER_LEFT);

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(false);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        VBox workCard = new VBox(12, progressHeader, progressBar, logArea);
        workCard.getStyleClass().add("card");
        workCard.setPadding(new Insets(20));
        VBox.setVgrow(workCard, Priority.ALWAYS);

        downloadButton = new Button();
        downloadButton.getStyleClass().add("primary-button");
        downloadButton.setOnAction(e -> downloadSelectedBoard());

        HBox footer = new HBox(downloadButton);
        footer.setAlignment(Pos.CENTER_RIGHT);

        Label title = new Label("Pinterest Board Downloader");
        title.getStyleClass().add("title");

        subtitleLabel = new Label();
        subtitleLabel.getStyleClass().add("subtitle");
        subtitleLabel.setWrapText(true);

        languageLabel = new Label();
        languageLabel.getStyleClass().add("settings-label");

        themeLabel = new Label();
        themeLabel.getStyleClass().add("settings-label");

        languageBox = new ComboBox<>();
        languageBox.getStyleClass().add("settings-control");
        languageBox.getItems().setAll(Language.values());
        languageBox.setCellFactory(list -> languageCell());
        languageBox.setButtonCell(languageCell());
        languageBox.getSelectionModel().select(currentLanguage);
        languageBox.setMinWidth(140);
        languageBox.setPrefWidth(140);
        languageBox.setMaxWidth(140);
        languageBox.setMinHeight(40);
        languageBox.setPrefHeight(40);
        languageBox.setMaxHeight(40);

        themeBox = new ComboBox<>();
        themeBox.getStyleClass().add("settings-control");
        themeBox.getItems().setAll(Theme.values());
        updateThemeCells();
        themeBox.getSelectionModel().select(currentTheme);
        themeBox.setMinWidth(140);
        themeBox.setPrefWidth(140);
        themeBox.setMaxWidth(140);
        themeBox.setMinHeight(40);
        themeBox.setPrefHeight(40);
        themeBox.setMaxHeight(40);

        languageBox.setOnAction(e -> {
            Language selected = languageBox.getSelectionModel().getSelectedItem();
            if (selected == null || selected == currentLanguage) {
                return;
            }

            currentLanguage = selected;
            I18n.setLanguage(selected);
            preferences.put(PREF_LANGUAGE, selected.code());
            flushPreferences();
            applyLanguage();
        });

        themeBox.setOnAction(e -> {
            Theme selected = themeBox.getSelectionModel().getSelectedItem();
            if (selected == null || selected == currentTheme) {
                return;
            }

            currentTheme = selected;
            preferences.put(PREF_THEME, selected.code());
            flushPreferences();
            applyTheme();
        });

        VBox languageSetting = new VBox(6, languageLabel, languageBox);
        languageSetting.getStyleClass().add("setting-item");
        languageSetting.setAlignment(Pos.TOP_LEFT);

        VBox themeSetting = new VBox(6, themeLabel, themeBox);
        themeSetting.getStyleClass().add("setting-item");
        themeSetting.setAlignment(Pos.TOP_LEFT);

        HBox settings = new HBox(12, languageSetting, themeSetting);
        settings.setAlignment(Pos.CENTER_RIGHT);
        settings.getStyleClass().add("settings-bar");

        VBox headerLeft = new VBox(4, title, subtitleLabel);
        HBox.setHgrow(headerLeft, Priority.ALWAYS);

        HBox header = new HBox(20, headerLeft, settings);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(16, header, form, workCard, footer);
        root.setPadding(new Insets(24));

        scene = new Scene(root, 1080, 740);
        applyTheme();

        stage.setTitle("Pinterest Board Downloader");
        try (InputStream iconStream = Objects.requireNonNull(
                PinterestDownloaderApp.class.getResourceAsStream("/pinterest.png"),
                "Application icon is missing"
        )) {
            stage.getIcons().add(new Image(iconStream));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load application icon", ex);
        }
        stage.setMinWidth(920);
        stage.setMinHeight(660);
        stage.setScene(scene);

        applyLanguage();

        if (token().isBlank()) {
            logArea.setText(tr("log.token.enter") + System.lineSeparator());
        } else if (envToken != null && !envToken.isBlank()) {
            logArea.setText(tr("log.token.env") + System.lineSeparator());
        } else {
            logArea.setText(tr("log.token.cache") + System.lineSeparator());
        }

        stage.show();
        Platform.runLater(tokenPassword::requestFocus);
    }

    private ListCell<Board> boardCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Board board, boolean empty) {
                super.updateItem(board, empty);
                setText(empty || board == null ? null : board.displayName());
            }
        };
    }

    private ListCell<Language> languageCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Language language, boolean empty) {
                super.updateItem(language, empty);
                if (empty || language == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                setText(language.displayName());
                setGraphic(languageFlag(language));
                setGraphicTextGap(8);
            }
        };
    }

    private static Canvas languageFlag(Language language) {
        double width = 24;
        double height = 16;
        Canvas flag = new Canvas(width, height);
        flag.setMouseTransparent(true);

        GraphicsContext graphics = flag.getGraphicsContext2D();
        switch (language) {
            case RU -> {
                graphics.setFill(Color.WHITE);
                graphics.fillRect(0, 0, width, height / 3);
                graphics.setFill(Color.web("#0039A6"));
                graphics.fillRect(0, height / 3, width, height / 3);
                graphics.setFill(Color.web("#D52B1E"));
                graphics.fillRect(0, height * 2 / 3, width, height / 3);
            }
            case EN -> {
                graphics.setFill(Color.web("#012169"));
                graphics.fillRect(0, 0, width, height);

                graphics.setStroke(Color.WHITE);
                graphics.setLineWidth(5);
                graphics.strokeLine(0, 0, width, height);
                graphics.strokeLine(width, 0, 0, height);

                graphics.setStroke(Color.web("#C8102E"));
                graphics.setLineWidth(2.2);
                graphics.strokeLine(0, 0, width, height);
                graphics.strokeLine(width, 0, 0, height);

                graphics.setFill(Color.WHITE);
                graphics.fillRect(0, height / 2 - 3, width, 6);
                graphics.fillRect(width / 2 - 3, 0, 6, height);
                graphics.setFill(Color.web("#C8102E"));
                graphics.fillRect(0, height / 2 - 1.5, width, 3);
                graphics.fillRect(width / 2 - 1.5, 0, 3, height);
            }
        }

        graphics.setStroke(Color.rgb(0, 0, 0, 0.35));
        graphics.setLineWidth(1);
        graphics.strokeRect(0.5, 0.5, width - 1, height - 1);
        return flag;
    }

    private ListCell<Theme> themeCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Theme theme, boolean empty) {
                super.updateItem(theme, empty);
                setText(empty || theme == null ? null : tr(theme.messageKey()));
            }
        };
    }

    private void updateThemeCells() {
        themeBox.setCellFactory(list -> themeCell());
        themeBox.setButtonCell(themeCell());
    }

    private void applyTheme() {
        if (scene == null) {
            return;
        }

        String baseCss = Objects.requireNonNull(
                getClass().getResource("/app.css"),
                "app.css not found"
        ).toExternalForm();

        String themeCss = Objects.requireNonNull(
                getClass().getResource(currentTheme == Theme.DARK
                        ? "/theme-dark.css"
                        : "/theme-light.css"),
                "theme css not found"
        ).toExternalForm();

        scene.getStylesheets().setAll(baseCss, themeCss);
    }

    private void applyLanguage() {
        tokenPassword.setPromptText(tr("prompt.token"));
        tokenVisible.setPromptText(tr("prompt.token"));
        boardBox.setPromptText(tr("prompt.board"));

        updateTokenActionText();
        if (!tokenActionButton.getItems().isEmpty()) {
            tokenActionButton.getItems().get(0).setText(tr("action.forgetToken"));
        }
        refreshButton.setText(tr("action.refresh"));
        chooseDirectoryButton.setText(tr("action.choose"));
        downloadButton.setText(tr("action.download"));

        includeVideo.setText(tr("option.video"));
        overwrite.setText(tr("option.overwrite"));

        tokenLabel.setText(tr("label.oauth"));
        boardLabel.setText(tr("label.board"));
        folderLabel.setText(tr("label.folder"));
        optionsLabel.setText(tr("label.options"));
        languageLabel.setText(tr("label.language"));
        themeLabel.setText(tr("label.theme"));

        if (!progressBar.isIndeterminate() && progressBar.getProgress() <= 0) {
            statusLabel.setText(tr("status.ready"));
        }

        tokenPassword.setContextMenu(createEditingContextMenu(tokenPassword));
        tokenVisible.setContextMenu(createEditingContextMenu(tokenVisible));
        directoryField.setContextMenu(createEditingContextMenu(directoryField));

        updateThemeCells();
        themeBox.getSelectionModel().select(currentTheme);
    }

    private ContextMenu createEditingContextMenu(TextInputControl field) {
        MenuItem cut = new MenuItem(tr("context.cut"));
        cut.setOnAction(e -> field.cut());

        MenuItem copy = new MenuItem(tr("context.copy"));
        copy.setOnAction(e -> field.copy());

        MenuItem paste = new MenuItem(tr("context.paste"));
        paste.setOnAction(e -> pasteFromClipboard(field));

        MenuItem selectAll = new MenuItem(tr("context.selectAll"));
        selectAll.setOnAction(e -> field.selectAll());

        return new ContextMenu(
                cut,
                copy,
                paste,
                new SeparatorMenuItem(),
                selectAll
        );
    }

    private void flushPreferences() {
        try {
            preferences.flush();
        } catch (BackingStoreException ex) {
            appendLog(tr("log.preferences.warning", ex.getMessage()));
        }
    }

    private void updateTokenVisibility(boolean visible) {
        if (visible) {
            tokenVisible.setVisible(true);
            tokenVisible.setManaged(true);
            tokenPassword.setVisible(false);
            tokenPassword.setManaged(false);
            tokenVisible.requestFocus();
            tokenVisible.positionCaret(tokenVisible.getText().length());
        } else {
            tokenPassword.setVisible(true);
            tokenPassword.setManaged(true);
            tokenVisible.setVisible(false);
            tokenVisible.setManaged(false);
            tokenPassword.requestFocus();
            tokenPassword.positionCaret(tokenPassword.getText().length());
        }

        updateTokenActionText();
    }

    private void updateTokenActionText() {
        if (tokenActionButton == null) {
            return;
        }
        tokenActionButton.setText(
                tokenVisible != null && tokenVisible.isVisible()
                        ? tr("action.hideToken")
                        : tr("action.showToken")
        );
    }

    /**
     * JavaFX уже поддерживает стандартные сочетания, но здесь они
     * продублированы через KeyCode и Clipboard API. Это исключает проблемы,
     * которые были в Tkinter при русской раскладке.
     */
    private void installEditingSupport(TextInputControl field) {
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown()) {
                if (event.getCode() == KeyCode.A) {
                    field.selectAll();
                    event.consume();
                    return;
                }
                if (event.getCode() == KeyCode.C) {
                    field.copy();
                    event.consume();
                    return;
                }
                if (event.getCode() == KeyCode.X) {
                    field.cut();
                    event.consume();
                    return;
                }
                if (event.getCode() == KeyCode.V) {
                    pasteFromClipboard(field);
                    event.consume();
                    return;
                }
            }

            if (event.isShiftDown() && event.getCode() == KeyCode.INSERT) {
                pasteFromClipboard(field);
                event.consume();
            }
        });

        field.setContextMenu(createEditingContextMenu(field));
    }

    private void cacheToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        preferences.put(PREF_OAUTH_TOKEN, token.trim());
        try {
            preferences.flush();
        } catch (BackingStoreException ex) {
            appendLog(tr("log.token.cacheWriteWarning", ex.getMessage()));
        }
    }

    private void forgetCachedToken() {
        preferences.remove(PREF_OAUTH_TOKEN);

        try {
            preferences.flush();
        } catch (BackingStoreException ex) {
            appendLog(tr("log.token.cacheClearWarning", ex.getMessage()));
        }

        tokenPassword.clear();
        tokenVisible.clear();
        boardBox.getItems().clear();
        progressBar.setProgress(0);
        progressLabel.setText("0 / 0");
        statusLabel.setText(tr("status.tokenForgotten"));
        appendLog(tr("log.token.cleared"));
        tokenPassword.requestFocus();
    }

    private void chooseDirectory(Stage stage) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(tr("dialog.chooseFolder"));

        try {
            Path current = Path.of(directoryField.getText().trim());
            if (Files.isDirectory(current)) {
                chooser.setInitialDirectory(current.toFile());
            }
        } catch (Exception ignored) {
        }

        var selected = chooser.showDialog(stage);
        if (selected != null) {
            directoryField.setText(selected.getAbsolutePath());
        }
    }

    private String token() {
        return tokenPassword.getText() == null ? "" : tokenPassword.getText().trim();
    }

    private void refreshBoards() {
        String token = token();
        if (token.isBlank()) {
            warning(tr("dialog.noToken.title"), tr("dialog.noToken.message"));
            return;
        }

        setBusy(true);
        statusLabel.setText(tr("status.loadingBoards"));
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        appendLog(tr("log.boards.request"));

        Task<List<Board>> task = new Task<>() {
            @Override
            protected List<Board> call() throws Exception {
                return client.getBoards(token);
            }
        };

        task.setOnSucceeded(e -> {
            cacheToken(token);

            List<Board> boards = task.getValue();
            boardBox.getItems().setAll(boards);

            if (!boards.isEmpty()) {
                boardBox.getSelectionModel().selectFirst();
                statusLabel.setText(tr("status.boardsFound", boards.size()));
                appendLog(tr("log.boards.received", boards.size()));
            } else {
                statusLabel.setText(tr("status.noBoards"));
                appendLog(tr("log.boards.none"));
            }

            progressBar.setProgress(0);
            setBusy(false);
        });

        task.setOnFailed(e -> {
            progressBar.setProgress(0);
            setBusy(false);
            handleError(task.getException());
        });

        startTask(task, "pinterest-board-refresh");
    }

    private void downloadSelectedBoard() {
        String token = token();
        if (token.isBlank()) {
            warning(tr("dialog.noToken.title"), tr("dialog.noToken.message"));
            return;
        }

        Board board = boardBox.getSelectionModel().getSelectedItem();
        if (board == null) {
            warning(tr("dialog.noBoard.title"), tr("dialog.noBoard.message"));
            return;
        }

        String rawDirectory = directoryField.getText() == null
                ? ""
                : directoryField.getText().trim();

        if (rawDirectory.isBlank()) {
            warning(tr("dialog.noFolder.title"), tr("dialog.noFolder.message"));
            return;
        }

        final Path outputRoot;
        try {
            outputRoot = Path.of(rawDirectory).toAbsolutePath().normalize();
            Files.createDirectories(outputRoot);
        } catch (Exception ex) {
            error(tr("dialog.folderError.title"), tr("dialog.folderError.message", ex.getMessage()));
            return;
        }

        boolean overwriteEnabled = overwrite.isSelected();
        boolean videoEnabled = includeVideo.isSelected();

        appendLog("");
        appendLog(tr("log.board", board.name()));
        appendLog(tr("log.folder", outputRoot));

        progressBar.setProgress(0);
        progressLabel.setText("0 / 0");
        statusLabel.setText(tr("status.preparing"));
        setBusy(true);

        Task<DownloadResult> task = new Task<>() {
            @Override
            protected DownloadResult call() throws Exception {
                Consumer<String> logger = message ->
                        Platform.runLater(() -> appendLog(message));

                BiConsumer<Integer, Integer> progress = (current, total) ->
                        Platform.runLater(() -> {
                            progressBar.setProgress(total == 0 ? 1.0 : (double) current / total);
                            progressLabel.setText(current + " / " + total);
                            statusLabel.setText(tr("status.downloading", current, total));
                        });

                return client.downloadBoard(
                        token,
                        board,
                        outputRoot,
                        videoEnabled,
                        overwriteEnabled,
                        logger,
                        progress
                );
            }
        };

        task.setOnSucceeded(e -> {
            cacheToken(token);

            DownloadResult result = task.getValue();
            progressBar.setProgress(1);
            statusLabel.setText(tr("status.completed"));
            setBusy(false);

            appendLog("");
            appendLog(tr(
                    "log.done",
                    result.downloaded(),
                    result.skipped(),
                    result.failed()
            ));

            information(
                    tr("dialog.done.title"),
                    tr(
                            "dialog.done.message",
                            result.downloaded(),
                            result.skipped(),
                            result.failed(),
                            result.folder()
                    )
            );
        });

        task.setOnFailed(e -> {
            progressBar.setProgress(0);
            setBusy(false);
            handleError(task.getException());
        });

        startTask(task, "pinterest-board-download");
    }

    private void setBusy(boolean busy) {
        refreshButton.setDisable(busy);
        downloadButton.setDisable(busy);
        tokenPassword.setDisable(busy);
        tokenVisible.setDisable(busy);
        boardBox.setDisable(busy);
        directoryField.setDisable(busy);
        includeVideo.setDisable(busy);
        overwrite.setDisable(busy);
        tokenActionButton.setDisable(busy);
        languageBox.setDisable(busy);
        themeBox.setDisable(busy);
    }

    private void appendLog(String text) {
        logArea.appendText(text.stripTrailing() + System.lineSeparator());
        logArea.positionCaret(logArea.getText().length());
    }

    private void handleError(Throwable throwable) {
        String message = throwable == null ? tr("error.unknown") : throwable.getMessage();
        statusLabel.setText(tr("status.error"));
        appendLog(tr("log.error", message));
        error(tr("dialog.error.title"), message);
    }

    private enum Language {
        RU("ru", Locale.forLanguageTag("ru"), "Русский"),
        EN("en", Locale.ENGLISH, "English");

        private final String code;
        private final Locale locale;
        private final String displayName;

        Language(String code, Locale locale, String displayName) {
            this.code = code;
            this.locale = locale;
            this.displayName = displayName;
        }

        static Language fromCode(String code) {
            if (code != null) {
                for (Language language : values()) {
                    if (language.code.equalsIgnoreCase(code)) {
                        return language;
                    }
                }
            }
            return systemDefault();
        }

        static Language systemDefault() {
            return "ru".equalsIgnoreCase(Locale.getDefault().getLanguage())
                    ? RU
                    : EN;
        }

        String code() {
            return code;
        }

        Locale locale() {
            return locale;
        }

        String displayName() {
            return displayName;
        }
    }

    private enum Theme {
        DARK("dark", "theme.dark"),
        LIGHT("light", "theme.light");

        private final String code;
        private final String messageKey;

        Theme(String code, String messageKey) {
            this.code = code;
            this.messageKey = messageKey;
        }

        static Theme fromCode(String code) {
            if (code != null) {
                for (Theme theme : values()) {
                    if (theme.code.equalsIgnoreCase(code)) {
                        return theme;
                    }
                }
            }
            return DARK;
        }

        String code() {
            return code;
        }

        String messageKey() {
            return messageKey;
        }
    }

    private static final class I18n {
        private static final String BUNDLE_NAME = "i18n.messages";

        /*
         * No-default-locale fallback is intentional:
         * RU -> messages_ru.properties -> messages.properties
         * EN -> messages_en.properties -> messages.properties
         *
         * It prevents an unrelated OS locale from unexpectedly becoming
         * another fallback layer.
         */
        private static final ResourceBundle.Control CONTROL =
                ResourceBundle.Control.getNoFallbackControl(
                        ResourceBundle.Control.FORMAT_DEFAULT
                );

        private static volatile Locale locale = Locale.ENGLISH;
        private static volatile ResourceBundle bundle = loadBundle(locale);
        private static volatile ResourceBundle baseBundle = loadBundle(Locale.ROOT);

        private I18n() {
        }

        static void setLanguage(Language language) {
            locale = language.locale();

            /*
             * Important for IDE/dev builds: IntelliJ/Maven can otherwise keep
             * a previously loaded PropertyResourceBundle in the JVM cache.
             */
            ResourceBundle.clearCache(PinterestDownloaderApp.class.getClassLoader());

            bundle = loadBundle(locale);
            baseBundle = loadBundle(Locale.ROOT);
        }

        static String text(String key, Object... args) {
            String template = lookup(key);

            if (args == null || args.length == 0) {
                return template;
            }

            try {
                return String.format(locale, template, args);
            } catch (IllegalFormatException ex) {
                /*
                 * A translation typo must not crash the whole JavaFX app.
                 * Showing the untranslated template is preferable.
                 */
                return template;
            }
        }

        private static String lookup(String key) {
            if (bundle != null && bundle.containsKey(key)) {
                return bundle.getString(key);
            }

            if (baseBundle != null && baseBundle.containsKey(key)) {
                return baseBundle.getString(key);
            }

            /*
             * Last-resort fallback. A missing translation key is visible in
             * the UI/log but cannot abort Application.start().
             */
            return "[" + key + "]";
        }

        private static ResourceBundle loadBundle(Locale targetLocale) {
            try {
                return ResourceBundle.getBundle(
                        BUNDLE_NAME,
                        targetLocale,
                        PinterestDownloaderApp.class.getClassLoader(),
                        CONTROL
                );
            } catch (MissingResourceException ex) {
                return null;
            }
        }
    }

    record Board(String id, String name, String privacy, String displayName) {
    }

    record DownloadResult(int downloaded, int skipped, int failed, Path folder) {
    }

    static final class PinterestClient {
        private static final String API_BASE = "https://api.pinterest.com/v5";
        private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
        private static final int MAX_RETRIES = 5;
        private static final int PAGE_SIZE = 100;

        private static final Set<String> VIDEO_EXTENSIONS =
                Set.of(".mp4", ".mov", ".webm", ".m3u8");

        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        private final ObjectMapper json = new ObjectMapper();

        private static String detectExtension(
                HttpResponse<?> response,
                String url
        ) {
            try {
                String path = URI.create(url).getPath();
                int slash = path.lastIndexOf('/');
                int dot = path.lastIndexOf('.');
                if (dot > slash) {
                    String extension = path.substring(dot).toLowerCase(Locale.ROOT);
                    if (extension.length() >= 2 && extension.length() <= 6) {
                        return extension;
                    }
                }
            } catch (Exception ignored) {
            }

            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("")
                    .split(";", 2)[0]
                    .trim()
                    .toLowerCase(Locale.ROOT);

            return switch (contentType) {
                case "image/jpeg", "image/jpg" -> ".jpg";
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                case "image/gif" -> ".gif";
                case "video/mp4" -> ".mp4";
                case "video/webm" -> ".webm";
                case "video/quicktime" -> ".mov";
                default -> ".bin";
            };
        }

        private static void sleepBackoff(
                HttpResponse<?> response,
                int attempt
        ) throws InterruptedException {
            Optional<String> retryAfter = response.headers().firstValue("Retry-After");

            long seconds;
            if (retryAfter.isPresent()) {
                try {
                    seconds = Math.max(1, Long.parseLong(retryAfter.get()));
                } catch (NumberFormatException ex) {
                    seconds = 1L << attempt;
                }
            } else {
                seconds = 1L << attempt;
            }

            Thread.sleep(Math.min(seconds, 60) * 1000L);
        }

        private static String sanitizeFilename(
                String value,
                String fallback
        ) {
            String cleaned = value == null ? "" : value
                    .replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_")
                    .replaceAll("\\s+", " ")
                    .trim()
                    .replaceAll("[. ]+$", "");

            if (cleaned.length() > 100) {
                cleaned = cleaned.substring(0, 100).trim();
            }

            return cleaned.isBlank() ? fallback : cleaned;
        }

        private static String nullableText(JsonNode node) {
            if (node == null || node.isNull()) {
                return null;
            }
            String value = node.asText("");
            return value.isBlank() ? null : value;
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return "";
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }

        private static String encodePath(String value) {
            return value.replace("/", "%2F");
        }

        private static String abbreviate(String text, int max) {
            if (text == null) {
                return "";
            }
            return text.length() <= max ? text : text.substring(0, max);
        }

        List<Board> getBoards(String token) throws IOException, InterruptedException {
            List<Board> raw = new ArrayList<>();
            String bookmark = null;

            do {
                String path = "/boards?page_size=" + PAGE_SIZE
                        + (bookmark == null ? "" : "&bookmark=" + encode(bookmark));

                JsonNode response = apiGet(token, path);
                JsonNode items = response.path("items");

                if (items.isArray()) {
                    for (JsonNode item : items) {
                        String id = item.path("id").asText("");
                        String name = item.path("name").asText(tr("board.untitled"));
                        String privacy = item.path("privacy").asText("");
                        if (!id.isBlank()) {
                            raw.add(new Board(id, name, privacy, name));
                        }
                    }
                }

                bookmark = nullableText(response.get("bookmark"));
            } while (bookmark != null);

            raw.sort(Comparator.comparing(
                    Board::name,
                    String.CASE_INSENSITIVE_ORDER
            ));

            Map<String, Long> counts = new HashMap<>();
            for (Board board : raw) {
                counts.merge(board.name(), 1L, Long::sum);
            }

            List<Board> result = new ArrayList<>(raw.size());
            for (Board board : raw) {
                String displayName = counts.getOrDefault(board.name(), 0L) > 1
                        ? board.name() + "  ·  " + board.id()
                        : board.name();
                result.add(new Board(
                        board.id(),
                        board.name(),
                        board.privacy(),
                        displayName
                ));
            }
            return result;
        }

        DownloadResult downloadBoard(
                String token,
                Board board,
                Path outputRoot,
                boolean includeVideo,
                boolean overwrite,
                Consumer<String> logger,
                BiConsumer<Integer, Integer> progress
        ) throws IOException, InterruptedException {

            Path boardDir = outputRoot.resolve(
                    sanitizeFilename(board.name(), board.id())
            );
            Files.createDirectories(boardDir);

            Path metadata = boardDir.resolve("metadata.jsonl");
            List<JsonNode> pins = getAllPins(token, board.id());
            int total = pins.size();

            progress.accept(0, total);

            AtomicInteger downloaded = new AtomicInteger();
            AtomicInteger skipped = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();

            for (int i = 0; i < total; i++) {
                int index = i + 1;
                JsonNode pin = pins.get(i);
                String pinId = pin.path("id").asText("unknown-" + index);

                if (!overwrite) {
                    Path existing = findExistingPinFile(boardDir, pinId);
                    if (existing != null) {
                        skipped.incrementAndGet();
                        logger.accept(tr(
                                "client.skipExisting",
                                index,
                                total,
                                existing.getFileName()
                        ));
                        appendMetadata(metadata, metadataRecord(
                                pinId,
                                "already_exists",
                                existing.getFileName().toString(),
                                null,
                                null,
                                pin
                        ));
                        progress.accept(index, total);
                        continue;
                    }
                }

                String title = firstNonBlank(
                        pin.path("title").asText(""),
                        pin.path("description").asText(""),
                        "pin"
                );

                String mediaUrl = selectMediaUrl(pin, includeVideo);
                if (mediaUrl == null) {
                    failed.incrementAndGet();
                    logger.accept(tr(
                            "client.mediaNotFound",
                            index,
                            total,
                            pinId
                    ));
                    appendMetadata(metadata, metadataRecord(
                            pinId,
                            "media_url_not_found",
                            null,
                            null,
                            null,
                            pin
                    ));
                    progress.accept(index, total);
                    continue;
                }

                Path basePath = boardDir.resolve(
                        pinId + "__" + sanitizeFilename(title, "pin")
                );

                try {
                    DownloadedFile file = downloadFile(
                            mediaUrl,
                            basePath,
                            overwrite
                    );

                    if (file.downloaded()) {
                        downloaded.incrementAndGet();
                        logger.accept(tr(
                                "client.downloaded",
                                index,
                                total,
                                file.path().getFileName()
                        ));
                    } else {
                        skipped.incrementAndGet();
                        logger.accept(tr(
                                "client.skipExisting",
                                index,
                                total,
                                file.path().getFileName()
                        ));
                    }

                    appendMetadata(metadata, metadataRecord(
                            pinId,
                            file.downloaded() ? "downloaded" : "already_exists",
                            file.path().getFileName().toString(),
                            mediaUrl,
                            null,
                            pin
                    ));
                } catch (Exception ex) {
                    failed.incrementAndGet();
                    logger.accept(tr(
                            "client.downloadFailed",
                            index,
                            total,
                            pinId,
                            ex.getMessage()
                    ));
                    appendMetadata(metadata, metadataRecord(
                            pinId,
                            "download_failed",
                            null,
                            mediaUrl,
                            ex.getMessage(),
                            pin
                    ));
                }

                progress.accept(index, total);
            }

            return new DownloadResult(
                    downloaded.get(),
                    skipped.get(),
                    failed.get(),
                    boardDir.toAbsolutePath().normalize()
            );
        }

        private List<JsonNode> getAllPins(
                String token,
                String boardId
        ) throws IOException, InterruptedException {
            List<JsonNode> result = new ArrayList<>();
            String bookmark = null;

            do {
                String path = "/boards/" + encodePath(boardId)
                        + "/pins?page_size=" + PAGE_SIZE
                        + (bookmark == null ? "" : "&bookmark=" + encode(bookmark));

                JsonNode response = apiGet(token, path);
                JsonNode items = response.path("items");
                if (items.isArray()) {
                    items.forEach(result::add);
                }

                bookmark = nullableText(response.get("bookmark"));
            } while (bookmark != null);

            return result;
        }

        private JsonNode apiGet(
                String token,
                String path
        ) throws IOException, InterruptedException {

            URI uri = URI.create(API_BASE + path);

            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/json")
                        .header("User-Agent", "PinterestBoardDownloaderJavaFX/25.0.4")
                        .GET()
                        .build();

                HttpResponse<String> response = http.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );

                int status = response.statusCode();

                if (status == 429 || status >= 500) {
                    if (attempt == MAX_RETRIES - 1) {
                        throw new IOException(
                                tr(
                                        "client.apiHttpError",
                                        status,
                                        abbreviate(response.body(), 600)
                                )
                        );
                    }
                    sleepBackoff(response, attempt);
                    continue;
                }

                if (status == 401) {
                    throw new IOException(
                            tr("client.http401")
                    );
                }

                if (status == 403) {
                    throw new IOException(
                            tr("client.http403")
                    );
                }

                if (status < 200 || status >= 300) {
                    throw new IOException(
                            tr(
                                    "client.apiHttpError",
                                    status,
                                    abbreviate(response.body(), 1000)
                            )
                    );
                }

                return json.readTree(response.body());
            }

            throw new IOException(tr("client.apiFailed"));
        }

        private DownloadedFile downloadFile(
                String url,
                Path basePath,
                boolean overwrite
        ) throws IOException, InterruptedException {

            URI uri = URI.create(url);

            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Accept", "*/*")
                        .header("Referer", "https://www.pinterest.com/")
                        .header("User-Agent", "PinterestBoardDownloaderJavaFX/25.0.4")
                        .GET()
                        .build();

                HttpResponse<InputStream> response = http.send(
                        request,
                        HttpResponse.BodyHandlers.ofInputStream()
                );

                int status = response.statusCode();

                if (status == 429 || status >= 500) {
                    response.body().close();
                    if (attempt == MAX_RETRIES - 1) {
                        throw new IOException(tr("client.mediaHttpError", status));
                    }
                    sleepBackoff(response, attempt);
                    continue;
                }

                if (status < 200 || status >= 300) {
                    response.body().close();
                    throw new IOException(tr("client.mediaHttpError", status));
                }

                String extension = detectExtension(response, url);
                Path target = Path.of(basePath.toString() + extension);

                if (!overwrite && Files.exists(target)) {
                    response.body().close();
                    return new DownloadedFile(target, false);
                }

                Path temp = Path.of(basePath + ".part");
                Files.deleteIfExists(temp);

                try (InputStream input = response.body();
                     OutputStream output = Files.newOutputStream(
                             temp,
                             StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING,
                             StandardOpenOption.WRITE
                     )) {
                    input.transferTo(output);
                } catch (Exception ex) {
                    Files.deleteIfExists(temp);
                    throw ex;
                }

                if (overwrite) {
                    Files.move(
                            temp,
                            target,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                    return new DownloadedFile(target, true);
                }

                /*
                 * Жёсткая защита от перезаписи.
                 * CREATE_NEW не даст открыть уже существующий target.
                 */
                try (InputStream input = Files.newInputStream(temp);
                     OutputStream output = Files.newOutputStream(
                             target,
                             StandardOpenOption.CREATE_NEW,
                             StandardOpenOption.WRITE
                     )) {
                    input.transferTo(output);
                } catch (FileAlreadyExistsException ex) {
                    Files.deleteIfExists(temp);
                    return new DownloadedFile(target, false);
                }

                Files.deleteIfExists(temp);
                return new DownloadedFile(target, true);
            }

            throw new IOException(tr("client.cannotDownload", url));
        }

        private void appendMetadata(
                Path metadata,
                Map<String, Object> record
        ) throws IOException {
        }

        private Map<String, Object> metadataRecord(
                String pinId,
                String status,
                String file,
                String mediaUrl,
                String error,
                JsonNode pin
        ) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pin_id", pinId);
            result.put("status", status);
            if (file != null) {
                result.put("file", file);
            }
            if (mediaUrl != null) {
                result.put("media_url", mediaUrl);
            }
            if (error != null) {
                result.put("error", error);
            }
            result.put("pin", pin);
            return result;
        }

        private Path findExistingPinFile(
                Path boardDir,
                String pinId
        ) throws IOException {
            String prefix = pinId + "__";

            try (Stream<Path> files = Files.list(boardDir)) {
                return files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith(prefix))
                        .filter(path -> !path.getFileName().toString().endsWith(".part"))
                        .findFirst()
                        .orElse(null);
            }
        }

        private String selectMediaUrl(
                JsonNode pin,
                boolean includeVideo
        ) {
            List<MediaCandidate> candidates = new ArrayList<>();

            JsonNode media = pin.get("media");
            if (media != null) {
                walkMedia(media, "", candidates);
            }

            if (candidates.isEmpty()) {
                walkMedia(pin, "", candidates);
            }

            if (candidates.isEmpty()) {
                return null;
            }

            if (includeVideo) {
                Optional<MediaCandidate> video = candidates.stream()
                        .filter(this::isVideo)
                        .max(this::compareCandidates);

                if (video.isPresent()) {
                    return video.get().url();
                }
            }

            return candidates.stream()
                    .filter(candidate -> !isVideo(candidate))
                    .max(this::compareCandidates)
                    .map(MediaCandidate::url)
                    .orElse(null);
        }

        private void walkMedia(
                JsonNode node,
                String path,
                List<MediaCandidate> output
        ) {
            if (node == null) {
                return;
            }

            if (node.isObject()) {
                JsonNode urlNode = node.get("url");
                if (urlNode != null && urlNode.isTextual()) {
                    String url = urlNode.asText();
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        long width = node.path("width").asLong(0);
                        long height = node.path("height").asLong(0);
                        output.add(new MediaCandidate(url, width, height, path));
                    }
                }

                node.properties().forEach(entry ->
                        walkMedia(
                                entry.getValue(),
                                path + "/" + entry.getKey(),
                                output
                        )
                );
                return;
            }

            if (node.isArray()) {
                int index = 0;
                for (JsonNode child : node) {
                    walkMedia(child, path + "/" + index, output);
                    index++;
                }
            }
        }

        private boolean isVideo(MediaCandidate candidate) {
            String lowerUrl = candidate.url().toLowerCase(Locale.ROOT);
            String lowerPath = candidate.path().toLowerCase(Locale.ROOT);

            if (lowerPath.contains("video")) {
                return true;
            }

            String uriPath;
            try {
                uriPath = URI.create(lowerUrl).getPath();
            } catch (Exception ex) {
                uriPath = lowerUrl;
            }

            for (String extension : VIDEO_EXTENSIONS) {
                if (uriPath.endsWith(extension)) {
                    return true;
                }
            }
            return false;
        }

        private int compareCandidates(
                MediaCandidate left,
                MediaCandidate right
        ) {
            int original = Integer.compare(
                    originalBonus(left),
                    originalBonus(right)
            );
            if (original != 0) {
                return original;
            }

            long leftArea = left.width() * left.height();
            long rightArea = right.width() * right.height();
            return Long.compare(leftArea, rightArea);
        }

        private int originalBonus(MediaCandidate candidate) {
            String path = candidate.path().toLowerCase(Locale.ROOT);
            return path.contains("original")
                    || path.contains("/orig")
                    || path.contains("1200x")
                    ? 1
                    : 0;
        }

        record MediaCandidate(
                String url,
                long width,
                long height,
                String path
        ) {
        }

        record DownloadedFile(Path path, boolean downloaded) {
        }
    }
}
