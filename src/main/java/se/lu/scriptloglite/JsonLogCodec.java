package se.lu.scriptloglite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;

/** Reads and streams the compatible [[metadata], [events]] JSON format. */
class JsonLogCodec {
    static String export(List<?> input, Map<String, Object> originalMetadata) {
        java.io.StringWriter writer = new java.io.StringWriter();
        try { write(ReplayLog.parse(input).events, originalMetadata, writer); }
        catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
        return writer.toString();
    }

    /** Stream one JSON event at a time; no whole-output string or event-map list. */
    static void write(List<LogEvent> events, Map<String, Object> originalMetadata, java.io.Writer writer) throws IOException {
        if (events.isEmpty() || !(events.get(0) instanceof SessionEvent)) throw new IllegalArgumentException("Missing session");
        SessionEvent initial = (SessionEvent) events.get(0);
        LogEvent last = events.get(events.size() - 1);
        // Validate one forward pass using a single text buffer, without replay checkpoints.
        StringBuilder text = new StringBuilder(initial.initialText);
        Instant previous = initial.time;
        for (int i = 1; i < events.size(); i++) {
            LogEvent event = events.get(i);
            if (event.time.isBefore(previous)) throw new IllegalArgumentException("timestamps run backwards");
            previous = event.time;
            if (event instanceof EditEvent) ((EditEvent) event).apply(text);
            else if (event instanceof CaretLogEvent) {
                CaretLogEvent caret = (CaretLogEvent) event;
                if (caret.dot < 0 || caret.mark < 0 || caret.dot > text.length() || caret.mark > text.length()) {
                    throw new IllegalArgumentException("caret outside document");
                }
            } else if (event instanceof ScrollLogEvent) {
                ScrollLogEvent scroll = (ScrollLogEvent) event;
                if (scroll.x < 0 || scroll.y < 0) throw new IllegalArgumentException("negative scroll position");
            } else if (event instanceof SessionEvent) throw new IllegalArgumentException("Unexpected session marker");
        }
        Map<String, Object> metadata = new LinkedHashMap<>(originalMetadata);
        for (String key : List.of("id_age", "id_language", "id_task", "id_condition", "id_comment", "id_code",
                "id_family_name", "id_project", "textLanguage", "id_first_name", "id_gender")) metadata.putIfAbsent(key, "");
        metadata.putIfAbsent("osName", System.getProperty("os.name"));
        metadata.putIfAbsent("Version", "ScriptLogLite-2");
        metadata.putIfAbsent("fontFamily", "Monospaced"); metadata.putIfAbsent("fontSize", 12);
        metadata.putIfAbsent("lineSpacing", 1.0);
        for (String key : List.of("TextAreaWidth", "TextAreaHeight", "TextAreaX", "TextAreaY")) metadata.putIfAbsent(key, 0);
        long start = metadata.containsKey("startTime") ? number(metadata, "startTime") : 0;
        metadata.put("startTime", start);
        long end = Math.addExact(start, Duration.between(initial.time, last.time).toNanos());
        if (metadata.containsKey("endTime")) end = Math.max(end, number(metadata, "endTime"));
        metadata.put("endTime", end);
        metadata.put("initialText", initial.initialText);
        metadata.put("recordingStartTime", initial.time.toString());
        metadata.put("tokensInFinalText", text.length());
        writer.write("[\n  [\n    ");
        writer.write(Json.stringify(metadata, 2));
        writer.write("\n  ],\n  [");
        for (int i = 1; i < events.size(); i++) {
            LogEvent item = events.get(i);
            long elapsed = Duration.between(initial.time, item.time).toNanos();
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("when", Math.addExact(start, elapsed));
            event.put("relativeTime", String.format(java.util.Locale.ROOT, "%.3f", elapsed / 1e9));
            event.put("event", "<" + item.type.name + ">"); event.put("eventID", item.type.id);
            if (item instanceof EditEvent) {
                EditEvent edit = (EditEvent) item;
                event.put("offset", edit.offset); event.put("length", edit.removed.length());
                if (edit.type != EventType.REMOVE) event.put("str", edit.inserted);
            } else if (item instanceof CaretLogEvent) {
                CaretLogEvent caret = (CaretLogEvent) item;
                event.put("dot", caret.dot); event.put("mark", caret.mark);
            } else if (item instanceof ScrollLogEvent) {
                ScrollLogEvent scroll = (ScrollLogEvent) item;
                event.put("viewX", scroll.x); event.put("viewY", scroll.y);
            } else if (item instanceof KeyLogEvent) {
                KeyLogEvent key = (KeyLogEvent) item;
                event.put("keyCode", key.keyCode);
                if (key.keyText != null) event.put("keyText", key.keyText);
                if (key.keyChar != null) event.put("keyChar", key.keyChar);
                if (key.modifiers != null) event.put("modifiers", key.modifiers);
                if (key.modifiersText != null) event.put("modifiersText", key.modifiersText);
                if (key.keyLocation != null) event.put("keyLocation", key.keyLocation);
            }
            writer.write(i == 1 ? "\n    " : ",\n    ");
            writer.write(Json.stringify(event, 2));
        }
        writer.write(events.size() == 1 ? "]\n]" : "\n  ]\n]");
    }

    @SuppressWarnings("unchecked")
    static ReplayLog load(String json) {
        Object parsed = new Json(json).parse();
        if (!(parsed instanceof List) || ((List<?>) parsed).size() != 2) throw new IllegalArgumentException("Expected [[metadata], [events]]");
        List<?> root = (List<?>) parsed;
        if (!(root.get(0) instanceof List) || ((List<?>) root.get(0)).size() != 1
                || !(((List<?>) root.get(0)).get(0) instanceof Map) || !(root.get(1) instanceof List)) {
            throw new IllegalArgumentException("Expected one metadata object and an event array");
        }
        Map<String, Object> metadata = (Map<String, Object>) ((List<?>) root.get(0)).get(0);
        long start = number(metadata, "startTime");
        Instant anchor = metadata.containsKey("recordingStartTime") ? Instant.parse(string(metadata, "recordingStartTime")) : Instant.EPOCH;
        String initial = metadata.containsKey("initialText") ? string(metadata, "initialText") : "";
        StringBuilder text = new StringBuilder(initial);
        List<LogEvent> events = new ArrayList<>(); events.add(new SessionEvent(anchor, initial));
        int index = 0;
        for (Object item : (List<?>) root.get(1)) {
            index++;
            try {
                if (!(item instanceof Map)) throw new IllegalArgumentException("event must be an object");
                Map<String, Object> fields = (Map<String, Object>) item;
                String tag = string(fields, "event");
                if (!tag.startsWith("<") || !tag.endsWith(">")) throw new IllegalArgumentException("invalid event name");
                EventType type = EventType.named(tag.substring(1, tag.length() - 1));
                if (type == EventType.SESSION || number(fields, "eventID") != type.id) throw new IllegalArgumentException("eventID mismatch");
                long elapsed = Math.subtractExact(number(fields, "when"), start);
                if (elapsed < 0) throw new IllegalArgumentException("event precedes startTime");
                Instant time = anchor.plusNanos(elapsed);
                LogEvent event;
                switch (type) {
                    case INSERT: case REMOVE: case REPLACE:
                        int offset = integer(fields, "offset"), length = integer(fields, "length");
                        if (offset < 0 || length < 0 || offset > text.length() - length) throw new IllegalArgumentException("edit outside document");
                        if (type == EventType.INSERT && length != 0) throw new IllegalArgumentException("insertString length must be zero");
                        if (type != EventType.REMOVE && !fields.containsKey("str")) throw new IllegalArgumentException("missing str");
                        String inserted = type == EventType.REMOVE || fields.get("str") == null ? null : string(fields, "str");
                        EditEvent edit = new EditEvent(time, type, offset, text.substring(offset, offset + length), inserted);
                        edit.apply(text); event = edit; break;
                    case CARET: event = new CaretLogEvent(time, integer(fields, "dot"), integer(fields, "mark")); break;
                    case SCROLL: event = new ScrollLogEvent(time, integer(fields, "viewX"), integer(fields, "viewY")); break;
                    default: event = new KeyLogEvent(time, type, integer(fields, "keyCode"), optionalString(fields, "keyText"),
                            optionalString(fields, "keyChar"), optionalInt(fields, "modifiers"), optionalString(fields, "modifiersText"),
                            optionalInt(fields, "keyLocation"));
                }
                events.add(event);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid JSON event " + index + ": " + exception.getMessage(), exception);
            }
        }
        ReplayLog replay = new ReplayLog(events); replay.metadata.putAll(metadata); return replay;
    }
    static String optionalString(Map<String, Object> fields, String key) { return fields.containsKey(key) ? string(fields, key) : null; }
    static Integer optionalInt(Map<String, Object> fields, String key) { return fields.containsKey(key) ? integer(fields, key) : null; }

    static long number(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Long) && !(value instanceof Integer)) {
            throw new IllegalArgumentException("Expected integer " + key);
        }
        return ((Number) value).longValue();
    }

    static int integer(Map<String, Object> object, String key) {
        return Math.toIntExact(number(object, key));
    }

    static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("Expected string " + key);
        return (String) value;
    }
}
