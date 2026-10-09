package se.lu.scriptloglite;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit conversion to WebScriptLog snapshot JSON; never a native save format. */
final class WebScriptLogExporter {
    static final class Report {
        int shiftedTimes, unknownKeys, scrolls, backwardSelections;
        String description() {
            return "Exported WebScriptLog.\n" + shiftedTimes + " timestamps adjusted to preserve ordering at millisecond precision.\n"
                    + unknownKeys + " keys exported as Unidentified.\n" + backwardSelections + " selections exported without direction.\n"
                    + scrolls + " native scroll records omitted (web scroll schema unavailable).";
        }
    }
    private static final class Item {
        final long original; final String section, value; final int index, priority;
        long time;
        Item(long original, String section, int index, int priority, String value) {
            this.original = original; this.section = section; this.index = index; this.priority = priority; this.value = value;
        }
    }
    static Report write(ReplayLog log, Writer writer) throws Exception {
        Report report = new Report(); List<Item> items = new ArrayList<>();
        long start = log.events.get(0).time.toEpochMilli();
        if (start < 0) throw new IllegalArgumentException("WebScriptLog requires nonnegative epoch milliseconds");
        if (!log.initialText.isEmpty()) items.add(new Item(start, "text_records", 0, 1, null));
        for (int i = 1; i < log.events.size(); i++) {
            LogEvent e = log.events.get(i); long t = e.time.toEpochMilli();
            if (e instanceof EditEvent) items.add(new Item(t, "text_records", i, 1, null));
            else if (e instanceof CaretLogEvent) {
                CaretLogEvent c = (CaretLogEvent) e;
                if (c.dot < c.mark) report.backwardSelections++;
                items.add(new Item(t, "cursor_records", i, 2, Math.min(c.dot, c.mark) + ":" + Math.max(c.dot, c.mark)));
            } else if (e instanceof KeyLogEvent) {
                KeyLogEvent key = (KeyLogEvent) e; String name = keyName(key);
                if (name.equals("Unidentified")) report.unknownKeys++;
                boolean down = e.type == EventType.KEY_PRESSED;
                items.add(new Item(t, "key_records", i, down ? 0 : 3, (down ? "keydown: " : "keyup: ") + name));
            } else if (e instanceof ScrollLogEvent) report.scrolls++;
        }
        Object pointers = log.metadata.get("webScriptLogPointerRecords");
        if (pointers instanceof List) for (Object item : (List<?>) pointers) {
            Map<?, ?> p = (Map<?, ?>) item;
            items.add(new Item(((Number) p.get("when")).longValue(), "key_records", -1, 0, p.get("record").toString()));
        }
        // Stable sorting preserves captured order; source pointers are interleaved by time.
        items.sort(Comparator.comparingLong(item -> item.original));
        Map<String, Long> lastInSection = new LinkedHashMap<>(); long last = start; int phase = -1;
        for (Item item : items) {
            long t = Math.max(last, item.original);
            if ((lastInSection.containsKey(item.section) && t <= lastInSection.get(item.section)) || (item.index >= 0 && t == last && item.priority < phase)) t = Math.addExact(t, 1);
            item.time = t; if (t != item.original) report.shiftedTimes++;
            if (t != last) phase = -1;
            last = t; if (item.index >= 0) phase = item.priority; lastInSection.put(item.section, t);
        }
        long end = Math.max(last, log.events.get(log.events.size() - 1).time.toEpochMilli());
        writer.write("{\n  \"header_records\": {\"starttime\": " + start + ", \"endtime\": " + end + "}");
        ReplayCursor cursor = new ReplayCursor(log);
        for (String section : List.of("text_records", "cursor_records", "key_records")) {
            writer.write(",\n  " + Json.quote(section) + ": {"); boolean first = true;
            for (Item item : items) if (item.section.equals(section)) {
                String value = item.value;
                if (section.equals("text_records")) { cursor.seek(item.index); value = cursor.state().text; }
                writer.write(first ? "\n    " : ",\n    "); first = false;
                writer.write(Json.quote(Long.toString(item.time)) + ": " + Json.quote(value));
            }
            writer.write(first ? "}" : "\n  }");
        }
        for (String section : List.of("scroll_records", "image_records", "window_records")) {
            Object retained = log.metadata.get("webScriptLog_" + section);
            writer.write(",\n  " + Json.quote(section) + ": " + Json.stringify(retained instanceof Map ? retained : Map.of(), 0));
        }
        writer.write("\n}\n"); return report;
    }
    static Report save(ReplayLog log, Path target) throws Exception {
        Path output = target.toAbsolutePath(); Path temporary = Files.createTempFile(output.getParent(), ".webscriptlog-", ".tmp");
        try {
            Report report;
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { report = write(log, writer); }
            try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING); }
            return report;
        } finally { Files.deleteIfExists(temporary); }
    }
    private static String keyName(KeyLogEvent key) {
        if (key.keyText != null && key.keyText.equals("Unidentified")) return "Unidentified";
        switch (key.keyCode) {
            case java.awt.event.KeyEvent.VK_BACK_SPACE: return "Backspace";
            case java.awt.event.KeyEvent.VK_ENTER: return "Enter";
            case java.awt.event.KeyEvent.VK_SHIFT: return "Shift";
            case java.awt.event.KeyEvent.VK_CONTROL: return "Control";
            case java.awt.event.KeyEvent.VK_ALT: return "Alt";
            case java.awt.event.KeyEvent.VK_META: return "Meta";
            case java.awt.event.KeyEvent.VK_TAB: return "Tab";
            case java.awt.event.KeyEvent.VK_DELETE: return "Delete";
            case java.awt.event.KeyEvent.VK_LEFT: return "ArrowLeft";
            case java.awt.event.KeyEvent.VK_RIGHT: return "ArrowRight";
            case java.awt.event.KeyEvent.VK_UP: return "ArrowUp";
            case java.awt.event.KeyEvent.VK_DOWN: return "ArrowDown";
            case java.awt.event.KeyEvent.VK_HOME: return "Home";
            case java.awt.event.KeyEvent.VK_END: return "End";
            case java.awt.event.KeyEvent.VK_ESCAPE: return "Escape";
            default:
                return key.keyChar != null && key.keyChar.length() == 1 && !Character.isISOControl(key.keyChar.charAt(0)) ? key.keyChar : "Unidentified";
        }
    }
}
