package se.lu.scriptloglite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class GeneralAnalysisChecks {
    static void run() throws Exception {
        Path input = Path.of("JF_92.idfx"), reference = Path.of("JF_20261008_92_GA.html");
        if (Files.exists(input) && Files.exists(reference)) {
            ReplayLog imported = ReplayLog.load(input, -1);
            GeneralAnalysis analysis = GeneralAnalysis.analyzeSource(imported);
            GeneralAnalysis internal = GeneralAnalysis.analyze(imported);
            check(internal.internal && internal.checkedKeys == 335 && internal.audit.isEmpty(), "direct internal calculation with independent source audit");
            check(analysis.checkedKeys == 335 && analysis.audit.isEmpty(), "independent conversion checks");
            check(analysis.rows.size() == 331, "grouped General Analysis row count");
            GeneralAnalysisReport.Comparison comparison = GeneralAnalysisReport.compare(analysis, Files.readString(reference));
            check(comparison.matched == 331 && comparison.extra == 0 && comparison.missing == 0, "reference rows matched");
            for (int column = 0; column < 20; column++) check(comparison.equal[column] == 331 && comparison.different[column] == 0,
                    "reference column " + GeneralAnalysis.COLUMNS.get(column));
            // The expected report is a fixture only: it is never read during calculation.
            String generated = GeneralAnalysisReport.html(analysis, "<unsafe>&title", comparison);
            check(generated.contains("&lt;unsafe&gt;&amp;title"), "report escapes text");
            check(GeneralAnalysisReport.readRows(generated).size() == 331, "generated event table readable");
            GeneralAnalysis restored = GeneralAnalysis.analyzeSource(JsonLogCodec.load(JsonLogCodec.export(imported.events, imported.metadata)));
            check(GeneralAnalysisReport.compare(restored, Files.readString(reference)).differences.isEmpty(), "saved JSON retains analysis provenance");
            GeneralAnalysis raw = GeneralAnalysis.analyzeSource(RawLogCodec.load(RawLogCodec.export(imported)));
            check(raw.audit.isEmpty() && GeneralAnalysisReport.compare(raw, Files.readString(reference)).differences.isEmpty(), "saved raw retains analysis provenance");
            GeneralAnalysis.Row first = analysis.rows.get(0);
            first.production++;
            GeneralAnalysisReport.Comparison corrupted = GeneralAnalysisReport.compare(analysis, Files.readString(reference));
            check(corrupted.different[7] == 1 && corrupted.equal[7] == 330, "comparison detects one bad production cell");
            first.production--;
            List<LogEvent> modified = new ArrayList<>(imported.events);
            for (int i = 0; i < modified.size(); i++) if (modified.get(i) instanceof KeyLogEvent && modified.get(i).type == EventType.KEY_PRESSED) {
                KeyLogEvent key = (KeyLogEvent) modified.get(i);
                modified.set(i, new KeyLogEvent(key.time, key.type, key.keyCode, key.keyText, "wrong", key.modifiers, key.modifiersText, key.keyLocation)); break;
            }
            ReplayLog bad = new ReplayLog(modified); bad.metadata.putAll(imported.metadata);
            check(!GeneralAnalysis.analyze(bad).audit.isEmpty(), "audit detects changed internal key despite identical source report rows");
            List<LogEvent> wrongText = new ArrayList<>(imported.events);
            for (int i = 0; i < wrongText.size(); i++) if (wrongText.get(i) instanceof EditEvent) {
                EditEvent edit = (EditEvent) wrongText.get(i);
                wrongText.set(i, new EditEvent(edit.time, edit.type, edit.offset, edit.removed, "Q")); break;
            }
            ReplayLog badText = new ReplayLog(wrongText); badText.metadata.putAll(imported.metadata);
            check(!GeneralAnalysis.analyze(badText).audit.isEmpty(), "audit detects wrong inserted text even when every document length matches");
            check(GeneralAnalysis.analyze(badText).rows.stream().anyMatch(r -> r.output.equals("[Q]")), "internal table exposes changed text rather than using source payload");
            List<LogEvent> continuedEvents = new ArrayList<>(imported.events);
            continuedEvents.add(new EditEvent(imported.events.get(imported.events.size() - 1).time.plusSeconds(1), EventType.INSERT,
                    imported.finalText.length(), "", " continued"));
            ReplayLog continued = new ReplayLog(continuedEvents); continued.metadata.putAll(imported.metadata);
            check(GeneralAnalysis.analyze(continued).log.finalText.endsWith(" continued"), "continued recording does not analyse stale imported prefix");
            check(GeneralAnalysis.analyzeSource(continued).log.finalText.equals(imported.finalText), "source mode explicitly limits itself to the imported session");
            ReplayLog restoredInternal = JsonLogCodec.load(JsonLogCodec.export(imported.events, imported.metadata));
            check(cells(internal).equals(cells(GeneralAnalysis.analyze(restoredInternal))), "internal IDFX/JSON tables identical");
            check(cells(internal).equals(cells(GeneralAnalysis.analyze(RawLogCodec.load(RawLogCodec.export(imported))))), "internal IDFX/raw tables identical");
            ReplayLog poisoned = new ReplayLog(imported.events); poisoned.metadata.putAll(imported.metadata);
            Object records = new Json(Json.stringify(imported.metadata.get("inputlogGeneralEvents"), 0)).parse();
            for (Object item : (List<?>) records) {
                Map<?, ?> record = (Map<?, ?>) item;
                for (Object part : (List<?>) record.get("parts")) for (Object field : (List<?>) ((Map<?, ?>) part).get("fields")) {
                    @SuppressWarnings("unchecked") Map<String, Object> values = (Map<String, Object>) field;
                    if (List.of("position", "documentLength", "newtext", "before", "after", "value").contains(values.get("name"))) {
                        values.put("value", values.get("name").equals("position") || values.get("name").equals("documentLength") ? "999" : "POISON");
                    }
                }
            }
            poisoned.metadata.put("inputlogGeneralEvents", records);
            check(cells(internal).equals(cells(GeneralAnalysis.analyze(poisoned))), "source keyboard/edit payload cannot determine internal table");
            ReplayLog stripped = new ReplayLog(imported.events); stripped.metadata.putAll(imported.metadata);
            stripped.metadata.remove("inputlogGeneralEvents"); stripped.metadata.remove("inputlogGeneralEventCount");
            check(cells(internal).equals(cells(GeneralAnalysis.analyze(stripped))), "internal calculation does not require retained source keyboard/edit records");
            GeneralAnalysisReport.Comparison nativeComparison = GeneralAnalysisReport.compare(internal, Files.readString(reference));
            check(nativeComparison.matched == 329 && nativeComparison.missing == 2 && nativeComparison.extra == 1, "native conversion differences are explicit");
            System.out.println("General Analysis: source-mode 331 × 20 exact; internal tables survive JSON/raw and ignore source keyboard/edit payloads");
        }
        Path json = Path.of("exp_subj_json_1.json"), rawFile = Path.of("exp_subj_raw_1.txt");
        if (Files.exists(json) && Files.exists(rawFile)) {
            GeneralAnalysis jsonAnalysis = GeneralAnalysis.analyze(ReplayLog.load(json, -1));
            check(cells(jsonAnalysis).equals(cells(GeneralAnalysis.analyze(ReplayLog.load(rawFile, -1)))), "native JSON/raw samples yield the same internal table");
        }
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        ReplayLog repeat = new ReplayLog(List.of(new SessionEvent(start, ""),
                new KeyLogEvent(start.plusMillis(100), EventType.KEY_PRESSED, 65, "A", "a", 0, "", 1, "held"),
                new EditEvent(start.plusMillis(102), EventType.INSERT, 0, "", "a"),
                new CaretLogEvent(start.plusMillis(103), 1, 1),
                new KeyLogEvent(start.plusMillis(120), EventType.KEY_PRESSED, 65, "A", "a", 0, "", 1, "repeat"),
                new EditEvent(start.plusMillis(121), EventType.INSERT, 1, "", "a"),
                new CaretLogEvent(start.plusMillis(122), 2, 2),
                new KeyLogEvent(start.plusMillis(150), EventType.KEY_RELEASED, 65, "A", "a", 0, "", 1, "held")));
        Map<Integer, Integer> pairs = KeyPairs.pair(repeat.events);
        check(pairs.size() == 1 && pairs.get(1) == 7, "explicit stroke identity preserves parent release through auto-repeat");
        GeneralAnalysis repeated = GeneralAnalysis.analyze(repeat);
        check(repeated.rows.get(0).action == 50 && repeated.rows.get(1).action == 0, "missing repeat release is not paired with a future unrelated release");
        ReplayLog jsonRepeat = JsonLogCodec.load(JsonLogCodec.export(repeat.events, repeat.metadata));
        ReplayLog rawRepeat = RawLogCodec.load(RawLogCodec.export(repeat));
        check(KeyPairs.pair(jsonRepeat.events).equals(pairs) && KeyPairs.pair(rawRepeat.events).equals(pairs), "stroke identity survives JSON/raw");
        check(cells(repeated).equals(cells(GeneralAnalysis.analyze(jsonRepeat))) && cells(repeated).equals(cells(GeneralAnalysis.analyze(rawRepeat))), "repeat timing tables survive JSON/raw");

        ReplayLog nativeLog = new ReplayLog(List.of(new SessionEvent(start, ""),
                new KeyLogEvent(start.plusMillis(100), EventType.KEY_PRESSED, 65, "A", "a", 0, "", 1),
                new EditEvent(start.plusMillis(102), EventType.INSERT, 0, "", "a"),
                new CaretLogEvent(start.plusMillis(103), 1, 1),
                new KeyLogEvent(start.plusMillis(150), EventType.KEY_RELEASED, 65, "A", "a", 0, "", 1)));
        GeneralAnalysis nativeAnalysis = GeneralAnalysis.analyze(nativeLog);
        check(nativeAnalysis.log.finalText.equals("a") && nativeAnalysis.checkedKeys == 0, "native history direct analysis");
        check(nativeAnalysis.rows.stream().anyMatch(r -> r.type.equals("keyboard") && r.action == 50), "paired key action duration");
        try { GeneralAnalysisReport.compare(nativeAnalysis, "<html><body>Not a report</body></html>"); throw new AssertionError("Invalid reference accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    static List<List<String>> cells(GeneralAnalysis analysis) {
        List<List<String>> result = new ArrayList<>(); for (GeneralAnalysis.Row row : analysis.rows) result.add(row.cells()); return result;
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
