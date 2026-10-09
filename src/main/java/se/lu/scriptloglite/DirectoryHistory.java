package se.lu.scriptloglite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static se.lu.scriptloglite.RecordingPaths.workingDirectory;

/** Persists independent last-open and last-save directories. */
public final class DirectoryHistory {
    final Path file;
    final java.util.Properties properties = new java.util.Properties();
    public DirectoryHistory(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException exception) { System.err.println("Cannot read directory preferences: " + exception.getMessage()); }
        }
    }
    public Path directory(String operation) {
        String saved = properties.getProperty(operation);
        if (saved != null) {
            try {
                Path path = Path.of(saved);
                if (Files.isDirectory(path)) return path;
            } catch (java.nio.file.InvalidPathException ignored) { }
        }
        return workingDirectory();
    }
    public void remember(String operation, Path selectedFile) {
        properties.setProperty(operation, selectedFile.toAbsolutePath().normalize().getParent().toString());
        store();
    }
    java.util.Set<LogFormat> formats() {
        java.util.Set<LogFormat> result = java.util.EnumSet.noneOf(LogFormat.class);
        for (String name : properties.getProperty("saveFormats", "JSON").split(",")) {
            try { result.add(LogFormat.valueOf(name)); } catch (IllegalArgumentException ignored) { }
        }
        return result.isEmpty() ? java.util.EnumSet.of(LogFormat.JSON) : result;
    }
    void rememberFormats(java.util.Set<LogFormat> formats) {
        if (formats.isEmpty()) throw new IllegalArgumentException("Select at least one save format");
        properties.setProperty("saveFormats", formats.stream().map(Enum::name).collect(java.util.stream.Collectors.joining(",")));
        store();
    }
    EditDialect editDialect() {
        try { return EditDialect.valueOf(properties.getProperty("editDialect", "REPLACE")); }
        catch (IllegalArgumentException ignored) { return EditDialect.REPLACE; }
    }
    void rememberEditDialect(EditDialect dialect) {
        properties.setProperty("editDialect", dialect.name()); store();
    }
    boolean idfxExtensions() { return Boolean.parseBoolean(properties.getProperty("idfxExtensions", "true")); }
    void rememberIdfxExtensions(boolean enabled) {
        properties.setProperty("idfxExtensions", Boolean.toString(enabled)); store();
    }
    private void store() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (java.io.Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                properties.store(writer, "ScriptLogLite last open/save directories");
            }
        } catch (IOException exception) { System.err.println("Cannot save directory preferences: " + exception.getMessage()); }
    }
}
