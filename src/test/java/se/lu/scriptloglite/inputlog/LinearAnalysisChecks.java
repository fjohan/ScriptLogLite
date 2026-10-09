package se.lu.scriptloglite.inputlog;

import se.lu.scriptloglite.AnalysisTestSupport;
import se.lu.scriptloglite.DirectoryHistory;
import se.lu.scriptloglite.EditEvent;
import se.lu.scriptloglite.EventType;
import se.lu.scriptloglite.KeyLogEvent;
import se.lu.scriptloglite.LogEvent;
import se.lu.scriptloglite.ReplayLog;
import se.lu.scriptloglite.SessionEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;

public final class LinearAnalysisChecks {
    public static void run() throws Exception {
        Path input = Path.of("JF_92.idfx"), reference = Path.of("JF_20261008_92_LA_PT200_FL60.html");
        if (Files.exists(input) && Files.exists(reference)) {
            ReplayLog log = ReplayLog.load(input, -1);
            LinearAnalysis internal = LinearAnalysis.analyze(log, 200, 60), source = LinearAnalysis.analyzeSource(log, 200, 60);
            String fixture = Files.readString(reference);
            LinearAnalysisReport.Comparison comparison = LinearAnalysisReport.compare(source, fixture);
            check(comparison.equal == 2 && comparison.different == 0 && comparison.missing == 0 && comparison.extra == 0, "source PT200/FL60 score matches both fixture rows exactly");
            check(internal.intervals.size() == 2 && internal.intervals.get(0).index == 0 && internal.intervals.get(1).index == 1, "internal two fixed 60-second intervals");
            check(cells(internal).equals(cells(LinearAnalysis.analyze(AnalysisTestSupport.jsonRoundTrip(log), 200, 60))), "internal JSON round trip");
            check(cells(internal).equals(cells(LinearAnalysis.analyze(AnalysisTestSupport.rawRoundTrip(log), 200, 60))), "internal raw round trip");
            check(cells(source).equals(cells(LinearAnalysis.analyzeSource(AnalysisTestSupport.rawRoundTrip(log), 200, 60))), "source provenance survives raw");
            ReplayLog poisoned = new ReplayLog(log.events); poisoned.metadata.putAll(log.metadata);
            poisoned.metadata.put("inputlogGeneralEvents", List.of(Map.of("invalid", "POISON")));
            check(cells(internal).equals(cells(LinearAnalysis.analyze(poisoned, 200, 60))), "raw source keyboard/edit payload cannot change internal score");
            check(score(internal).contains("[REMOVE 195:209]"), "actual selected-range cut is explicit");
            check(LinearAnalysisReport.compare(source, fixture.replaceFirst("\\{750\\}", "{751}")).different == 1, "comparison detects changed pause value");
            rejects(() -> LinearAnalysisReport.compare(LinearAnalysis.analyze(log, 300, 60), fixture), "reference PT mismatch");
            rejects(() -> LinearAnalysisReport.compare(LinearAnalysis.analyze(log, 200, 30), fixture), "reference FL mismatch");
            check(LinearAnalysisReport.compare(source, LinearAnalysisReport.html(source, "<unsafe>&title", null)).equal == 2, "generated score rows read back");
            check(LinearAnalysisReport.html(source, "<unsafe>&title", null).contains("&lt;unsafe&gt;&amp;title"), "escaped title");
            List<LogEvent> continued = new ArrayList<>(log.events);
            continued.add(new EditEvent(log.events.get(log.events.size() - 1).time.plusSeconds(2), EventType.INSERT, log.finalText.length(), "", "more"));
            ReplayLog longer = new ReplayLog(continued); longer.metadata.putAll(log.metadata);
            check(score(LinearAnalysis.analyze(longer, 200, 60)).contains("<more>"), "native continuation appears in score");
            check(cells(source).equals(cells(LinearAnalysis.analyzeSource(longer, 200, 60))), "source mode excludes native continuation");
        }
        Path nativeJson = Path.of("exp_subj_json_1.json"), nativeRaw = Path.of("exp_subj_raw_1.txt");
        if (Files.exists(nativeJson) && Files.exists(nativeRaw)) check(cells(LinearAnalysis.analyze(ReplayLog.load(nativeJson, -1), 200, 60))
                .equals(cells(LinearAnalysis.analyze(ReplayLog.load(nativeRaw, -1), 200, 60))), "native JSON and raw samples agree");
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        ReplayLog boundary = new ReplayLog(List.of(new SessionEvent(start, ""),
                press(start, 0, 'a'), new EditEvent(start.plusMillis(1), EventType.INSERT, 0, "", "a"),
                press(start, 199, 'b'), new EditEvent(start.plusMillis(200), EventType.INSERT, 1, "", "b"),
                press(start, 399, 'c'), new EditEvent(start.plusMillis(400), EventType.INSERT, 2, "", "c"),
                press(start, 1000, 'd'), new EditEvent(start.plusMillis(1001), EventType.INSERT, 3, "", "d")));
        LinearAnalysis oneSecond = LinearAnalysis.analyze(boundary, 200, 1);
        check(oneSecond.intervals.get(0).score().equals("ab{200}c"), "PT is inclusive; 199 ms hidden and 200 ms shown");
        check(oneSecond.intervals.get(1).score().equals("{601}d"), "exact interval boundary places pause and arriving action in new row");
        check(oneSecond.log.finalText.equals("abcd") && !score(oneSecond).contains("<a>"), "associated edits do not duplicate typing");
        check(score(LinearAnalysis.analyze(boundary, 0, 1)).contains("{199}"), "PT zero displays all positive gaps");
        check(!score(LinearAnalysis.analyze(boundary, 10000, 1)).contains("{"), "high PT suppresses pause marks");
        ReplayLog longGap = new ReplayLog(List.of(new SessionEvent(start, ""), press(start, 0, 'a'), press(start, 5000000000L, 'b')));
        LinearAnalysis sparse = LinearAnalysis.analyze(longGap, 200, 1);
        check(sparse.intervals.size() == 2 && sparse.intervals.get(1).index == 5000000L, "long inactivity does not allocate millions of empty rows");
        check(LinearAnalysisReport.html(sparse, "Long", null).contains("No score actions"), "sparse gap visible in report");
        ReplayLog programmatic = new ReplayLog(List.of(new SessionEvent(start, "old"),
                new EditEvent(start.plusSeconds(1), EventType.REPLACE, 0, "old", "<&>"),
                new EditEvent(start.plusSeconds(3), EventType.INSERT, 3, "", "\nmore")));
        LinearAnalysis edits = LinearAnalysis.analyze(programmatic, 200, 60);
        check(score(edits).contains("{2000}") && score(edits).contains("[REPLACE 0:3→"), "unassociated edits have real timestamps and pauses");
        check(LinearAnalysisReport.html(edits, "Literal", null).contains("&amp;"), "literal edit HTML escaped");
        rejects(() -> LinearAnalysis.analyze(boundary, -1, 60), "negative PT rejected");
        rejects(() -> LinearAnalysis.analyze(boundary, 200, 0), "zero FL rejected");
        rejects(() -> LinearAnalysis.analyze(boundary, 200, Long.MAX_VALUE), "overflowing FL rejected");
        rejects(() -> LinearAnalysis.analyzeSource(boundary, 200, 60), "source mode requires provenance");
        rejects(() -> LinearAnalysisReport.compare(oneSecond, "<html>no score</html>"), "malformed reference rejected");
        check(LinearAnalysis.analyze(new ReplayLog(List.of(new SessionEvent(start, "before"))), 200, 60).intervals.isEmpty(), "initial text is not invented writing activity");
        SwingUtilities.invokeAndWait(() -> check(new LinearAnalysisPanel(oneSecond, "Synthetic", new DirectoryHistory(Path.of("target", "linear-test.properties"))).getComponentCount() == 2,
                "parameterized score tab builds headlessly"));
        System.out.println("Linear Analysis: source PT200/FL60 fixture exact; internal JSON/raw, parameter boundaries, explicit edits and sparse intervals checked");
    }
    private static KeyLogEvent press(Instant start, long elapsed, char value) {
        int code = KeyEvent.getExtendedKeyCodeForChar(value);
        return new KeyLogEvent(start.plusMillis(elapsed), EventType.KEY_PRESSED, code, KeyEvent.getKeyText(code), "" + value, 0, "", 1);
    }
    private static List<String> cells(LinearAnalysis analysis) {
        List<String> cells = new ArrayList<>(); for (LinearAnalysis.Interval interval : analysis.intervals) cells.add(interval.index + "=" + interval.score()); return cells;
    }
    private static String score(LinearAnalysis analysis) { StringBuilder out = new StringBuilder(); for (LinearAnalysis.Interval interval : analysis.intervals) out.append(interval.score()); return out.toString(); }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked checked, String message) throws Exception {
        try { checked.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Linear Analysis: " + message);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError("Linear Analysis: " + message); }
}
