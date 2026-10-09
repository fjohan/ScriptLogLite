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
        exportChecks();
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
                Files.deleteIfExists(folder.resolve("converted.idfx")); Files.deleteIfExists(folder.resolve("converted.json")); Files.deleteIfExists(folder.resolve("converted.txt")); Files.delete(folder);
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
        String outsideKey = "<event type='keyboard' id='outside'><part type='winlog'><startTime>110</startTime><endTime>115</endTime>"
                + "<key>VK_T</key><value>t</value><keyboardstate/></part></event>";
        String externalFocus = "<event type='focus' id='focus'><part type='winlog'><startTime>105</startTime><endTime>105</endTime><title>Browser</title></part></event>";
        ReplayLog external = InputlogImporter.load(file(outsideKey + externalFocus + key(0, 1, "VK_A", "a", true, 120, 130)));
        check(external.finalText.equals("a") && external.events.stream().filter(e -> e instanceof KeyLogEvent && e.type == EventType.KEY_PRESSED).count() == 1,
                "timestamped external keyboard activity is retained without creating document keys or edits");
        check(((List<?>) external.metadata.get("inputlogAncillaryEvents")).size() == 2, "external key and focus provenance retained");
        rejects(file(outsideKey), "missing coordinates without external focus");
        rejects(file(outsideKey + externalFocus.replace("Browser", "WordLog MainDoc")), "missing coordinates within main document");
        rejects(file(key(0, 4, "VK_A", "a", true, 110, 120)), "pre-existing content");
        rejects(file("<event type='unknown' id='9'/>"), "unsupported source event");
        rejects(file(key + "<event type='insert' id='3'><part type='wordlog'><position>99</position>"
                + "<before>x</before><after>y</after></part></event>"), "ambiguous insertion");
        rejects("<!DOCTYPE log [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]>" + file(key), "external entity");
        rejects(file(key).replace("<value>a</value>", "<value>&secret;</value>"), "undefined entity");
        System.out.println("Inputlog: overlapping keys, Word newlines, malformed and unsupported files checked");
    }
    private static void exportChecks() throws Exception {
        Path sample = Path.of("exp_subj_json_1.json");
        if (!Files.exists(sample)) return;
        ReplayLog original = ReplayLog.load(sample, -1);
        String xml = InputlogExporter.export(original);
        checkInputlogHeaderReadSteps(xml);
        String plain = InputlogExporter.export(original, false);
        check(!plain.contains("<label") && !plain.contains("__ScriptLogLite"), "plain IDFX omits labels and provenance extensions");
        checkInputlogHeaderReadSteps(plain);
        check(InputlogImporter.load(plain).finalText.equals(original.finalText), "plain IDFX reconstructs sample text");
        check(!plain.contains("ScriptLogLite.view"), "plain export omits viewport-only selections");
        Path optionsDirectory = Files.createTempDirectory("idfx-options-check");
        try {
            DirectoryHistory preferences = new DirectoryHistory(optionsDirectory.resolve("preferences.properties"));
            check(preferences.idfxExtensions(), "IDFX provenance default enabled");
            preferences.rememberIdfxExtensions(false);
            check(!new DirectoryHistory(preferences.file).idfxExtensions(), "IDFX provenance preference persists");
            RecordingSession session = new RecordingSession();
            session.restore(original, original.events.get(original.events.size() - 1).time);
            session.setFormats(java.util.EnumSet.of(LogFormat.IDFX));
            session.setIdfxExtensions(false);
            SaveSnapshot snapshot = session.snapshot();
            session.setIdfxExtensions(true);
            Path target = optionsDirectory.resolve("plain.idfx");
            snapshot.save(target);
            check(!Files.readString(target).contains("<label"), "background snapshot retains captured IDFX option");
            check(ReplayLog.load(target, -1).finalText.equals(original.finalText), "saved plain IDFX");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.list(optionsDirectory)) {
                for (Path path : (Iterable<Path>) files::iterator) Files.delete(path);
            }
            Files.delete(optionsDirectory);
        }
        ReplayLog imported = InputlogImporter.load(xml);
        check(imported.finalText.equals(original.finalText), "exported sample reconstructs final text");
        check(imported.metadata.get("fontFamily").equals("Calibri") && imported.metadata.get("fontSize").toString().equals("36"),
                "IDFX header extension retains appearance");
        check(JsonLogCodec.number(imported.metadata, "endTime") == JsonLogCodec.number(original.metadata, "endTime"), "IDFX header end precision");
        check(imported.events.stream().filter(e -> e instanceof ScrollLogEvent).count() == 24, "labelled viewport restored");
        check(imported.events.stream().filter(e -> e instanceof KeyLogEvent && e.type == EventType.KEY_PRESSED).count() == 321,
                "native keyboard presses");
        check(imported.events.stream().filter(e -> e instanceof KeyLogEvent && e.type == EventType.KEY_RELEASED).count() == 321,
                "paired releases");
        // Strip every ScriptLogLite extension. The standard Inputlog fields alone
        // must still reconstruct the document, so labels cannot conceal bad edits.
        String nativeXml = xml.replaceAll("<label[^>]*>.*?</label>", "")
                .replaceAll("(?s)<entry>\\s*<key>__ScriptLogLite[^<]*</key>\\s*<value>.*?</value>\\s*</entry>", "");
        ReplayLog nativeLog = InputlogImporter.load(nativeXml);
        check(nativeLog.finalText.equals(original.finalText), "native IDFX fields alone reconstruct text");
        check(xml.contains("<replay>True</replay>") && xml.contains("type=\"replacement\""), "typing and replacement mappings");
        ReplayCursor cursor = new ReplayCursor(nativeLog);
        cursor.seek(nativeLog.events.size() - 1); cursor.seek(0);
        check(cursor.state().text.isEmpty(), "exported native history reversible");
        for (String value : List.of("a<&\\u000a\n😀", "\b\t\u0000")) {
            ReplayLog special = new ReplayLog(List.of(new SessionEvent(java.time.Instant.EPOCH, ""),
                    new EditEvent(java.time.Instant.EPOCH.plusMillis(1), EventType.INSERT, 0, "", value)));
            check(InputlogImporter.load(InputlogExporter.export(special)).finalText.equals(value), "IDFX text escaping");
        }
        ReplayLog initial = new ReplayLog(List.of(new SessionEvent(java.time.Instant.EPOCH, "existing")));
        check(InputlogImporter.load(InputlogExporter.export(initial)).finalText.equals("existing"), "initial text bootstrapped");
        Path raw = Path.of("exp_subj_raw_1.txt");
        if (Files.exists(raw)) check(InputlogImporter.load(InputlogExporter.export(ReplayLog.load(raw, -1))).finalText.equals(original.finalText),
                "raw input exports same IDFX text");
        System.out.println("Inputlog export: JSON/raw sample, native fields, key pairing, geometry metadata, escaping and reverse replay checked");
    }
    // Model the unconditional Read() calls in Inputlog's SessionIdentification.ReadXml.
    // A generic DOM parse accepts compact XML and therefore cannot catch this incompatibility.
    private static void checkInputlogHeaderReadSteps(String xml) throws Exception {
        javax.xml.stream.XMLStreamReader reader = javax.xml.stream.XMLInputFactory.newFactory()
                .createXMLStreamReader(new java.io.StringReader(xml));
        try {
            while (!(reader.isStartElement() && reader.getLocalName().equals("meta"))) reader.next();
            reader.next();
            while (reader.isCharacters() && reader.isWhiteSpace()) reader.next();
            int entries = 0;
            while (reader.isStartElement() && reader.getLocalName().equals("entry")) {
                reader.next();
                while (reader.isCharacters() && reader.isWhiteSpace()) reader.next();
                check(reader.isStartElement() && reader.getLocalName().equals("key"), "Inputlog header key");
                reader.getElementText(); reader.next();
                check(reader.isCharacters() && reader.isWhiteSpace(), "Inputlog requires whitespace after key");
                reader.next();
                check(reader.isStartElement() && reader.getLocalName().equals("value"), "Inputlog header value");
                reader.getElementText(); reader.next();
                check(reader.isCharacters() && reader.isWhiteSpace(), "Inputlog requires whitespace after value");
                reader.next();
                check(reader.isEndElement() && reader.getLocalName().equals("entry"), "Inputlog header entry end");
                reader.next();
                check(reader.isCharacters() && reader.isWhiteSpace(), "Inputlog requires whitespace after entry");
                reader.next(); entries++;
            }
            check(entries >= 6 && reader.isEndElement() && reader.getLocalName().equals("meta"), "Inputlog reaches meta end");
        } finally { reader.close(); }
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
