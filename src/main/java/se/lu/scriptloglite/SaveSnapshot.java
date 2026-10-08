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
    SaveSnapshot(List<LogEvent> events, Map<String, Object> metadata, long revision) {
        this.events = List.copyOf(events);
        this.metadata = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        this.revision = revision;
    }
    void save(Path path) throws IOException {
        Path target = path.toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".saved-log-", ".tmp");
        try {
            try (java.io.Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                JsonLogCodec.write(events, metadata, writer);
                writer.write('\n');
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
