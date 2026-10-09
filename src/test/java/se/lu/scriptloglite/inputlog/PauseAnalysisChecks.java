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

public final class PauseAnalysisChecks {
    public static void run() throws Exception {
        Path file = Path.of("JF_92.idfx"), fixture = Path.of("JF_20261008_92_PA_PT200_FN5.html");
        if (Files.exists(file) && Files.exists(fixture)) {
            ReplayLog log = ReplayLog.load(file, -1);
            PauseAnalysis source = PauseAnalysis.analyzeSource(log, 200, 5), internal = PauseAnalysis.analyze(log, 200, 5);
            String reference = Files.readString(fixture);
            PauseAnalysisReport.Comparison comparison = PauseAnalysisReport.compare(source, reference);
            check(comparison.equal == 198 && comparison.different == 0 && comparison.extra == 0 && comparison.missing == 0, "all 198 source fixture metrics agree");
            check(metric(source, "General", "Total Number of Pauses").equals("151"), "reference pause count");
            check(metric(source, "Overview", "Total Pause Time (s)").equals("85.345"), "reference total pause time");
            check(metric(source, "P-Bursts at 2000 ms", "Number of P-Bursts").equals("3"), "reference P-bursts");
            check(metrics(internal).equals(metrics(PauseAnalysis.analyze(AnalysisTestSupport.jsonRoundTrip(log), 200, 5))), "internal JSON persistence");
            check(metrics(internal).equals(metrics(PauseAnalysis.analyze(AnalysisTestSupport.rawRoundTrip(log), 200, 5))), "internal raw persistence");
            check(metrics(source).equals(metrics(PauseAnalysis.analyzeSource(AnalysisTestSupport.rawRoundTrip(log), 200, 5))), "raw retained source persistence");
            ReplayLog poisoned = new ReplayLog(log.events); poisoned.metadata.putAll(log.metadata);
            poisoned.metadata.put("inputlogGeneralEvents", List.of(Map.of("invalid", "POISON")));
            check(metrics(internal).equals(metrics(PauseAnalysis.analyze(poisoned, 200, 5))), "internal metrics do not read raw source keys/edits");
            check(PauseAnalysisReport.compare(source, reference.replace("85.345", "85.346")).different == 1, "changed metric detected");
            rejects(() -> PauseAnalysisReport.compare(PauseAnalysis.analyzeSource(log, 300, 5), reference), "PT mismatch rejected");
            rejects(() -> PauseAnalysisReport.compare(PauseAnalysis.analyzeSource(log, 200, 4), reference), "FN mismatch rejected");
            rejects(() -> PauseAnalysisReport.compare(PauseAnalysis.analyzeSource(log, 200, 5, 1500), reference), "PB mismatch rejected");
            check(PauseAnalysisReport.compare(source, PauseAnalysisReport.html(source, "<unsafe>&title", null)).equal == 198, "generated metric tables read back");
            check(PauseAnalysisReport.html(source, "<unsafe>&title", null).contains("&lt;unsafe&gt;&amp;title"), "HTML escaping");
            List<LogEvent> events = new ArrayList<>(log.events);
            events.add(new EditEvent(events.get(events.size() - 1).time.plusSeconds(30), EventType.INSERT, log.finalText.length(), "", "continued"));
            ReplayLog continued = new ReplayLog(events); continued.metadata.putAll(log.metadata);
            check(metrics(source).equals(metrics(PauseAnalysis.analyzeSource(continued, 200, 5))), "source mode stops at imported boundary");
            check(PauseAnalysis.analyze(continued, 200, 5).processMillis > internal.processMillis, "continuation included internally");
            SwingUtilities.invokeAndWait(() -> check(new PauseAnalysisPanel(internal, "test", new DirectoryHistory(Path.of("target", "pause-test.properties"))).getComponentCount() == 2, "panel builds headlessly"));
        }
        Path file97 = Path.of("JF_97.idfx"), fixture97 = Path.of("JF_20261009_97_PA_PT200_FN5.html");
        if (Files.exists(file97) && Files.exists(fixture97)) {
            ReplayLog log = ReplayLog.load(file97, -1);
            PauseAnalysis source = PauseAnalysis.analyzeSource(log, 200, 5), internal = PauseAnalysis.analyze(log, 200, 5);
            String reference = Files.readString(fixture97);
            PauseAnalysisReport.Comparison comparison = PauseAnalysisReport.compare(source, reference);
            check(comparison.equal == 144 && comparison.different == 54 && comparison.missing == 0 && comparison.extra == 0,
                    "JF97 source comparison exposes 54 location/combined-location differences");
            for (String difference : comparison.differences) {
                check(!difference.startsWith("Overview|") && !difference.startsWith("General|")
                        && !difference.startsWith("P-Bursts at ") && !difference.startsWith("Interval #"),
                        "JF97 overview, general, P-bursts and all five interval tables agree exactly");
            }
            check(metric(source, "General", "Total Number of Pauses").equals("273"), "JF97 source pause count");
            check(metric(source, "Overview", "Total Pause Time (s)").equals("210.688"), "JF97 source pause duration");
            check(metric(source, "P-Bursts at 2000 ms", "Number of P-Bursts").equals("16"), "JF97 source burst count");
            // These records share timestamps with focus. The focus must precede
            // the action to avoid duplicated gaps or a stale anchor afterwards.
            for (String id : List.of("40", "52", "504")) check(source.observations.stream()
                    .filter(o -> o.id.equals(id)).findFirst().orElseThrow().duration == 0, "JF97 simultaneous focus/action gap " + id);
            check(source.observations.stream().filter(o -> o.id.equals("55")).findFirst().orElseThrow().duration == 3061,
                    "JF97 focus tie resets next mouse anchor");
            check(source.observations.stream().filter(o -> o.id.equals("506")).findFirst().orElseThrow().duration == 1469,
                    "JF97 closing focus tie resets next mouse anchor");
            check(metric(internal, "General", "Total Number of Pauses").equals("267"), "JF97 internal document-stream pause count");
            check(metric(internal, "Overview", "Total Pause Time (s)").equals("193.895"), "JF97 internal pause duration");
            check(metrics(internal).equals(metrics(PauseAnalysis.analyze(AnalysisTestSupport.jsonRoundTrip(log), 200, 5))), "JF97 internal JSON persistence");
            check(metrics(internal).equals(metrics(PauseAnalysis.analyze(AnalysisTestSupport.rawRoundTrip(log), 200, 5))), "JF97 internal raw persistence");
            check(metrics(source).equals(metrics(PauseAnalysis.analyzeSource(AnalysisTestSupport.jsonRoundTrip(log), 200, 5))), "JF97 source JSON persistence");
            check(metrics(source).equals(metrics(PauseAnalysis.analyzeSource(AnalysisTestSupport.rawRoundTrip(log), 200, 5))), "JF97 source raw persistence");
            check(PauseAnalysisReport.compare(source, reference.replace("210.688", "210.689")).different == 55, "JF97 changed total detected");
            System.out.println("Pause Analysis JF97: source overview/general/bursts/intervals exact (144/198 metrics); 54 location differences exposed; focus ties and internal JSON/raw checked");
        }
        Path json = Path.of("exp_subj_json_1.json"), raw = Path.of("exp_subj_raw_1.txt");
        if (Files.exists(json) && Files.exists(raw)) check(metrics(PauseAnalysis.analyze(ReplayLog.load(json, -1), 200, 5))
                .equals(metrics(PauseAnalysis.analyze(ReplayLog.load(raw, -1), 200, 5))), "native JSON/raw samples agree");
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        ReplayLog simple = new ReplayLog(List.of(new SessionEvent(start, ""),
                press(start, 0, 'a'), new EditEvent(start.plusMillis(1), EventType.INSERT, 0, "", "a"),
                press(start, 199, 'b'), new EditEvent(start.plusMillis(200), EventType.INSERT, 1, "", "b"),
                press(start, 399, 'c'), new EditEvent(start.plusMillis(400), EventType.INSERT, 2, "", "c"),
                press(start, 1000, 'd'), new EditEvent(start.plusMillis(1001), EventType.INSERT, 3, "", "d")));
        PauseAnalysis result = PauseAnalysis.analyze(simple, 200, 2);
        check(result.processMillis == 1000 && result.intervalMillis == 500, "FN divides duration");
        check(metric(result, "General", "Total Number of Pauses").equals("2"), "inclusive PT200 excludes 199 ms");
        check(metric(result, "Overview", "Total Pause Time (s)").equals("0.801"), "gaps not duplicated by edits");
        check(metric(result, "Interval #1", "Number of Pauses").equals("1") && metric(result, "Interval #2", "Number of Pauses").equals("1"), "arriving action determines interval");
        check(metric(result, "General", "Arithmetic Mean of Pauses (s)").equals("0.401"), "two-observation mean");
        check(metric(result, "General", "Standard Deviation (s)").equals("0.201"), "two-observation population deviation");
        check(!metric(result, "General", "95% CI Log-Transformed - Low Boundary (s)").isEmpty(), "two-observation confidence interval defined");
        check(metric(PauseAnalysis.analyze(simple, 0, 2), "General", "Total Number of Pauses").equals("3"), "PT0 excludes invented zero gaps");
        check(metric(PauseAnalysis.analyze(simple, 5000, 2), "General", "Total Number of Pauses").equals("0"), "high PT hides all pauses");
        check(metric(PauseAnalysis.analyze(simple, 200, 2, 500), "P-Bursts at 500 ms", "Number of P-Bursts").equals("2"), "independent P-burst threshold");
        check(Math.abs(PauseAnalysis.student95(1) - 12.706204736) < 1e-7 && Math.abs(PauseAnalysis.student95(10) - 2.228138852) < 1e-7, "Student-t critical values");
        ReplayLog empty = new ReplayLog(List.of(new SessionEvent(start, "initial text")));
        PauseAnalysis noActions = PauseAnalysis.analyze(empty, 0, 5);
        check(noActions.processMillis == 0 && noActions.observations.isEmpty(), "initial text alone is not activity");
        check(metric(noActions, "P-Bursts at 2000 ms", "Number of P-Bursts").equals("0"), "no invented empty burst");
        rejects(() -> PauseAnalysis.analyze(simple, -1, 5), "negative PT");
        rejects(() -> PauseAnalysis.analyze(simple, 200, 0), "zero FN");
        rejects(() -> PauseAnalysis.analyze(simple, 200, 10001), "excessive FN");
        rejects(() -> PauseAnalysis.analyze(simple, 200, 5, 0), "zero PB");
        rejects(() -> PauseAnalysis.analyzeSource(simple, 200, 5), "missing source provenance");
        rejects(() -> PauseAnalysisReport.compare(result, "<html>invalid</html>"), "invalid reference");
        System.out.println("Pause Analysis: all 198 source fixture metrics exact; internal JSON/raw, PT/FN/P-burst boundaries and small-sample statistics checked");
    }
    private static KeyLogEvent press(Instant start, long ms, char c) { return new KeyLogEvent(start.plusMillis(ms), EventType.KEY_PRESSED, (int) Character.toUpperCase(c), Character.toString(Character.toUpperCase(c)), Character.toString(c), 0, "", KeyEvent.KEY_LOCATION_STANDARD); }
    private static String metric(PauseAnalysis analysis, String section, String label) { return analysis.metrics.stream().filter(m -> m.section.equals(section) && m.label.equals(label)).findFirst().orElseThrow().value; }
    private static List<String> metrics(PauseAnalysis analysis) { List<String> result = new ArrayList<>(); for (PauseAnalysis.Metric m : analysis.metrics) result.add(m.key() + "=" + m.value); return result; }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked call, String label) throws Exception { try { call.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError(label); }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
