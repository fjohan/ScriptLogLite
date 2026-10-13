package se.lu.scriptloglite;

import java.awt.event.KeyEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** WebScriptLog snapshot import. Key identities remain observed, never inferred from text. */
final class WebScriptLogImporter {
    private static final class Record {
        final long time; final int priority; final String kind, value;
        Record(long time, int priority, String kind, String value) { this.time = time; this.priority = priority; this.kind = kind; this.value = value; }
    }
    static ReplayLog load(String content) {
        Object parsed = new Json(content).parse();
        if (!(parsed instanceof Map)) throw new IllegalArgumentException("Expected WebScriptLog JSON object");
        Map<?, ?> root = (Map<?, ?>) parsed;
        Map<?, ?> header = object(root.get("header_records"), "header_records");
        long start = timestamp(header.get("starttime"), "starttime"), end = timestamp(header.get("endtime"), "endtime");
        if (end < start) throw new IllegalArgumentException("WebScriptLog endtime precedes starttime");
        List<Record> records = new ArrayList<>();
        read(root, "text_records", 1, true, start, end, records);
        read(root, "cursor_records", 2, false, start, end, records);
        read(root, "key_records", 0, false, start, end, records);
        if (root.containsKey("scroll_records")) for (Map.Entry<?, ?> entry : object(root.get("scroll_records"), "scroll_records").entrySet()) {
            long time = timestamp(entry.getKey(), "scroll timestamp");
            if (time < start || time > end) throw new IllegalArgumentException("scroll timestamp outside header interval");
            if (!(entry.getValue() instanceof Number)) throw new IllegalArgumentException("Unsupported web scroll record; expected numeric vertical offset");
            long value = timestamp(entry.getValue(), "scroll position");
            if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("scroll offset out of range");
            records.add(new Record(time, 2, "scroll_records", Long.toString(value)));
        }
        // A simultaneous key release follows the snapshot and caret observation.
        records.sort(Comparator.comparingLong((Record r) -> r.time).thenComparingInt(r -> r.kind.equals("key_records") && r.value.startsWith("keyup:") ? 3 : r.priority));
        Map<Long, int[]> afterCarets = new LinkedHashMap<>();
        for (Record r : records) if (r.kind.equals("cursor_records")) afterCarets.put(r.time, caret(r.value));
        List<LogEvent> events = new ArrayList<>(); events.add(new SessionEvent(Instant.ofEpochMilli(start), ""));
        String text = ""; int lastKey = 0; int dot = 0, mark = 0, snapshots = 0, edits = 0, noops = 0, keys = 0, unknown = 0, cursors = 0;
        List<Map<String, Object>> pointers = new ArrayList<>();
        for (Record r : records) {
            Instant time = Instant.ofEpochMilli(r.time);
            try {
                if (r.kind.equals("text_records")) {
                    snapshots++;
                    if (text.equals(r.value)) { noops++; continue; }
                    EditEvent edit = difference(time, text, r.value, afterCarets.get(r.time), dot, mark, lastKey);
                    events.add(edit); edits++; text = r.value; lastKey = 0;
                    dot = Math.min(dot, text.length()); mark = Math.min(mark, text.length());
                } else if (r.kind.equals("cursor_records")) {
                    int[] c = caret(r.value);
                    if (c[0] > text.length() || c[1] > text.length()) throw new IllegalArgumentException("cursor outside snapshot text");
                    // Web records selectionStart:selectionEnd, without selection direction.
                    mark = c[0]; dot = c[1]; events.add(new CaretLogEvent(time, dot, mark)); cursors++;
                } else if (r.kind.equals("scroll_records")) {
                    events.add(new ScrollLogEvent(time, 0, Integer.parseInt(r.value)));
                } else {
                    int colon = r.value.indexOf(':');
                    if (colon < 0) throw new IllegalArgumentException("invalid input record");
                    String kind = r.value.substring(0, colon).trim();
                    // One space separates the action from KeyboardEvent.key; a second is the actual Space key.
                    String name = r.value.substring(colon + 1);
                    if (name.startsWith(" ")) name = name.substring(1);
                    if (kind.equals("mousedown") || kind.equals("mouseup") || kind.equals("mousemove")) {
                        Map<String, Object> pointer = new LinkedHashMap<>(); pointer.put("when", r.time); pointer.put("record", r.value); pointers.add(pointer); continue;
                    }
                    if (!kind.equals("keydown") && !kind.equals("keyup") && !kind.equals("repeat")) throw new IllegalArgumentException("unsupported input record " + kind);
                    int code = keyCode(name); String ch = character(name, code);
                    if (code == KeyEvent.VK_UNDEFINED && name.codePointCount(0, name.length()) != 1) unknown++;
                    if (!kind.equals("keyup")) lastKey = code;
                    events.add(new KeyLogEvent(time, !kind.equals("keyup") ? EventType.KEY_PRESSED : EventType.KEY_RELEASED,
                            code, name, ch, null, null, KeyEvent.KEY_LOCATION_UNKNOWN, null, kind.equals("repeat"))); keys++;
                }
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("WebScriptLog " + r.kind + " at " + r.time + ": " + exception.getMessage(), exception);
            }
        }
        // Preserve recording duration even when the final input predates endtime.
        events.add(new CaretLogEvent(Instant.ofEpochMilli(end), dot, mark, true));
        ReplayLog result = new ReplayLog(events);
        result.metadata.put("sourceFormat", "WebScriptLog"); result.metadata.put("webScriptLogHeader", header);
        result.metadata.put("webScriptLogPointerRecords", pointers);
        List<String> warnings = new ArrayList<>();
        warnings.add("Each full text snapshot is converted to one reversible contiguous edit. Autocorrection, paste and composition are not inferred as physical keystrokes.");
        warnings.add("Initial text is assumed empty; the first snapshot establishes the first observed text. Cursor ranges use UTF-16 positions; selection direction is unavailable.");
        warnings.add(unknown + " key records have unavailable identities. Their timestamps remain intact; characters are not invented from snapshots. Typing statistics cannot recover characters from unidentified keys.");
        if (!pointers.isEmpty()) warnings.add(pointers.size() + " pointer records retained in metadata; the web log supplies no pointer coordinates.");
        for (Map.Entry<?, ?> entry : root.entrySet()) {
            String name = entry.getKey().toString();
            if (List.of("header_records", "text_records", "cursor_records", "key_records").contains(name)) continue;
            Map<?, ?> extra = object(entry.getValue(), name);
            if (!extra.isEmpty()) { result.metadata.put("webScriptLog_" + name, extra); if (!name.equals("scroll_records")) warnings.add(name + " retained in metadata; this record type is not replayed."); }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("textSnapshots", snapshots); report.put("edits", edits); report.put("unchangedSnapshots", noops);
        report.put("cursorRecords", cursors); report.put("keyRecords", keys); report.put("unknownKeyRecords", unknown);
        report.put("pointerRecords", pointers.size()); report.put("warnings", warnings);
        result.metadata.put("webScriptLogImportReport", report);
        return result;
    }
    private static void read(Map<?, ?> root, String kind, int priority, boolean required, long start, long end, List<Record> records) {
        if (!root.containsKey(kind) && !required) return;
        Map<?, ?> values = object(root.get(kind), kind);
        for (Map.Entry<?, ?> e : values.entrySet()) {
            long time = timestamp(e.getKey(), kind + " timestamp");
            if (time < start || time > end) throw new IllegalArgumentException(kind + " timestamp outside header interval: " + time);
            if (!(e.getValue() instanceof String)) throw new IllegalArgumentException(kind + " value must be a string at " + time);
            records.add(new Record(time, priority, kind, (String) e.getValue()));
        }
    }
    private static Map<?, ?> object(Object value, String label) { if (!(value instanceof Map)) throw new IllegalArgumentException("Missing/invalid WebScriptLog " + label); return (Map<?, ?>) value; }
    private static long timestamp(Object value, String label) {
        try { long time = Long.parseLong(String.valueOf(value)); if (time < 0) throw new NumberFormatException(); return time; }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("Invalid epoch milliseconds for " + label); }
    }
    private static int[] caret(String value) {
        String[] parts = value.split(":", -1); if (parts.length != 2) throw new IllegalArgumentException("expected selectionStart:selectionEnd");
        int start = Integer.parseInt(parts[0]), end = Integer.parseInt(parts[1]);
        if (start < 0 || end < start) throw new IllegalArgumentException("invalid cursor range"); return new int[] {start, end};
    }
    private static EditEvent difference(Instant time, String old, String next, int[] after, int dot, int mark, int key) {
        int prefix = 0; while (prefix < old.length() && prefix < next.length() && old.charAt(prefix) == next.charAt(prefix)) prefix++;
        // Avoid splitting a surrogate pair in a replacement range.
        if (!boundary(old, prefix) || !boundary(next, prefix)) prefix--;
        int suffix = 0;
        while (suffix < old.length() - prefix && suffix < next.length() - prefix
                && old.charAt(old.length() - 1 - suffix) == next.charAt(next.length() - 1 - suffix)) suffix++;
        if (!boundary(old, old.length() - suffix) || !boundary(next, next.length() - suffix)) suffix--;
        int removed = old.length() - prefix - suffix, inserted = next.length() - prefix - suffix;
        if (after != null && after[0] == after[1]) {
            int candidate = removed == 0 ? after[0] - inserted : inserted == 0 ? after[0] : Math.min(dot, mark);
            int delete = removed, add = inserted;
            if (removed > 0 && inserted > 0 && dot != mark) { delete = Math.abs(dot - mark); add = next.length() - old.length() + delete; }
            if (candidate >= 0 && delete >= 0 && add >= 0 && candidate + delete <= old.length() && candidate + add <= next.length()
                    && boundary(old, candidate) && boundary(old, candidate + delete) && boundary(next, candidate) && boundary(next, candidate + add)
                    && (old.substring(0, candidate) + next.substring(candidate, candidate + add) + old.substring(candidate + delete)).equals(next)) {
                prefix = candidate; removed = delete; inserted = add;
            }
        }
        // A recorded pre-edit selection describes the operated range even if its
        // replacement retains a common prefix. Snapshots alone would minimize it away.
        int candidate = -1, delete = removed, add = inserted;
        if (dot != mark) { candidate = Math.min(dot, mark); delete = Math.abs(dot - mark); add = next.length() - old.length() + delete; }
        else if (removed == 0 && after == null) candidate = dot;
        else if (inserted == 0 && key == KeyEvent.VK_BACK_SPACE) candidate = dot - removed;
        else if (inserted == 0 && key == KeyEvent.VK_DELETE) candidate = dot;
        if (candidate >= 0 && add >= 0 && candidate + delete <= old.length() && candidate + add <= next.length()
                && boundary(old, candidate) && boundary(old, candidate + delete) && boundary(next, candidate) && boundary(next, candidate + add)
                && (old.substring(0, candidate) + next.substring(candidate, candidate + add) + old.substring(candidate + delete)).equals(next)) {
            prefix = candidate; removed = delete; inserted = add;
        }
        String before = old.substring(prefix, prefix + removed), replacement = next.substring(prefix, prefix + inserted);
        return new EditEvent(time, removed == 0 ? EventType.INSERT : inserted == 0 ? EventType.REMOVE : EventType.REPLACE,
                prefix, before, inserted == 0 ? null : replacement);
    }
    private static boolean boundary(String text, int position) { return position == 0 || position == text.length() || !Character.isHighSurrogate(text.charAt(position - 1)) || !Character.isLowSurrogate(text.charAt(position)); }
    private static int keyCode(String name) {
        if (name.length() == 1) return KeyEvent.getExtendedKeyCodeForChar(name.charAt(0));
        switch (name) {
            case "Backspace": return KeyEvent.VK_BACK_SPACE; case "Enter": return KeyEvent.VK_ENTER;
            case "Shift": return KeyEvent.VK_SHIFT; case "Control": return KeyEvent.VK_CONTROL;
            case "Alt": return KeyEvent.VK_ALT; case "AltGraph": return KeyEvent.VK_ALT_GRAPH; case "Meta": return KeyEvent.VK_META;
            case "Tab": return KeyEvent.VK_TAB; case "Delete": return KeyEvent.VK_DELETE;
            case "ArrowLeft": return KeyEvent.VK_LEFT; case "ArrowRight": return KeyEvent.VK_RIGHT;
            case "ArrowUp": return KeyEvent.VK_UP; case "ArrowDown": return KeyEvent.VK_DOWN;
            case "PageUp": return KeyEvent.VK_PAGE_UP; case "PageDown": return KeyEvent.VK_PAGE_DOWN;
            case "Home": return KeyEvent.VK_HOME; case "End": return KeyEvent.VK_END;
            case "Escape": return KeyEvent.VK_ESCAPE; default: return KeyEvent.VK_UNDEFINED;
        }
    }
    private static String character(String name, int code) {
        if (name.codePointCount(0, name.length()) == 1) return name;
        if (code == KeyEvent.VK_UNDEFINED) return "undefined";
        return code == KeyEvent.VK_ENTER ? "\n" : code == KeyEvent.VK_TAB ? "\t" : "";
    }
}
