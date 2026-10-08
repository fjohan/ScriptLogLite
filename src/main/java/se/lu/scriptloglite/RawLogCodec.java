package se.lu.scriptloglite;

import java.io.IOException;
import java.io.Writer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compact header, # separator, and one event per line, matching the supplied raw sample. */
final class RawLogCodec {
    static String export(ReplayLog log) {
        java.io.StringWriter writer = new java.io.StringWriter();
        try { write(log.events, log.metadata, writer); }
        catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
        return writer.toString();
    }
    static void write(List<LogEvent> events, Map<String, Object> original, Writer writer) throws IOException {
        Map<String, Object> header = JsonLogCodec.header(events, original);
        for (Map.Entry<String, Object> field : header.entrySet()) {
            writer.write(field.getKey() + ": " + compact(field.getValue()) + "\n");
        }
        writer.write("#\n");
        long start = JsonLogCodec.number(header, "startTime");
        Instant initial = events.get(0).time;
        for (int i = 1; i < events.size(); i++) {
            LogEvent event = events.get(i);
            long elapsed = Duration.between(initial, event.time).toNanos();
            writer.write(Math.addExact(start, elapsed) + " "
                    + String.format(java.util.Locale.ROOT, "%.3f", elapsed / 1e9) + " <" + event.type.name + ">");
            if (event instanceof EditEvent) {
                EditEvent edit = (EditEvent) event;
                writer.write(" " + edit.offset + " " + edit.removed.length());
                if (edit.type != EventType.REMOVE) writer.write(" " + encode(edit.inserted));
            } else if (event instanceof CaretLogEvent) {
                CaretLogEvent caret = (CaretLogEvent) event; writer.write(" " + caret.dot + " " + caret.mark);
            } else if (event instanceof ScrollLogEvent) {
                ScrollLogEvent scroll = (ScrollLogEvent) event; writer.write(" " + scroll.x + " " + scroll.y);
            } else if (event instanceof KeyLogEvent) {
                KeyLogEvent key = (KeyLogEvent) event; writer.write(" " + key.keyCode);
                extra(writer, "keyText", key.keyText); extra(writer, "keyChar", key.keyChar);
                extra(writer, "modifiers", key.modifiers); extra(writer, "modifiersText", key.modifiersText);
                extra(writer, "keyLocation", key.keyLocation);
            }
            writer.write('\n');
        }
    }
    private static String compact(Object value) {
        String json = Json.stringify(value, 0);
        StringBuilder result = new StringBuilder();
        boolean quoted = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                result.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '\"') quoted = false;
            } else if (c == '\"') { quoted = true; result.append(c); }
            else if (!Character.isWhitespace(c)) result.append(c);
        }
        return result.toString();
    }
    private static void extra(Writer writer, String name, Object value) throws IOException {
        if (value != null) writer.write(" " + name + "=" + encode(value.toString()));
    }
    // The sample uses a single backslash before s/n. Literal backslashes
    // and additional controls use a unicode escape so every string is reversible.
    static String encode(String text) {
        if (text == null) return "\\N";
        if (text.isEmpty()) return "\\e";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case ' ': out.append("\\s"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c == '\\' || Character.isWhitespace(c) || c < 32) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }
    static String decode(String text) {
        if (text.equals("\\N")) return null;
        if (text.equals("\\e")) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                if (i + 1 >= text.length()) throw new IllegalArgumentException("Incomplete raw escape");
                i += 1;
                switch (text.charAt(i)) {
                    case '\\': c = '\\'; break;
                    case 's': c = ' '; break;
                    case 'n': c = '\n'; break;
                    case 'r': c = '\r'; break;
                    case 't': c = '\t'; break;
                    case 'u':
                        if (i + 4 >= text.length()) throw new IllegalArgumentException("Incomplete unicode escape");
                        c = (char) Integer.parseInt(text.substring(i + 1, i + 5), 16); i += 4; break;
                    default: throw new IllegalArgumentException("Unknown raw escape");
                }
            }
            out.append(c);
        }
        return out.toString();
    }
    static ReplayLog load(String content) {
        String[] lines = content.split("\\R", -1);
        Map<String, Object> header = new LinkedHashMap<>();
        int separator = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].equals("#")) { separator = i; break; }
            int colon = lines[i].indexOf(':');
            if (colon < 1) throw new IllegalArgumentException("Invalid raw header line " + (i + 1));
            String key = lines[i].substring(0, colon).trim(), value = lines[i].substring(colon + 1).trim();
            if (header.containsKey(key)) throw new IllegalArgumentException("Duplicate raw header key " + key);
            Object parsed;
            if ((key.equals("Version") || key.startsWith("id_") || key.equals("osName") || key.equals("fontFamily")
                    || key.equals("textLanguage") || key.equals("recordingStartTime") || key.equals("initialText"))
                    && !value.startsWith("\"")) parsed = value;
            else if (value.startsWith("\"")) parsed = new Json(value).parse();
            else {
                try { parsed = new Json(value).parse(); }
                catch (IllegalArgumentException exception) { parsed = value; }
            }
            header.put(key, parsed);
        }
        if (separator < 0) throw new IllegalArgumentException("Missing # raw header separator");
        long start = JsonLogCodec.number(header, "startTime");
        Instant anchor = header.containsKey("recordingStartTime") ? Instant.parse(JsonLogCodec.string(header, "recordingStartTime")) : Instant.EPOCH;
        String initial = header.containsKey("initialText") ? JsonLogCodec.string(header, "initialText") : "";
        StringBuilder text = new StringBuilder(initial);
        List<LogEvent> events = new ArrayList<>(); events.add(new SessionEvent(anchor, initial));
        for (int i = separator + 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            try {
                String[] parts = lines[i].split(" ", -1);
                if (parts.length < 4 || !parts[2].startsWith("<") || !parts[2].endsWith(">")) throw new IllegalArgumentException("Invalid event fields");
                long elapsed = Math.subtractExact(Long.parseLong(parts[0]), start);
                if (elapsed < 0) throw new IllegalArgumentException("Event precedes startTime");
                double relative = Double.parseDouble(parts[1]);
                if (!Double.isFinite(relative) || relative < 0) throw new IllegalArgumentException("Invalid relative time");
                Instant time = anchor.plusNanos(elapsed);
                EventType type = EventType.named(parts[2].substring(1, parts[2].length() - 1));
                LogEvent event;
                switch (type) {
                    case INSERT: case REPLACE: case REMOVE:
                        if (parts.length != (type == EventType.REMOVE ? 5 : 6)) throw new IllegalArgumentException("Invalid edit fields");
                        int offset = Integer.parseInt(parts[3]), length = Integer.parseInt(parts[4]);
                        if (offset < 0 || length < 0 || offset > text.length() - length || (type == EventType.INSERT && length != 0)) throw new IllegalArgumentException("Edit outside document");
                        EditEvent edit = new EditEvent(time, type, offset, text.substring(offset, offset + length), type == EventType.REMOVE ? null : decode(parts[5]));
                        edit.apply(text); event = edit; break;
                    case CARET: case SCROLL:
                        if (parts.length != 5) throw new IllegalArgumentException("Invalid view event fields");
                        event = type == EventType.CARET ? new CaretLogEvent(time, Integer.parseInt(parts[3]), Integer.parseInt(parts[4]))
                                : new ScrollLogEvent(time, Integer.parseInt(parts[3]), Integer.parseInt(parts[4])); break;
                    case KEY_PRESSED: case KEY_RELEASED:
                        Map<String, String> extras = new LinkedHashMap<>();
                        for (int j = 4; j < parts.length; j++) {
                            int equals = parts[j].indexOf('=');
                            if (equals < 1) throw new IllegalArgumentException("Invalid keyboard detail");
                            extras.put(parts[j].substring(0, equals), decode(parts[j].substring(equals + 1)));
                        }
                        event = new KeyLogEvent(time, type, Integer.parseInt(parts[3]), extras.get("keyText"), extras.get("keyChar"),
                                ReplayLog.optionalInteger(extras, "modifiers"), extras.get("modifiersText"), ReplayLog.optionalInteger(extras, "keyLocation")); break;
                    default: throw new IllegalArgumentException("Unexpected event " + type.name);
                }
                events.add(event);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid raw line " + (i + 1) + ": " + exception.getMessage(), exception);
            }
        }
        ReplayLog result = new ReplayLog(events); result.metadata.putAll(header); return result;
    }
}
