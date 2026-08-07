package com.svyat.pinterestdownloader;

/**
 * Entry point, intentionally NOT extending javafx.application.Application.
 *
 * Running a JavaFX Application subclass directly with a plain Java run
 * configuration can make the Java launcher require javafx.graphics on the
 * module-path. Using a normal launcher class avoids that special launcher path
 * and works with JavaFX dependencies supplied by Maven/IntelliJ.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        PinterestDownloaderApp.main(args);
    }
}
