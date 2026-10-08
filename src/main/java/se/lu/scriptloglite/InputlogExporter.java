package se.lu.scriptloglite;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.Writer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/** Inputlog-compatible text history; exported metadata explicitly identifies inferred values. */
final class InputlogExporter {
    static final String HEADER = "__ScriptLogLiteHeader";
    static final String TIME = "ScriptLogLite.elapsedNanos";
    private final List<LogEvent> events;
    private final Map<String, Object> header;
    private final XMLStreamWriter xml;
    private final boolean extensions;
    private final Map<Integer, Integer> releases = new HashMap<>();
    private final Set<Integer> consumedEdits = new HashSet<>();
    private final Set<Integer> held = new HashSet<>();
    private final Map<String, String> labels = new LinkedHashMap<>();
    private final StringBuilder text = new StringBuilder();
    private final Instant start;
    private int id, dot, mark, orphanReleases;

    private InputlogExporter(List<LogEvent> events, Map<String, Object> metadata, Writer writer, boolean extensions) throws XMLStreamException {
        this.events = events;
        this.extensions = extensions;
        header = JsonLogCodec.header(events, metadata);
        start = events.get(0).time;
        xml = XMLOutputFactory.newFactory().createXMLStreamWriter(writer);
        Map<Integer, ArrayDeque<Integer>> pending = new HashMap<>();
        for (int i = 1; i < events.size(); i++) if (events.get(i) instanceof KeyLogEvent) {
            KeyLogEvent key = (KeyLogEvent) events.get(i);
            ArrayDeque<Integer> queue = pending.computeIfAbsent(key.keyCode, ignored -> new ArrayDeque<>());
            if (key.type == EventType.KEY_PRESSED) queue.add(i);
            else if (!queue.isEmpty()) releases.put(queue.remove(), i);
            else orphanReleases++;
        }
    }
    static void write(List<LogEvent> events, Map<String, Object> metadata, Writer writer) throws IOException {
        write(events, metadata, writer, true);
    }
    static void write(List<LogEvent> events, Map<String, Object> metadata, Writer writer, boolean extensions) throws IOException {
        try { new InputlogExporter(events, metadata, writer, extensions).write(); }
        catch (XMLStreamException e) { throw new IOException("Cannot write IDFX", e); }
    }
    static String export(ReplayLog log) { return export(log, true); }
    static String export(ReplayLog log, boolean extensions) {
        java.io.StringWriter writer = new java.io.StringWriter();
        try { write(log.events, log.metadata, writer, extensions); }
        catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        return writer.toString();
    }
    private void write() throws XMLStreamException {
        xml.writeStartDocument("UTF-8", "1.0"); xml.writeStartElement("log");
        xml.writeStartElement("meta");
        entry("__LogProgramVersion", "9.7.0.1");
        entry("__MainDocument", "ScriptLogLite-export.docx");
        entry("__LogCreationDate", DateTimeFormatter.ofPattern("dd-MM-yy HH:mm:ss.SSS").withZone(ZoneOffset.UTC).format(start));
        entry("__LogCreationTimeStamp", Long.toString(start.toEpochMilli()));
        entry("__LogRelativeCreationDate", "1");
        entry("__GUID", java.util.UUID.randomUUID().toString());
        if (extensions) {
            entry("__ScriptLogLiteExporter", "ScriptLogLite IDFX-1 (compatibility schema 9.7.0.1; not a Word recording)");
            entry(HEADER, Json.stringify(header, 0));
            List<String> notes = new ArrayList<>();
            notes.add("Word positions use UTF-16 offsets with one mandatory final paragraph mark; LF maps to Word CR.");
            notes.add("A legacy log without a wall-clock epoch uses its parsed session epoch (typically 1970); creation date is not evidence of a real Word recording.");
            notes.add("Native clocks are elapsed milliseconds on a synthetic clock starting at 1; Word-only event timestamps are labels.");
            notes.add("Straightforward typing/backspace uses replay=True; other edits use replacement events with associated replay=False keys.");
            notes.add("Missing key characters/modifiers are inferred from nearby edits and held modifier keys where possible.");
            notes.add("No Word document, page layout or screen mouse coordinates exist. Scroll offsets are retained only in labels.");
            notes.add("Initial text, when present, is a synthetic insertion at session start. Statistics are approximate text counts.");
            notes.add("Keys are paired in order per Java key code; missing releases use endTime=0. Unpaired releases omitted: " + orphanReleases + ".");
            entry("__ScriptLogLiteExportReport", Json.stringify(notes, 0));
        }
        xml.writeCharacters("\n  "); xml.writeEndElement();
        xml.writeCharacters("\n  "); xml.writeStartElement("session");
        for (String[] mapping : new String[][] {{"Participant", "id_code"}, {"Text Language", "textLanguage"},
                {"Age", "id_age"}, {"Gender", "id_gender"}, {"Session", "id_task"}, {"Group", "id_condition"}}) {
            entry(mapping[0], String.valueOf(header.getOrDefault(mapping[1], "")));
        }
        entry("Experience", ""); entry("Restricted Logging", ""); xml.writeCharacters("\n  "); xml.writeEndElement();
        focus(start); // Identifies the document and anchors native Inputlog timing.
        String initial = ((SessionEvent) events.get(0)).initialText;
        if (!initial.isEmpty()) { replacement(start, 0, 0, initial, null); text.append(initial); }
        for (int i = 1; i < events.size(); i++) {
            LogEvent event = events.get(i);
            if (event instanceof KeyLogEvent) {
                KeyLogEvent key = (KeyLogEvent) event;
                if (key.type == EventType.KEY_PRESSED) keyboard(i, key);
                else held.remove(key.keyCode);
            } else if (event instanceof EditEvent) {
                EditEvent edit = (EditEvent) event;
                if (!consumedEdits.contains(i)) {
                    replacement(event.time, edit.offset, edit.offset + edit.removed.length(), edit.replacement(), edit.type);
                    edit.apply(text);
                    dot = Math.min(dot, text.length()); mark = Math.min(mark, text.length());
                }
            } else if (event instanceof CaretLogEvent) {
                CaretLogEvent caret = (CaretLogEvent) event; dot = caret.dot; mark = caret.mark;
                open("selection", event.time); label("ScriptLogLite.dot", Integer.toString(dot));
                label("ScriptLogLite.mark", Integer.toString(mark)); part("wordlog");
                field("start", Math.min(dot, mark)); field("end", Math.max(dot, mark)); closePartAndEvent();
            } else if (extensions && event instanceof ScrollLogEvent) {
                ScrollLogEvent scroll = (ScrollLogEvent) event;
                // A labelled selection carries the viewport without fabricating a mouse-wheel event.
                open("selection", event.time); label("ScriptLogLite.viewX", Integer.toString(scroll.x));
                label("ScriptLogLite.viewY", Integer.toString(scroll.y)); part("wordlog");
                field("start", Math.min(dot, mark)); field("end", Math.max(dot, mark)); closePartAndEvent();
            }
        }
        long duration = Math.subtractExact(JsonLogCodec.number(header, "endTime"), JsonLogCodec.number(header, "startTime"));
        Instant end = start.plusNanos(duration);
        focus(end); statistics(end, initial); xml.writeEndElement(); xml.writeEndDocument(); xml.flush();
    }
    private void keyboard(int index, KeyLogEvent key) throws XMLStreamException {
        held.add(key.keyCode);
        int modifiers = key.modifiers == null ? inferredModifiers() : key.modifiers;
        EditEvent next = index + 1 < events.size() && events.get(index + 1) instanceof EditEvent
                ? (EditEvent) events.get(index + 1) : null;
        boolean typing = next != null && dot == mark && next.offset == dot && next.removed.isEmpty()
                && next.replacement().length() == 1 && printableKey(key.keyCode)
                && !(key.keyCode == KeyEvent.VK_ENTER && (modifiers & InputEvent.SHIFT_DOWN_MASK) != 0)
                && (modifiers & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK)) == 0;
        boolean backspace = next != null && dot == mark && key.keyCode == KeyEvent.VK_BACK_SPACE
                && next.offset == dot - 1 && next.removed.length() == 1 && next.replacement().isEmpty()
                && (modifiers & InputEvent.CTRL_DOWN_MASK) == 0;
        boolean replay = typing || backspace;
        String value = typing ? next.replacement() : backspace ? "\b" : key.keyChar;
        if (value == null || value.equals("undefined")) value = "";
        String virtual = windowsKey(key);
        open("keyboard", key.time);
        if (replay) label("ScriptLogLite.editElapsedNanos", Long.toString(nanos(next.time)));
        if (releases.containsKey(index)) label("ScriptLogLite.releaseElapsedNanos", Long.toString(nanos(events.get(releases.get(index)).time)));
        part("wordlog"); field("position", dot); field("documentLength", text.length() + 1);
        field("replay", replay ? "True" : "False"); xml.writeEndElement();
        part("winlog"); field("startTime", clock(key.time));
        field("endTime", releases.containsKey(index) ? clock(events.get(releases.get(index)).time) : 0);
        field("key", virtual); field("value", wordEscape(value)); xml.writeStartElement("keyboardstate");
        for (Object[] modifier : new Object[][] {{InputEvent.SHIFT_DOWN_MASK, "VK_LSHIFT"},
                {InputEvent.CTRL_DOWN_MASK, "VK_LCONTROL"}, {InputEvent.ALT_DOWN_MASK, "VK_LMENU"},
                {InputEvent.META_DOWN_MASK, "VK_LWIN"}}) {
            if ((modifiers & (Integer) modifier[0]) != 0) field("key", modifier[1]);
        }
        xml.writeEndElement(); closePartAndEvent();
        if (replay) { next.apply(text); consumedEdits.add(index + 1); dot = next.offset + next.replacement().length(); mark = dot; }
    }
    private int inferredModifiers() {
        int result = 0;
        if (held.contains(KeyEvent.VK_SHIFT)) result |= InputEvent.SHIFT_DOWN_MASK;
        if (held.contains(KeyEvent.VK_CONTROL)) result |= InputEvent.CTRL_DOWN_MASK;
        if (held.contains(KeyEvent.VK_ALT) || held.contains(KeyEvent.VK_ALT_GRAPH)) result |= InputEvent.ALT_DOWN_MASK;
        if (held.contains(KeyEvent.VK_META) || held.contains(KeyEvent.VK_WINDOWS)) result |= InputEvent.META_DOWN_MASK;
        return result;
    }
    private static boolean printableKey(int code) {
        return code == KeyEvent.VK_ENTER || code == KeyEvent.VK_TAB || code == KeyEvent.VK_SPACE
                || (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) || (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z)
                || code == KeyEvent.VK_QUOTE || code == KeyEvent.VK_BACK_QUOTE || code == KeyEvent.VK_OPEN_BRACKET
                || code == KeyEvent.VK_CLOSE_BRACKET || code == KeyEvent.VK_SEMICOLON || code == KeyEvent.VK_COMMA
                || code == KeyEvent.VK_PERIOD || code == KeyEvent.VK_SLASH || code == KeyEvent.VK_BACK_SLASH
                || code == KeyEvent.VK_EQUALS || code == KeyEvent.VK_MINUS;
    }
    private static String windowsKey(KeyLogEvent key) {
        if (key.keyText != null && key.keyText.startsWith("VK_")) return key.keyText;
        switch (key.keyCode) {
            case KeyEvent.VK_PAGE_UP: return "VK_PRIOR";
            case KeyEvent.VK_PAGE_DOWN: return "VK_NEXT";
            case KeyEvent.VK_BACK_SPACE: return "VK_BACK";
            case KeyEvent.VK_ENTER: return "VK_RETURN";
            case KeyEvent.VK_SHIFT: return key.keyLocation != null && key.keyLocation == KeyEvent.KEY_LOCATION_RIGHT ? "VK_RSHIFT" : "VK_LSHIFT";
            case KeyEvent.VK_CONTROL: return "VK_LCONTROL";
            case KeyEvent.VK_ALT: case KeyEvent.VK_ALT_GRAPH: return "VK_LMENU";
            case KeyEvent.VK_META: case KeyEvent.VK_WINDOWS: return "VK_LWIN";
            case KeyEvent.VK_BACK_QUOTE: return "VK_OEM_3";
            case KeyEvent.VK_OPEN_BRACKET: return "VK_OEM_4";
            case KeyEvent.VK_CLOSE_BRACKET: return "VK_OEM_6";
            case KeyEvent.VK_QUOTE: return "VK_OEM_7";
            case KeyEvent.VK_SEMICOLON: return "VK_OEM_1";
            case KeyEvent.VK_SLASH: return "VK_OEM_2";
            case KeyEvent.VK_BACK_SLASH: return "VK_OEM_5";
            case KeyEvent.VK_MINUS: return "VK_OEM_MINUS";
            case KeyEvent.VK_EQUALS: return "VK_OEM_PLUS";
            case KeyEvent.VK_COMMA: return "VK_OEM_COMMA";
            case KeyEvent.VK_PERIOD: return "VK_OEM_PERIOD";
            default:
                if (key.keyCode >= 48 && key.keyCode <= 57 || key.keyCode >= 65 && key.keyCode <= 90) return "VK_" + (char) key.keyCode;
                for (String name : List.of("SPACE", "TAB", "DELETE", "UP", "DOWN", "LEFT", "RIGHT", "HOME", "END", "ESCAPE")) {
                    try { if (KeyEvent.class.getField("VK_" + name).getInt(null) == key.keyCode) return "VK_" + name; }
                    catch (ReflectiveOperationException ignored) { }
                }
                if (key.keyCode >= KeyEvent.VK_F1 && key.keyCode <= KeyEvent.VK_F12) return "VK_F" + (key.keyCode - KeyEvent.VK_F1 + 1);
                return "0"; // Numeric undefined value is accepted by C# Enum.Parse.
        }
    }
    private void replacement(Instant time, int begin, int end, String value, EventType type) throws XMLStreamException {
        open("replacement", time);
        if (type != null) label("ScriptLogLite.editType", type.name);
        part("wordlog"); field("start", begin); field("end", end); field("newtext", wordEscape(value)); closePartAndEvent();
    }
    private void focus(Instant time) throws XMLStreamException {
        open("focus", time); part("winlog"); field("title", "WordLog MainDoc");
        field("startTime", clock(time)); field("endTime", clock(time)); closePartAndEvent();
    }
    private void statistics(Instant time, String initial) throws XMLStreamException {
        open("statistics", time); part("wordlog"); stats(text.toString(), ""); stats(initial, "st"); closePartAndEvent();
    }
    private void stats(String value, String prefix) throws XMLStreamException {
        String visible = value.replace("\n", "").replace("\r", "");
        field(prefix + "charexclspaces", visible.replace(" ", "").length()); field(prefix + "charinclspaces", visible.length());
        // Inputlog uses this same tag for both final and starting statistics.
        field("fareastcharcount", 0); field(prefix + "linecount", value.isEmpty() ? 0 : value.split("\n", -1).length);
        field(prefix + "pagecount", 1); field(prefix + "paragraphcount", value.isEmpty() ? 0 : value.split("\n", -1).length);
        field(prefix + "wordcount", value.isBlank() ? 0 : value.trim().split("\\s+").length);
    }
    private long nanos(Instant time) { return Duration.between(start, time).toNanos(); }
    private long clock(Instant time) { return Math.addExact(1, nanos(time) / 1_000_000); }
    private void open(String type, Instant time) throws XMLStreamException {
        xml.writeCharacters("\n  "); xml.writeStartElement("event"); xml.writeAttribute("type", type);
        xml.writeAttribute("id", Integer.toString(id++)); label(TIME, Long.toString(nanos(time)));
    }
    private void label(String key, String value) { if (extensions) labels.put(key, value); }
    private void part(String type) throws XMLStreamException {
        if (!labels.isEmpty()) {
            // Inputlog's reader keys its label dictionary by the label value. One
            // structured label avoids duplicate values for equal dot/mark/times.
            xml.writeStartElement("label"); xml.writeAttribute("key", "ScriptLogLite");
            xml.writeCharacters(Json.stringify(labels, 0)); xml.writeEndElement(); labels.clear();
        }
        xml.writeStartElement("part"); xml.writeAttribute("type", type);
    }
    private void closePartAndEvent() throws XMLStreamException { xml.writeEndElement(); xml.writeEndElement(); }
    private void entry(String key, String value) throws XMLStreamException {
        // SessionIdentification.ReadXml depends on whitespace nodes between these
        // elements and after each entry, as produced by C# XmlWriter Indent=true.
        xml.writeCharacters("\n    "); xml.writeStartElement("entry");
        xml.writeCharacters("\n      "); field("key", key);
        xml.writeCharacters("\n      "); field("value", value);
        xml.writeCharacters("\n    "); xml.writeEndElement();
    }
    private void field(String name, Object value) throws XMLStreamException {
        xml.writeStartElement(name); xml.writeCharacters(String.valueOf(value)); xml.writeEndElement();
    }
    static String wordEscape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n') c = '\r';
            if (c < 32 || c == '\\' || c == '\uFFFE' || c == '\uFFFF' || Character.isSurrogate(c)) {
                escaped.append(String.format(java.util.Locale.ROOT, "\\u%04X", (int) c));
            } else escaped.append(c);
        }
        return escaped.toString();
    }
}
