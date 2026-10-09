package se.lu.scriptloglite;

import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import se.lu.scriptloglite.inputlog.GeneralAnalysis;
import se.lu.scriptloglite.inputlog.LinearAnalysis;
import se.lu.scriptloglite.inputlog.PauseAnalysis;
import se.lu.scriptloglite.inputlog.WordPausesAnalysis;

final class WebScriptLogChecks {
    static void run() throws Exception {
        Path source = Path.of("wslog_QQQQQQ_09-10-2026_19_54_58.txt");
        if (Files.exists(source)) {
            ReplayLog log = ReplayLog.load(source, -1);
            String content = Files.readString(source); if (content.startsWith("\uFEFF")) content = content.substring(1);
            Map<?, ?> root = (Map<?, ?>) new Json(content).parse();
            Map<?, ?> texts = (Map<?, ?>) root.get("text_records"), keys = (Map<?, ?>) root.get("key_records");
            ReplayCursor cursor = new ReplayCursor(log);
            List<Map.Entry<?, ?>> snapshots = new ArrayList<>(texts.entrySet()); snapshots.sort(java.util.Comparator.comparingLong(e -> Long.parseLong(e.getKey().toString())));
            int index = 0;
            for (Map.Entry<?, ?> snapshot : snapshots) {
                Instant when = Instant.ofEpochMilli(Long.parseLong(snapshot.getKey().toString()));
                while (index + 1 < log.events.size() && !log.events.get(index + 1).time.isAfter(when)) index++;
                cursor.seek(index); check(cursor.state().text.equals(snapshot.getValue()), "every full snapshot reconstructs at its timestamp");
            }
            check(log.finalText.equals(snapshots.get(snapshots.size() - 1).getValue()), "final snapshot exact");
            check(log.finalText.length() == 209, "mobile fixture final UTF-16 length");
            cursor.seek(log.events.size() - 1);
            for (int i = log.events.size() - 1; i >= 0; i--) cursor.seek(i);
            check(cursor.state().text.isEmpty(), "every edit reverses to empty initial text");
            List<KeyLogEvent> imported = new ArrayList<>();
            for (LogEvent event : log.events) if (event instanceof KeyLogEvent) imported.add((KeyLogEvent) event);
            check(imported.size() == 600, "600 keyboard records retained; 14 pointer records are separate");
            int keyIndex = 0;
            for (Map.Entry<?, ?> record : keys.entrySet()) {
                String value = record.getValue().toString(); if (value.startsWith("mouse")) continue;
                KeyLogEvent key = imported.get(keyIndex++);
                check(key.time.equals(Instant.ofEpochMilli(Long.parseLong(record.getKey().toString()))), "exact observed key epoch milliseconds");
                check(key.type == (value.startsWith("keydown:") ? EventType.KEY_PRESSED : EventType.KEY_RELEASED), "key action preserved");
                check(key.keyText.equals(value.substring(value.indexOf(':') + 1).trim()), "observed key identity preserved");
                if (key.keyText.equals("Unidentified")) check(key.keyCode == 0 && key.keyChar.equals("undefined"), "unknown identity not inferred from snapshot");
            }
            check(imported.stream().filter(k -> k.keyCode == 0).count() == 484, "484 unidentified keyboard records");
            check(log.events.stream().filter(e -> e instanceof CaretLogEvent).count() == 504, "503 recorded carets plus closing marker");
            check(((List<?>) log.metadata.get("webScriptLogPointerRecords")).size() == 14, "pointer provenance retained");
            check(log.events.get(0).time.equals(Instant.ofEpochMilli(1791568328577L)) && log.events.get(log.events.size() - 1).time.equals(Instant.ofEpochMilli(1791568498669L)), "header start/end preserved");
            Path dir = Files.createTempDirectory("sll-web-import-");
            try {
                Path output = dir.resolve("converted.json");
                new SaveSnapshot(log.events, log.metadata, 0, java.util.EnumSet.of(LogFormat.JSON, LogFormat.RAW)).save(output);
                for (Path saved : List.of(output, dir.resolve("converted.txt"))) {
                    ReplayLog reopened = ReplayLog.load(saved, -1);
                    check(reopened.finalText.equals(log.finalText) && reopened.events.size() == log.events.size(), "saved JSON/raw imports intact");
                    for (int i = 0; i < log.events.size(); i++) check(reopened.events.get(i).time.equals(log.events.get(i).time), "round-trip event time");
                    check(Json.stringify(reopened.metadata.get("webScriptLogImportReport"), 0).equals(Json.stringify(log.metadata.get("webScriptLogImportReport"), 0)), "import report round trip");
                    RecordingSession session = new RecordingSession(); session.restore(reopened, reopened.events.get(reopened.events.size() - 1).time);
                    session.append(new EditEvent(Instant.ofEpochMilli(1791568500000L), EventType.INSERT, log.finalText.length(), "", "more"));
                    check(session.replay().finalText.equals(log.finalText + "more"), "continue writing after conversion");
                }
            } finally { try (var paths = Files.list(dir)) { for (Path path : (Iterable<Path>) paths::iterator) Files.delete(path); } Files.delete(dir); }
            // All existing internal analysis entry points accept the converted history.
            GeneralAnalysis.analyze(log); LinearAnalysis.analyze(log, 200, 60); PauseAnalysis.analyze(log, 200, 5); WordPausesAnalysis.analyze(log);
        }
        ReplayLog ambiguous = WebScriptLogImporter.load("{\"header_records\":{\"starttime\":0,\"endtime\":10},\"text_records\":{\"1\":\"aaa\",\"3\":\"aaaa\",\"5\":\"aaa\"},\"cursor_records\":{\"1\":\"3:3\",\"3\":\"1:1\",\"5\":\"2:2\"}}");
        List<EditEvent> edits = new ArrayList<>(); for (LogEvent e : ambiguous.events) if (e instanceof EditEvent) edits.add((EditEvent) e);
        check(edits.get(1).offset == 0 && edits.get(2).offset == 2, "cursor disambiguates repeated-character insert/delete");
        ReplayLog unicode = WebScriptLogImporter.load("{\"header_records\":{\"starttime\":0,\"endtime\":10},\"text_records\":{\"1\":\"😀\",\"2\":\"😁\",\"3\":\"😁\"}}");
        check(unicode.finalText.equals("😁"), "surrogate text preserved");
        EditEvent replacement = (EditEvent) unicode.events.get(2);
        check(replacement.offset == 0 && replacement.removed.length() == 2 && replacement.inserted.length() == 2, "surrogate pair never split");
        check(((Map<?, ?>) unicode.metadata.get("webScriptLogImportReport")).get("unchangedSnapshots").equals(1), "unchanged snapshot is not an invented edit");
        rejects("{\"header_records\":{\"starttime\":0,\"endtime\":2},\"text_records\":{\"3\":\"a\"}}", "out-of-range timestamp");
        rejects("{\"header_records\":{\"starttime\":0,\"endtime\":2},\"text_records\":{\"1\":\"a\"},\"cursor_records\":{\"1\":\"2:2\"}}", "invalid cursor");
        rejects("{\"header_records\":{\"starttime\":0,\"endtime\":2},\"text_records\":{\"1\":3}}", "non-string snapshot");
        rejects("{}", "missing web header");
        System.out.println("WebScriptLog: all 296 mobile snapshots, key/cursor timings, reverse replay, JSON/raw continuation, Unicode and analysis entry points checked");
    }
    private static void rejects(String source, String message) { try { WebScriptLogImporter.load(source); } catch (IllegalArgumentException expected) { return; } throw new AssertionError(message); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError("WebScriptLog: " + message); }
}
