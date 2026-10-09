package se.lu.scriptloglite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.EnumSet;
import java.time.Instant;

final class EditDialectChecks {
    static void run() throws Exception {
        Path directory = Files.createTempDirectory("sll-dialect-");
        try {
            DirectoryHistory preferences = new DirectoryHistory(directory.resolve("preferences.properties"));
            check(preferences.editDialect() == EditDialect.REPLACE, "replace is default");
            preferences.rememberEditDialect(EditDialect.REPLACE);
            check(new DirectoryHistory(preferences.file).editDialect() == EditDialect.REPLACE, "dialect preference persists");
            preferences.rememberEditDialect(EditDialect.RECORDED);
            check(new DirectoryHistory(preferences.file).editDialect() == EditDialect.RECORDED, "explicit recorded choice persists");
            Instant time = Instant.parse("2026-01-01T00:00:00Z");
            ReplayLog synthetic = new ReplayLog(List.of(new SessionEvent(time, ""),
                    new EditEvent(time.plusMillis(1), EventType.INSERT, 0, "", "ab"),
                    new EditEvent(time.plusMillis(2), EventType.REPLACE, 1, "b", "c"),
                    new EditEvent(time.plusMillis(3), EventType.REMOVE, 0, "a", null),
                    new EditEvent(time.plusMillis(4), EventType.INSERT, 1, "", null)));
            verify(synthetic, directory);
            for (String file : List.of("exp_subj_json_1.json", "wslog_QQQQQQ_09-10-2026_19_54_58.txt")) {
                if (Files.exists(Path.of(file))) verify(ReplayLog.load(Path.of(file), -1), directory);
            }
            RecordingSession session = new RecordingSession(); session.restore(synthetic, synthetic.events.get(synthetic.events.size() - 1).time);
            session.setFormats(EnumSet.of(LogFormat.JSON, LogFormat.RAW));
            SaveSnapshot before = session.snapshot(); session.setEditDialect(EditDialect.REPLACE);
            SaveSnapshot after = session.snapshot();
            before.save(directory.resolve("before.json")); after.save(directory.resolve("after.json"));
            check(ReplayLog.load(directory.resolve("before.json"), -1).events.get(1).type == EventType.INSERT, "pending snapshot retains old dialect");
            check(ReplayLog.load(directory.resolve("after.json"), -1).events.get(1).type == EventType.REPLACE, "new snapshot uses selected dialect");
            check(session.events().get(1).type == EventType.INSERT, "captured callback is preserved");
        } finally {
            try (var files = Files.list(directory)) { for (Path file : (Iterable<Path>) files::iterator) Files.delete(file); }
            Files.delete(directory);
        }
        System.out.println("Edit dialect: JSON/raw replace insertions, exact reverse replay, unchanged capture, saved settings and background snapshots checked");
    }
    private static void verify(ReplayLog original, Path directory) throws Exception {
        new SaveSnapshot(original.events, original.metadata, 0, EnumSet.of(LogFormat.JSON, LogFormat.RAW), true, EditDialect.REPLACE).save(directory.resolve("replace.json"));
        for (String filename : List.of("replace.json", "replace.txt")) {
            ReplayLog loaded = ReplayLog.load(directory.resolve(filename), -1);
            check(loaded.metadata.get("editDialect").equals("replace"), "header identifies dialect");
            check(loaded.events.size() == original.events.size(), "event count unchanged");
            ReplayCursor a = new ReplayCursor(original), b = new ReplayCursor(loaded);
            for (int i = original.events.size() - 1; i >= 0; i--) {
                LogEvent old = original.events.get(i), event = loaded.events.get(i);
                check(event.type == (old.type == EventType.INSERT ? EventType.REPLACE : old.type), "only insertion type changes");
                check(old.time.equals(event.time), "time retained");
                if (old instanceof EditEvent) {
                    EditEvent x = (EditEvent) old, y = (EditEvent) event;
                    check(x.offset == y.offset && x.removed.equals(y.removed) && java.util.Objects.equals(x.inserted, y.inserted), "edit payload retained including null");
                }
                a.seek(i); b.seek(i);
                check(a.state().text.equals(b.state().text), "reverse text at every event unchanged");
            }
            check(original.finalText.equals(loaded.finalText), "final text unchanged");
            se.lu.scriptloglite.inputlog.GeneralAnalysis.analyze(loaded);
            se.lu.scriptloglite.inputlog.WordPausesAnalysis.analyze(loaded);
            RecordingSession continued = new RecordingSession(); continued.restore(loaded, loaded.events.get(loaded.events.size() - 1).time);
            continued.append(new EditEvent(loaded.events.get(loaded.events.size() - 1).time.plusMillis(1), EventType.INSERT, loaded.finalText.length(), "", "more"));
            check(continued.replay().finalText.equals(loaded.finalText + "more"), "continued writing after reload");
        }
        String json = Files.readString(directory.resolve("replace.json"));
        check(!json.contains("<insertString>"), "replace dialect contains no insertString records");
        if (original.events.stream().anyMatch(e -> e.type == EventType.INSERT)) check(json.contains("\"eventID\": 103"), "JSON replace event ID agrees");
    }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError("Edit dialect: " + message); }
}
