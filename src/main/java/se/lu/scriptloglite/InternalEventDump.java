package se.lu.scriptloglite;

import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Inspection dump of actual typed events, without save-dialect conversion. */
final class InternalEventDump {
    static void write(ReplayLog log, Writer writer) throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("record", "header"); header.put("format", "ScriptLogLite internal events v1");
        header.put("eventCount", log.events.size()); header.put("metadata", log.metadata);
        line(writer, header);
        for (int i = 0; i < log.events.size(); i++) {
            LogEvent event = log.events.get(i); Map<String, Object> data = new LinkedHashMap<>();
            data.put("record", "event"); data.put("index", i); data.put("class", event.getClass().getSimpleName());
            data.put("time", event.time.toString()); data.put("type", event.type.name());
            data.put("event", event.type.name); data.put("eventID", event.type.id);
            if (event instanceof SessionEvent) data.put("initialText", ((SessionEvent) event).initialText);
            else if (event instanceof EditEvent) {
                EditEvent edit = (EditEvent) event;
                data.put("offset", edit.offset); data.put("removed", edit.removed); data.put("inserted", edit.inserted);
            } else if (event instanceof CaretLogEvent) {
                CaretLogEvent caret = (CaretLogEvent) event; data.put("dot", caret.dot); data.put("mark", caret.mark);
            } else if (event instanceof ScrollLogEvent) {
                ScrollLogEvent scroll = (ScrollLogEvent) event; data.put("x", scroll.x); data.put("y", scroll.y);
            } else if (event instanceof KeyLogEvent) {
                KeyLogEvent key = (KeyLogEvent) event;
                data.put("keyCode", key.keyCode); data.put("keyText", key.keyText); data.put("keyChar", key.keyChar);
                data.put("modifiers", key.modifiers); data.put("modifiersText", key.modifiersText);
                data.put("keyLocation", key.keyLocation); data.put("strokeId", key.strokeId);
            }
            line(writer, data);
        }
    }
    private static void line(Writer writer, Map<String, Object> record) throws Exception {
        // Compact JSON keeps each event on exactly one line, even when text contains newlines.
        writer.write(Json.stringify(record, 0).replace("\n", "")); writer.write('\n');
    }
    static void save(ReplayLog log, Path path) throws Exception {
        Path output = path.toAbsolutePath(), temporary = Files.createTempFile(output.getParent(), ".event-dump-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { write(log, writer); }
            try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
