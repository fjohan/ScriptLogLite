package se.lu.scriptloglite.inputlog;

import se.lu.scriptloglite.*;

import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;

public final class SummaryAnalysisChecks {
    public static void run() throws Exception {
        Path input = Path.of("JF_92.idfx"), reference = Path.of("JF_20261008_92_SU_PT0.html");
        if (Files.exists(input) && Files.exists(reference)) {
            ReplayLog imported = ReplayLog.load(input, -1);
            SummaryAnalysis internal = SummaryAnalysis.analyze(imported), source = SummaryAnalysis.analyzeSource(imported);
            String fixture = Files.readString(reference);
            SummaryAnalysisReport.Comparison nativeComparison = SummaryAnalysisReport.compare(internal, fixture);
            check(nativeComparison.equal == 28 && nativeComparison.different == 3 && nativeComparison.missing == 0 && nativeComparison.extra == 0,
                    "internal reference comparison exposes all three differences");
            check(SummaryAnalysisReport.compare(source, fixture).equal == 30, "source calculation matches 30 of 31 fixture metrics");
            check(value(internal, "- Total Typed (incl.spaces)").equals("253"), "observed typing counts deleted characters");
            check(value(internal, "- Total Typed (excl.spaces)").equals("206"), "Unicode punctuation in typed count");
            check(value(internal, "- Characters Inserted").equals("14") && value(internal, "- Characters Replaced").equals("0"), "actual paste and cut edit counts");
            check(value(source, "- Characters Replaced").equals("1"), "source paragraph replacement convention");
            check(value(internal, "Total Words in Main Document").equals("40") && value(internal, "Mean Word Length").equals("5.075"), "process word lengths");
            check(value(internal, "Median Characters/Sentence").equals("204"), "process lexical categories differ from total typed");
            check(value(internal, "Standard Deviation Characters/Paragraph").equals("8.990"), "legacy paragraph variance convention");
            check(value(internal, "Standard Deviation Words/Paragraph").equals("2.433"), "independently computed deviation is not forced to match blank fixture");
            check(value(internal, "Total Process Time (s)").equals("116.812"), "process duration includes mouse action duration");
            check(cells(internal).equals(cells(SummaryAnalysis.analyze(AnalysisTestSupport.jsonRoundTrip(imported)))), "internal JSON round trip");
            check(cells(internal).equals(cells(SummaryAnalysis.analyze(AnalysisTestSupport.rawRoundTrip(imported)))), "internal raw round trip");
            ReplayLog stripped = new ReplayLog(imported.events); stripped.metadata.putAll(imported.metadata);
            stripped.metadata.remove("inputlogGeneralEvents");
            check(cells(internal).equals(cells(SummaryAnalysis.analyze(stripped))), "retained source keyboard/edit payloads never supply internal metrics");
            ReplayLog poisoned = new ReplayLog(imported.events); poisoned.metadata.putAll(imported.metadata);
            poisoned.metadata.put("inputlogGeneralEvents", List.of(Map.of("invalid", "POISON")));
            check(cells(internal).equals(cells(SummaryAnalysis.analyze(poisoned))), "malformed optional keyboard/edit provenance cannot change internal summary");
            List<LogEvent> continuedEvents = new ArrayList<>(imported.events);
            Instant time = imported.events.get(imported.events.size() - 1).time.plusSeconds(2);
            continuedEvents.add(new KeyLogEvent(time, EventType.KEY_PRESSED, KeyEvent.VK_Z, "Z", "z", 0, "", 1));
            continuedEvents.add(new EditEvent(time.plusMillis(1), EventType.INSERT, imported.finalText.length(), "", "z"));
            ReplayLog continued = new ReplayLog(continuedEvents); continued.metadata.putAll(imported.metadata);
            check(value(SummaryAnalysis.analyze(continued), "- Total Typed (incl.spaces)").equals("254"), "native continuation resumes document focus and includes later keys");
            check(cells(source).equals(cells(SummaryAnalysis.analyzeSource(continued))), "source mode limits metrics and product to imported prefix");
            String html = SummaryAnalysisReport.html(internal, "<unsafe>&title", nativeComparison);
            check(html.contains("&lt;unsafe&gt;&amp;title"), "HTML escapes report title");
            check(SummaryAnalysisReport.compare(internal, SummaryAnalysisReport.html(internal, "title", null)).equal == 31, "own report metrics read back");
            check(SummaryAnalysisReport.compare(internal, fixture.replaceFirst(">253<", ">254<")).different == 4, "comparison detects changed reference value");
            rejects(() -> SummaryAnalysisReport.compare(internal, fixture.replace("<td>0</td>", "<td>2000</td>")), "nonzero pause threshold rejected");
            System.out.println("Summary Analysis: internal PT0 28/31 and source 30/31 fixture metrics; JSON/raw results identical; all differences reported");
        }
        Path json = Path.of("exp_subj_json_1.json"), raw = Path.of("exp_subj_raw_1.txt");
        if (Files.exists(json) && Files.exists(raw)) check(cells(SummaryAnalysis.analyze(ReplayLog.load(json, -1)))
                .equals(cells(SummaryAnalysis.analyze(ReplayLog.load(raw, -1)))), "native sample JSON/raw summaries identical");
        ReplayLog typed = typing("Hi all.\nBye!");
        SummaryAnalysis summary = SummaryAnalysis.analyze(typed);
        check(value(summary, "- Total Typed (incl.spaces)").equals("12"), "ordinary typing is not double counted as inserts");
        check(value(summary, "- Characters Inserted").equals("0"), "associated typing edits are not paste");
        check(value(summary, "Total Words in Main Document").equals("3"), "word segmentation");
        check(value(summary, "Total Sentences in Main Document").equals("2"), "terminal punctuation segmentation");
        check(value(summary, "Total Paragraphs in Main Document").equals("2"), "paragraph segmentation");
        check(value(summary, "Median Characters/Sentence").equals("4"), "sentence lengths exclude whitespace and terminal punctuation");
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        ReplayLog edited = new ReplayLog(List.of(new SessionEvent(time, "old"),
                new EditEvent(time.plusSeconds(1), EventType.REPLACE, 0, "old", "new text"),
                new EditEvent(time.plusSeconds(2), EventType.REMOVE, 0, "new ", ""),
                new EditEvent(time.plusSeconds(3), EventType.INSERT, 4, "", " & more")));
        SummaryAnalysis edits = SummaryAnalysis.analyze(edited);
        check(value(edits, "- Characters Replaced").equals("8") && value(edits, "- Characters Inserted").equals("7"), "replacement vs inserted edit text");
        check(value(edits, "- Total Typed (incl.spaces)").equals("0"), "programmatic edits do not invent typing");
        check(edited.finalText.equals("text & more") && product(edits, "Inserted UTF-16 units (all actual edits)").equals("15")
                && product(edits, "Removed UTF-16 units (all actual edits)").equals("7"), "reconstructed product and edit totals");
        SummaryAnalysis empty = SummaryAnalysis.analyze(new ReplayLog(List.of(new SessionEvent(time, ""))));
        check(value(empty, "- Per Minute (incl. spaces)").isEmpty(), "zero duration rate unavailable");
        check(value(empty, "Total Process Time (s)").equals("0.000"), "empty process time");
        rejects(() -> SummaryAnalysis.analyzeSource(typed), "native log has no source mode");
        rejects(() -> SummaryAnalysisReport.compare(summary, "<html><table></table></html>"), "invalid reference rejected");
        SwingUtilities.invokeAndWait(() -> {
            SummaryAnalysisPanel panel = new SummaryAnalysisPanel(summary, "Synthetic", new DirectoryHistory(Path.of("target", "summary-test.properties")));
            check(panel.getComponentCount() == 2, "summary tab builds headlessly");
        });
    }
    private static ReplayLog typing(String text) {
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        List<LogEvent> events = new ArrayList<>(); events.add(new SessionEvent(time, ""));
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i); int code = ch == '\n' ? KeyEvent.VK_ENTER : KeyEvent.getExtendedKeyCodeForChar(ch);
            Instant pressed = time.plusMillis(100 + i * 10);
            events.add(new KeyLogEvent(pressed, EventType.KEY_PRESSED, code, KeyEvent.getKeyText(code), "" + ch, 0, "", 1));
            events.add(new EditEvent(pressed.plusMillis(1), EventType.INSERT, i, "", "" + ch));
            events.add(new KeyLogEvent(pressed.plusMillis(4), EventType.KEY_RELEASED, code, KeyEvent.getKeyText(code), "" + ch, 0, "", 1));
        }
        return new ReplayLog(events);
    }
    private static List<String> cells(SummaryAnalysis analysis) {
        List<String> values = new ArrayList<>();
        for (SummaryAnalysis.Metric metric : analysis.metrics) values.add(metric.key() + "=" + metric.value);
        for (SummaryAnalysis.Metric metric : analysis.product) values.add(metric.key() + "=" + metric.value);
        return values;
    }
    private static String value(SummaryAnalysis analysis, String label) {
        return analysis.metrics.stream().filter(metric -> metric.label.equals(label)).findFirst().orElseThrow().value;
    }
    private static String product(SummaryAnalysis analysis, String label) {
        return analysis.product.stream().filter(metric -> metric.label.equals(label)).findFirst().orElseThrow().value;
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action, String message) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Summary Analysis: " + message);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError("Summary Analysis: " + message); }
}
