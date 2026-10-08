package se.lu.scriptloglite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static se.lu.scriptloglite.RecordingPaths.workingDirectory;

/** Persists independent last-open and last-save directories. */
final class DirectoryHistory {
    final Path file;
    final java.util.Properties properties = new java.util.Properties();
    DirectoryHistory(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException exception) { System.err.println("Cannot read directory preferences: " + exception.getMessage()); }
        }
    }
    Path directory(String operation) {
        String saved = properties.getProperty(operation);
        if (saved != null) {
            try {
                Path path = Path.of(saved);
                if (Files.isDirectory(path)) return path;
            } catch (java.nio.file.InvalidPathException ignored) { }
        }
        return workingDirectory();
    }
    void remember(String operation, Path selectedFile) {
        properties.setProperty(operation, selectedFile.toAbsolutePath().normalize().getParent().toString());
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (java.io.Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                properties.store(writer, "ScriptLogLite last open/save directories");
            }
        } catch (IOException exception) { System.err.println("Cannot save directory preferences: " + exception.getMessage()); }
    }
}
