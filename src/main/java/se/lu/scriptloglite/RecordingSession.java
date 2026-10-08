package se.lu.scriptloglite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Owns a document's history, metadata, revisions, and save snapshots, independent of Swing. */
final class RecordingSession {
    private final List<LogEvent> entries = new ArrayList<>();
    private final Map<String, Object> metadata = new LinkedHashMap<>();
    boolean dirty = true;
    private java.util.Set<LogFormat> formats = java.util.EnumSet.of(LogFormat.JSON);
    synchronized void setFormats(java.util.Set<LogFormat> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("Select at least one save format");
        formats = java.util.EnumSet.copyOf(values); changed();
    }
    private boolean idfxExtensions = true;
    synchronized void setIdfxExtensions(boolean enabled) {
        if (idfxExtensions != enabled) { idfxExtensions = enabled; changed(); }
    }
    private long revision;
    long automaticRevision = -1;
    Path automaticPath;
    Runnable onRecord = () -> { };
    private Instant lastTime = Instant.MIN;

    RecordingSession() { metadata.put("startTime", System.nanoTime()); }
    synchronized int eventCount() { return entries.size(); }
    synchronized List<LogEvent> events() { return List.copyOf(entries); }
    synchronized Map<String, Object> metadata() { return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata)); }
    synchronized void putMetadata(String key, Object value) { metadata.put(key, value); changed(); }
    synchronized void updateMetadata(Map<String, Object> values) { metadata.putAll(values); changed(); }
    synchronized void putMetadataIfAbsent(String key, Object value) { if (!metadata.containsKey(key)) putMetadata(key, value); }
    synchronized void restore(ReplayLog loaded, Instant time) {
        entries.clear(); entries.addAll(loaded.events);
        metadata.clear(); metadata.putAll(loaded.metadata);
        lastTime = time;
        changed();
    }
    synchronized Instant timestamp() {
        Instant now = Instant.now();
        return now.isBefore(lastTime) ? lastTime : now;
    }
    synchronized void append(LogEvent event) {
        if (event.time.isBefore(lastTime)) event = event.at(lastTime);
        entries.add(event);
        lastTime = event.time;
        changed();
        onRecord.run();
    }
    synchronized void changed() { dirty = true; revision++; }
    void startSession(String text) { append(new SessionEvent(timestamp(), text)); }
    synchronized ReplayLog replay() {
        ReplayLog result = new ReplayLog(entries);
        result.metadata.putAll(metadata);
        return result;
    }
    synchronized SaveSnapshot snapshot() { return new SaveSnapshot(entries, metadata, revision, formats, idfxExtensions); }
    void save(Path path) throws IOException {
        if (automaticPath != null) {
            for (LogFormat format : formats) {
                Path output = format.path(path), automatic = format.path(automaticPath);
                if (output.toAbsolutePath().normalize().equals(automatic.toAbsolutePath().normalize())
                        || (Files.exists(output) && Files.exists(automatic) && Files.isSameFile(output, automatic))) {
                    throw new IOException("Choose another filename than the active automatic log");
                }
            }
        }
        snapshot().save(path);
    }
    // Synchronous only for command-line demo/tests, never called by GUI save actions.
    void saveAutomatic() throws IOException {
        if (automaticPath == null) throw new IllegalStateException("No automatic recording path configured");
        SaveSnapshot snapshot;
        synchronized (this) { if (!dirty || entries.isEmpty()) return; snapshot = snapshot(); }
        snapshot.save(automaticPath);
        savedAutomatically(snapshot.revision);
    }
    synchronized void savedAutomatically(long savedRevision) {
        automaticRevision = Math.max(automaticRevision, savedRevision);
        if (revision == savedRevision) dirty = false;
    }
}
