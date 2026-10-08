package se.lu.scriptloglite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

final class InputlogChecks {
    static void run() throws Exception {
        Path sample = Path.of("JF_92.idfx");
        if (Files.exists(sample)) {
            ReplayLog log = ReplayLog.load(sample, -1);
            String expected = "KJELL HÖGLUND\n\nJag har haft samma känsla förut\n"
                    + "det är inte första gången\ndenna känsla att vara ett fallande löv i vinden\n\n"
                    + "som när någon stuckit hål\npå den vackraste ballongen\n(just när) tåget lämnar perrongen\n";
            check(log.finalText.equals(expected), "independent IDFX reconstruction");
            check(log.finalText.length() == 209, "Word paragraph normalization");
            check(log.finalText.replace("\n", "").length() == 200
                    && log.finalText.replace("\n", "").replace(" ", "").length() == 171,
                    "Word final statistics independently corroborate reconstructed text");
            Map<?, ?> report = (Map<?, ?>) log.metadata.get("inputlogImportReport");
            Map<?, ?> counts = (Map<?, ?>) report.get("sourceEventCounts");
            check(counts.get("keyboard").equals(335) && counts.get("selection").equals(22), "source event counts");
            check(((List<?>) report.get("inferredEdits")).size() == 2, "cut and paste inference disclosed");
            check(((List<?>) log.metadata.get("inputlogAncillaryEvents")).size() == 17, "mouse/focus/statistics retained");
            check(JsonLogCodec.number(log.metadata, "endTime") == 120015000000L, "source session duration including idle time");
            check(log.events.stream().anyMatch(e -> e instanceof KeyLogEvent && "\b".equals(((KeyLogEvent) e).keyChar)),
                    "illegal XML backspace references restored");
            ReplayCursor cursor = new ReplayCursor(log);
            cursor.seek(log.events.size() - 1);
            for (int i = log.events.size() - 2; i >= 0; i--) cursor.seek(i);
            check(cursor.state().text.isEmpty(), "all imported edits reversed");
            ScriptLogLiteChecks.checkHistory(log, JsonLogCodec.load(JsonLogCodec.export(log.events, log.metadata)), "IDFX JSON round trip");
            ReplayLog raw = RawLogCodec.load(RawLogCodec.export(log));
            ScriptLogLiteChecks.checkHistory(log, raw, "IDFX raw round trip");
            check(raw.metadata.get("inputlogImportReport").equals(new Json(Json.stringify(report, 0)).parse()), "report survives raw");
            SwingUtilities.invokeAndWait(() -> {
                LoggingFilter filter = new LoggingFilter();
                JTextArea area = EditorSupport.createTextArea(filter);
                EditorSupport.restoreLog(area, filter, log);
                area.append("continued");
                ReplayLog continued = filter.session.replay();
                check(continued.finalText.equals(expected + "continued"), "continue imported recording");
                LogEvent appended = continued.events.get(log.events.size());
                check(Duration.between(continued.events.get(0).time, appended.time).toNanos()
                        >= JsonLogCodec.number(continued.metadata, "endTime"), "continuation follows recorded idle tail");
                long elapsed = Duration.between(continued.events.get(0).time, appended.time).toNanos();
                check(elapsed - JsonLogCodec.number(continued.metadata, "endTime") < 5_000_000_000L, "continuation clock");
            });
            Path folder = Files.createTempDirectory("inputlog-export-check");
            try {
                new SaveSnapshot(log.events, log.metadata, 1, java.util.EnumSet.allOf(LogFormat.class)).save(folder.resolve("converted.json"));
                ScriptLogLiteChecks.checkHistory(log, ReplayLog.load(folder.resolve("converted.json"), -1), "saved IDFX JSON");
                ScriptLogLiteChecks.checkHistory(log, ReplayLog.load(folder.resolve("converted.txt"), -1), "saved IDFX raw");
            } finally {
                Files.deleteIfExists(folder.resolve("converted.json")); Files.deleteIfExists(folder.resolve("converted.txt")); Files.delete(folder);
            }
            check(LogFormat.JSON.path(sample).equals(Path.of("JF_92.json")), "IDFX export filename");
            System.out.println("Inputlog: JF_92 reconstructed, reversed, continued and round-tripped as JSON/raw");
        }
        String key = key(0, 1, "VK_A", "a", true, 110, 150);
        String second = key(1, 2, "VK_B", "b", true, 120, 130);
        ReplayLog overlap = InputlogImporter.load(file(key + second));
        check(overlap.finalText.equals("ab"), "overlapping key releases preserve edits");
        ReplayLog enter = InputlogImporter.load(file(key(0, 1, "VK_RETURN", "NEWLINE", true, 110, 0)
                + key(1, 2, "VK_BACK", "&#x8;", true, 120, 130)));
        check(enter.finalText.isEmpty(), "Enter/backspace one Word unit");
        rejects(file(key(0, 4, "VK_A", "a", true, 110, 120)), "pre-existing content");
        rejects(file("<event type='unknown' id='9'/>"), "unsupported source event");
        rejects(file(key + "<event type='insert' id='3'><part type='wordlog'><position>99</position>"
                + "<before>x</before><after>y</after></part></event>"), "ambiguous insertion");
        rejects("<!DOCTYPE log [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]>" + file(key), "external entity");
        rejects(file(key).replace("<value>a</value>", "<value>&secret;</value>"), "undefined entity");
        System.out.println("Inputlog: overlapping keys, Word newlines, malformed and unsupported files checked");
    }
    private static String file(String events) {
        return "<log><meta><entry><key>__LogCreationTimeStamp</key><value>1000</value></entry>"
                + "<entry><key>__LogRelativeCreationDate</key><value>100</value></entry></meta><session/>" + events + "</log>";
    }
    private static String key(int position, int length, String key, String value, boolean replay, int start, int end) {
        return "<event type='keyboard' id='" + start + "'><part type='wordlog'><position>" + position
                + "</position><documentLength>" + length + "</documentLength><replay>" + replay
                + "</replay></part><part type='winlog'><startTime>" + start + "</startTime><endTime>" + end
                + "</endTime><key>" + key + "</key><value>" + value + "</value><keyboardstate/></part></event>";
    }
    private static void rejects(String xml, String message) throws Exception {
        try { InputlogImporter.load(xml); } catch (Exception expected) { return; }
        throw new AssertionError("Inputlog accepted " + message);
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
