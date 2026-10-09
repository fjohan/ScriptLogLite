package se.lu.scriptloglite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.awt.event.KeyEvent;

final class WebScriptLogExportChecks {
    static void run() throws Exception {
        for (String name : List.of("wslog_QQQQQQ_09-10-2026_19_54_58.txt", "exp_subj_json_1.json", "JF_97.idfx")) {
            Path file = Path.of(name); if (!Files.exists(file)) continue;
            ReplayLog original = ReplayLog.load(file, -1); String serialized = export(original);
            ReplayLog loaded = WebScriptLogImporter.load(serialized);
            check(loaded.finalText.equals(original.finalText), "fixture final text " + name);
            check(texts(original).equals(texts(loaded)), "fixture snapshot sequence " + name);
            check(original.events.stream().filter(e -> e instanceof KeyLogEvent).count() == loaded.events.stream().filter(e -> e instanceof KeyLogEvent).count(), "fixture keyboard record count " + name);
            if (name.startsWith("wslog")) {
                String input = Files.readString(file); if (input.startsWith("\uFEFF")) input = input.substring(1);
                Map<?, ?> old = (Map<?, ?>) new Json(input).parse(), converted = (Map<?, ?>) new Json(serialized).parse();
                check(old.get("text_records").equals(converted.get("text_records")), "mobile text records and times exact");
                check(old.get("key_records").equals(converted.get("key_records")), "mobile keys/pointers and times exact");
                check(old.get("header_records").equals(converted.get("header_records")), "mobile header exact");
                Map<?, ?> cursors = (Map<?, ?>) converted.get("cursor_records");
                for (Map.Entry<?, ?> e : ((Map<?, ?>) old.get("cursor_records")).entrySet()) check(e.getValue().equals(cursors.get(e.getKey())), "mobile recorded cursors exact");
            }
        }
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        ReplayLog collision = new ReplayLog(List.of(new SessionEvent(time, "start"),
                new EditEvent(time.plusNanos(1), EventType.INSERT, 5, "", "a"),
                new CaretLogEvent(time.plusNanos(2), 6, 6),
                new EditEvent(time.plusNanos(3), EventType.REPLACE, 0, "s", "S"),
                new CaretLogEvent(time.plusNanos(4), 1, 3),
                new KeyLogEvent(time.plusNanos(5), EventType.KEY_PRESSED, KeyEvent.VK_A, "A", "a", 0, "", 1),
                new KeyLogEvent(time.plusNanos(6), EventType.KEY_RELEASED, KeyEvent.VK_A, "A", "a", 0, "", 1),
                new ScrollLogEvent(time.plusNanos(7), 0, 10)));
        java.io.StringWriter writer = new java.io.StringWriter(); WebScriptLogExporter.Report report = WebScriptLogExporter.write(collision, writer);
        check(report.shiftedTimes > 0 && report.scrolls == 1 && report.backwardSelections == 1, "conversion limitations reported");
        ReplayLog imported = WebScriptLogImporter.load(writer.toString());
        check(imported.finalText.equals(collision.finalText), "collisions and nonempty initial text survive");
        check(texts(imported).equals(List.of("start", "starta", "Starta")), "colliding snapshots are not overwritten");
        check(imported.events.stream().filter(e -> e instanceof KeyLogEvent).count() == 2, "colliding key pair retained");
        check(imported.events.get(imported.events.size() - 1).time.isAfter(time), "header end extended for adjustments");
        Path directory = Files.createTempDirectory("sll-web-export-");
        try {
            Path output = directory.resolve("export.txt"); WebScriptLogExporter.save(collision, output);
            check(ReplayLog.load(output, -1).finalText.equals(collision.finalText), "atomic export file opens normally");
            RecordingSession session = new RecordingSession(); session.restore(collision, time.plusNanos(7));
            SaveSnapshot before = session.snapshot(); WebScriptLogExporter.save(session.replay(), output);
            SaveSnapshot after = session.snapshot();
            check(before.revision == after.revision && before.formats.equals(after.formats) && before.editDialect == after.editDialect, "export doesn't change save state");
        } finally { try (var files = Files.list(directory)) { for (Path file : (Iterable<Path>) files::iterator) Files.delete(file); } Files.delete(directory); }
        System.out.println("WebScriptLog export: mobile/native/IDFX snapshots, exact mobile timing, collision handling, initial text and separate atomic export checked");
    }
    private static String export(ReplayLog log) throws Exception { java.io.StringWriter writer = new java.io.StringWriter(); WebScriptLogExporter.write(log, writer); return writer.toString(); }
    private static List<String> texts(ReplayLog log) {
        List<String> result = new ArrayList<>(); if (!log.initialText.isEmpty()) result.add(log.initialText);
        ReplayCursor cursor = new ReplayCursor(log); String last = log.initialText;
        for (int i = 1; i < log.events.size(); i++) if (log.events.get(i) instanceof EditEvent) { cursor.seek(i); String text = cursor.state().text; if (!text.equals(last)) result.add(text); last = text; }
        return result;
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError("WebScriptLog export: " + label); }
}
