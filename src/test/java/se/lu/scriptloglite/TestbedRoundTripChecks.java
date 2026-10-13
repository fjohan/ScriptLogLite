package se.lu.scriptloglite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import se.lu.scriptloglite.conversions.Conversions;
import se.lu.scriptloglite.conversions.ConversionFormat;

/** Semantic comparisons: native exact history, IDFX observations, web normalization. */
final class TestbedRoundTripChecks {
    static void run() throws Exception {
        Path input = Path.of("testbed-sll.json"); if (!Files.exists(input)) return;
        ReplayLog source = ReplayLog.load(input, -1);
        check(source.events.size() == 3277 && edits(source).size() == 455, "fixture coverage changed; review baseline");
        ScriptLogLiteChecks.checkHistory(source, AnalysisTestSupport.jsonRoundTrip(source), "testbed native JSON exact");
        ScriptLogLiteChecks.checkHistory(source, AnalysisTestSupport.rawRoundTrip(source), "testbed native raw exact");
        Path directory = Files.createTempDirectory("sll-testbed-");
        try {
            for (ConversionFormat target : List.of(ConversionFormat.IDFX, ConversionFormat.WEBSCRIPTLOG)) {
                Path exported = directory.resolve("converted" + target.suffix), back = directory.resolve(target.name() + ".json");
                var first = Conversions.run(new Conversions.Request(input, exported, ConversionFormat.JSON, target, false, false, true, true));
                check(first.converted == 1 && first.failed == 0, "export " + target);
                var second = Conversions.run(new Conversions.Request(exported, back, target, ConversionFormat.JSON, false, false, true, true));
                check(second.converted == 1 && second.failed == 0, "reimport " + target);
                ReplayLog result = ReplayLog.load(back, -1); boolean web = target == ConversionFormat.WEBSCRIPTLOG;
                check(result.finalText.equals(source.finalText), "final text " + target);
                List<EditEvent> a = edits(source), b = edits(result);
                check(a.size() == b.size(), "edit count " + target);
                ReplayCursor original = new ReplayCursor(source), converted = new ReplayCursor(result);
                List<Integer> ai = editIndices(source), bi = editIndices(result);
                for (int i = 0; i < a.size(); i++) {
                    EditEvent x = a.get(i), y = b.get(i);
                    check(x.offset == y.offset && x.removed.equals(y.removed) && java.util.Objects.equals(x.inserted, y.inserted), "edit payload " + target + " #" + i);
                    timing(x, y, web, "edit " + i);
                    original.seek(ai.get(i)); converted.seek(bi.get(i));
                    check(original.state().text.equals(converted.state().text), "every edit's text state " + target);
                }
                converted.seek(result.events.size() - 1);
                for (int i = result.events.size() - 1; i >= 0; i--) converted.seek(i);
                check(converted.state().text.equals(result.initialText), "reverse replay " + target);
                List<KeyLogEvent> ak = keys(source), bk = keys(result); check(ak.size() == bk.size(), "all key events " + target);
                for (int i = 0; i < ak.size(); i++) {
                    KeyLogEvent x = ak.get(i), y = bk.get(i); check(x.type == y.type, "key action " + target); timing(x, y, web, "key " + i);
                    if (x.keyChar != null && x.keyChar.length() == 1 && !Character.isISOControl(x.keyChar.charAt(0))) check(x.keyChar.equals(y.keyChar), "observed printable key " + target);
                    if (web) check(!"undefined".equals(y.keyChar), "all testbed browser identities available");
                }
                List<CaretLogEvent> ac = carets(source), bc = carets(result);
                if (web) {
                    check(bc.size() == ac.size() + 1, "web closing marker");
                    for (int i = 0; i < ac.size(); i++) {
                        CaretLogEvent x = ac.get(i), y = bc.get(i);
                        check(y.mark == Math.min(x.dot, x.mark) && y.dot == Math.max(x.dot, x.mark), "normalized selection range"); timing(x, y, true, "caret " + i);
                    }
                    List<ScrollLogEvent> as = scrolls(source), bs = scrolls(result); check(as.size() == bs.size(), "web vertical scroll count");
                    for (int i = 0; i < as.size(); i++) { check(as.get(i).y == bs.get(i).y, "web vertical scroll offset"); timing(as.get(i), bs.get(i), true, "scroll " + i); }
                } else {
                    int matched = 0;
                    for (CaretLogEvent c : bc) if (matched < ac.size()) {
                        CaretLogEvent expected = ac.get(matched);
                        if (c.dot == expected.dot && c.mark == expected.mark && c.time.equals(expected.time)) matched++;
                    }
                    check(matched == ac.size(), "all original directed carets present in IDFX");
                    List<ScrollLogEvent> as = scrolls(source), bs = scrolls(result); check(as.size() == bs.size(), "IDFX scroll count");
                    for (int i = 0; i < as.size(); i++) check(as.get(i).x == bs.get(i).x && as.get(i).y == bs.get(i).y && as.get(i).time.equals(bs.get(i).time), "IDFX viewport exact");
                }
            }
            Path plain = directory.resolve("plain.idfx");
            var plainResult = Conversions.run(new Conversions.Request(input, plain, ConversionFormat.JSON, ConversionFormat.IDFX, false, false, true, false));
            check(plainResult.converted == 1 && plainResult.failed == 0, "plain IDFX export");
            ReplayLog plainLog = ReplayLog.load(plain, -1);
            List<Integer> originalEdits = editIndices(source), plainEdits = editIndices(plainLog);
            check(originalEdits.size() == plainEdits.size(), "plain IDFX edit count");
            ReplayCursor a = new ReplayCursor(source), b = new ReplayCursor(plainLog);
            for (int i = 0; i < originalEdits.size(); i++) {
                a.seek(originalEdits.get(i)); b.seek(plainEdits.get(i));
                check(a.state().text.equals(b.state().text), "plain IDFX every text state");
            }
            b.seek(0); check(b.state().text.equals(plainLog.initialText), "plain IDFX reverse replay");
        } finally { try (var paths = Files.walk(directory)) { for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path); } }
        System.out.println("Action testbed: native JSON/raw exact; IDFX/web all 455 edit payloads and text states, 1822 key timings/actions, directed/normalized selections and reverse replay checked");
    }
    private static void timing(LogEvent a, LogEvent b, boolean web, String label) {
        check(web ? Math.abs(Duration.between(a.time, b.time).toNanos()) <= 2000000 : a.time.equals(b.time), "timing " + label);
    }
    private static List<Integer> editIndices(ReplayLog log) { List<Integer> result = new ArrayList<>(); for (int i=0;i<log.events.size();i++) if (log.events.get(i) instanceof EditEvent) result.add(i); return result; }
    private static List<EditEvent> edits(ReplayLog log) { List<EditEvent> list = new ArrayList<>(); for (LogEvent e : log.events) if (e instanceof EditEvent) list.add((EditEvent)e); return list; }
    private static List<KeyLogEvent> keys(ReplayLog log) { List<KeyLogEvent> list = new ArrayList<>(); for (LogEvent e : log.events) if (e instanceof KeyLogEvent) list.add((KeyLogEvent)e); return list; }
    private static List<CaretLogEvent> carets(ReplayLog log) { List<CaretLogEvent> list = new ArrayList<>(); for (LogEvent e : log.events) if (e instanceof CaretLogEvent) list.add((CaretLogEvent)e); return list; }
    private static List<ScrollLogEvent> scrolls(ReplayLog log) { List<ScrollLogEvent> list = new ArrayList<>(); for (LogEvent e : log.events) if (e instanceof ScrollLogEvent) list.add((ScrollLogEvent)e); return list; }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError("Action testbed: " + label); }
}
