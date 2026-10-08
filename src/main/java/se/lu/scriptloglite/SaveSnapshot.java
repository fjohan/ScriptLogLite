package se.lu.scriptloglite;

import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class SaveSnapshot {
    final List<LogEvent> events;
    final Map<String, Object> metadata;
    final long revision;
    final java.util.Set<LogFormat> formats;
    SaveSnapshot(List<LogEvent> events, Map<String, Object> metadata, long revision) {
        this(events, metadata, revision, java.util.EnumSet.of(LogFormat.JSON));
    }
    SaveSnapshot(List<LogEvent> events, Map<String, Object> metadata, long revision, java.util.Set<LogFormat> formats) {
        if (formats.isEmpty()) throw new IllegalArgumentException("Select at least one save format");
        this.formats = java.util.Collections.unmodifiableSet(java.util.EnumSet.copyOf(formats));
        this.events = List.copyOf(events);
        this.metadata = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        this.revision = revision;
    }
    void save(Path path) throws IOException {
        Map<Path, Path> staged = new LinkedHashMap<>();
        try {
            for (LogFormat format : formats) {
                Path target = format.path(path).toAbsolutePath();
                Path temporary = Files.createTempFile(target.getParent(), ".saved-log-", ".tmp");
                staged.put(target, temporary);
                try (java.io.Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    if (format == LogFormat.JSON) JsonLogCodec.write(events, metadata, writer);
                    else RawLogCodec.write(events, metadata, writer);
                    writer.write('\n');
                }
            }
            for (Map.Entry<Path, Path> entry : staged.entrySet()) {
                try {
                    Files.move(entry.getValue(), entry.getKey(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(entry.getValue(), entry.getKey(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            for (Path temporary : staged.values()) Files.deleteIfExists(temporary);
        }
    }
}
