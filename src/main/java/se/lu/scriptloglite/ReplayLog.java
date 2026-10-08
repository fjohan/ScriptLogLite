package se.lu.scriptloglite;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Validated event history with view state and sparse text checkpoints. */
class ReplayLog {
    final List<LogEvent> events;
    final Map<String, Object> metadata = new LinkedHashMap<>();
    final List<ViewState> views = new ArrayList<>();
    final List<Integer> boundaries = new ArrayList<>();
    final java.util.NavigableMap<Integer, String> checkpoints = new java.util.TreeMap<>();
    final String initialText, finalText;
    // Compatibility convenience: get() materializes one state, not an array of text snapshots.
    final List<ReplayState> states = new java.util.AbstractList<ReplayState>() {
        public int size() { return events.size(); }
        public ReplayState get(int index) { ReplayCursor cursor = new ReplayCursor(ReplayLog.this); cursor.seek(index); return cursor.state(); }
    };
    static final Pattern FIELD = Pattern.compile("(\\w+)=(\"(?:\\\\.|[^\"\\\\])*+\"|\\S+)");

    ReplayLog(List<LogEvent> input) {
        if (input.isEmpty() || !(input.get(0) instanceof SessionEvent)) {
            throw new IllegalArgumentException("Missing session");
        }
        events = List.copyOf(input);
        initialText = ((SessionEvent) events.get(0)).initialText;
        StringBuilder text = new StringBuilder(initialText);
        int dot = 0, mark = 0, x = 0, y = 0, edits = 0;
        Instant previous = events.get(0).time;
        checkpoints.put(0, initialText);
        boundaries.add(0);
        for (int i = 0; i < events.size(); i++) {
            LogEvent event = events.get(i);
            try {
                if (event.time.isBefore(previous)) throw new IllegalArgumentException("timestamps run backwards");
                previous = event.time;
                if (event instanceof EditEvent) {
                    EditEvent edit = (EditEvent) event;
                    if (edit.type == EventType.INSERT && !edit.removed.isEmpty()) {
                        throw new IllegalArgumentException("insertString cannot remove text");
                    }
                    edit.apply(text);
                    dot = Math.min(dot, text.length()); mark = Math.min(mark, text.length());
                    if (++edits % 256 == 0) checkpoints.put(i, text.toString());
                    if (boundaries.size() > 1) boundaries.set(boundaries.size() - 1, i - 1);
                    boundaries.add(i);
                } else if (event instanceof CaretLogEvent) {
                    CaretLogEvent caret = (CaretLogEvent) event;
                    dot = caret.dot; mark = caret.mark;
                    if (dot < 0 || mark < 0 || dot > text.length() || mark > text.length()) {
                        throw new IllegalArgumentException("caret outside document");
                    }
                } else if (event instanceof ScrollLogEvent) {
                    ScrollLogEvent scroll = (ScrollLogEvent) event; x = scroll.x; y = scroll.y;
                    if (x < 0 || y < 0) throw new IllegalArgumentException("negative scroll position");
                } else if (i != 0 && event instanceof SessionEvent) {
                    throw new IllegalArgumentException("unexpected session marker");
                }
                views.add(new ViewState(dot, mark, x, y));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid replay event " + (i + 1) + ": " + exception.getMessage(), exception);
            }
        }
        finalText = text.toString();
        if (boundaries.size() > 1) boundaries.set(boundaries.size() - 1, events.size() - 1);
    }

    static ReplayLog parse(List<?> input) {
        if (!input.isEmpty() && input.get(0) instanceof LogEvent) {
            List<LogEvent> events = new ArrayList<>();
            for (Object item : input) events.add((LogEvent) item);
            return new ReplayLog(events);
        }
        List<LogEvent> events = new ArrayList<>();
        for (Object item : input) events.add(legacyEvent((String) item));
        return new ReplayLog(events);
    }
    static LogEvent legacyEvent(String line) {
        String[] parts = line.split(" ", 3);
        Instant time = Instant.parse(parts[0]);
        EventType type = EventType.named(parts[1]);
        Map<String, String> fields = new HashMap<>();
        Matcher matcher = FIELD.matcher(parts.length > 2 ? parts[2] : "");
        while (matcher.find()) fields.put(matcher.group(1), decode(matcher.group(2)));
        switch (type) {
            case SESSION: return new SessionEvent(time, required(fields, "initialText"));
            case INSERT: case REMOVE: case REPLACE:
                String old = required(fields, "oldText");
                if (old.length() != Integer.parseInt(required(fields, "length"))) {
                    throw new IllegalArgumentException("oldText length mismatch");
                }
                return new EditEvent(time, type, Integer.parseInt(required(fields, "offset")), old,
                        type == EventType.REMOVE ? null : fields.get("text"));
            case CARET: return new CaretLogEvent(time, Integer.parseInt(required(fields, "dot")), Integer.parseInt(required(fields, "mark")));
            case SCROLL: return new ScrollLogEvent(time, Integer.parseInt(required(fields, "x")), Integer.parseInt(required(fields, "y")));
            default: return new KeyLogEvent(time, type, Integer.parseInt(required(fields, "keyCode")),
                    fields.get("keyText"), fields.get("keyChar"), optionalInteger(fields, "modifiers"),
                    fields.get("modifiersText"), optionalInteger(fields, "keyLocation"));
        }
    }
    static Integer optionalInteger(Map<String, String> fields, String key) {
        return fields.containsKey(key) ? Integer.valueOf(fields.get(key)) : null;
    }
    static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) throw new IllegalArgumentException("missing " + key);
        return value;
    }
    static String decode(String value) { return value.startsWith("\"") ? (String) new Json(value).parse() : value.equals("null") ? null : value; }
    static ReplayLog load(Path path, int session) throws Exception {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        if (content.stripLeading().startsWith("<")) {
            if (session != -1 && session != 1) throw new IllegalArgumentException("IDFX holds one session");
            ReplayLog imported = InputlogImporter.load(content);
            imported.metadata.put("inputlogSourceFile", path.getFileName().toString());
            return imported;
        }
        if (content.stripLeading().startsWith("[")) {
            if (session != -1 && session != 1) throw new IllegalArgumentException("JSON holds one session");
            return JsonLogCodec.load(content);
        }
        if (java.util.Arrays.stream(content.split("\\R")).anyMatch(line -> line.equals("#"))) {
            if (session != -1 && session != 1) throw new IllegalArgumentException("Raw holds one session");
            return RawLogCodec.load(content);
        }
        List<List<String>> sessions = new ArrayList<>();
        for (String line : content.split("\\R")) {
            if (line.isBlank()) continue;
            if (line.matches("\\S+ session .*")) sessions.add(new ArrayList<>());
            if (!sessions.isEmpty()) sessions.get(sessions.size() - 1).add(line);
        }
        if (sessions.isEmpty()) throw new IllegalArgumentException("Log has no session marker");
        int index = session == -1 ? sessions.size() - 1 : session - 1;
        if (index < 0 || index >= sessions.size()) throw new IllegalArgumentException("Invalid session number");
        return parse(sessions.get(index));
    }
    int nextEdit(int position) { for (int boundary : boundaries) if (boundary > position) return boundary; return position; }
    int previousEdit(int position) { for (int i = boundaries.size() - 1; i >= 0; i--) if (boundaries.get(i) < position) return boundaries.get(i); return 0; }
}
