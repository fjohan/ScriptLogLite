package se.lu.scriptloglite.inputlog;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** Standalone pause report and section-aware Inputlog reference comparison. */
public final class PauseAnalysisReport {
    static final class Comparison {
        int equal, different, missing, extra;
        final List<String> differences = new ArrayList<>();
        String description() { return equal + " equal, " + different + " different, " + missing + " missing, " + extra + " extra metrics"; }
    }
    static Comparison compare(PauseAnalysis analysis, String html) throws Exception {
        Map<String, String> expected = readMetrics(html, analysis);
        Comparison result = new Comparison();
        for (PauseAnalysis.Metric metric : analysis.metrics) {
            String value = expected.remove(metric.key());
            if (value == null) { result.extra++; result.differences.add("No reference metric: " + metric.key()); }
            else if (same(metric.value, value)) result.equal++;
            else { result.different++; result.differences.add(metric.key() + ": expected '" + value + "', got '" + metric.value + "'"); }
        }
        for (Map.Entry<String, String> entry : expected.entrySet()) { result.missing++; result.differences.add("Not generated: " + entry.getKey() + " = " + entry.getValue()); }
        return result;
    }
    private static boolean same(String a, String b) {
        if (normalize(a).equals(normalize(b))) return true;
        try { return new java.math.BigDecimal(a.replace(" %", "")).compareTo(new java.math.BigDecimal(b.replace(" %", ""))) == 0; }
        catch (NumberFormatException exception) { return false; }
    }
    private static String normalize(String value) { return value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim(); }
    static Map<String, String> readMetrics(String html, PauseAnalysis analysis) throws Exception {
        List<List<String>> rows = new ArrayList<>(); List<Boolean> headers = new ArrayList<>();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            List<String> row; StringBuilder cell; boolean header;
            public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                if (tag == HTML.Tag.TR) { row = new ArrayList<>(); header = false; }
                if (tag == HTML.Tag.TD || tag == HTML.Tag.TH) { cell = new StringBuilder(); if (tag == HTML.Tag.TH) header = true; }
            }
            public void handleText(char[] text, int position) { if (cell != null) cell.append(text); }
            public void handleEndTag(HTML.Tag tag, int position) {
                if ((tag == HTML.Tag.TD || tag == HTML.Tag.TH) && cell != null) { if (row != null) row.add(normalize(cell.toString())); cell = null; }
                if (tag == HTML.Tag.TR && row != null) { rows.add(row); headers.add(header); row = null; }
            }
        }, true);
        Map<String, String> values = new LinkedHashMap<>(); String section = ""; boolean active = false;
        boolean pt = false, fn = false, pb = false, type = false;
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i); if (row.isEmpty()) continue; String label = row.get(0);
            if (row.size() == 2 && label.equals("Pause Threshold (ms)")) { require(row.get(1), Long.toString(analysis.pauseThreshold), "PT"); pt = true; }
            if (row.size() == 2 && label.equals("Number of Intervals")) { require(row.get(1), Integer.toString(analysis.intervalCount), "FN"); fn = true; }
            if (row.size() == 2 && label.equals("P-Burst Threshold (ms)")) { require(row.get(1), Long.toString(analysis.burstThreshold), "P-burst threshold"); pb = true; }
            if (row.size() == 2 && label.equals("Pause Analysis Interval Type")) { require(row.get(1), "Fixed Number of Intervals", "interval type"); type = true; }
            if (label.equals("General Information")) { active = true; continue; }
            if (List.of("Reconstructed Text", "Reference comparison", "Observed action gaps").contains(label)) { active = false; continue; }
            if (!active) continue;
            if (List.of("Pause Location", "Miscellaneous Pauses", "Combined Pause Location", "Summary per Interval").contains(label)) continue;
            if (headers.get(i) || label.endsWith(" PAUSES")) { section = label; continue; }
            if (row.size() == 2 && !section.isEmpty()) {
                if (values.put(section + "|" + label, row.get(1)) != null) throw new IllegalArgumentException("Duplicate pause metric: " + section + "|" + label);
            }
        }
        if (!pt || !fn || !pb || !type || !values.containsKey("Overview|Total Process Time (s)") || !values.containsKey("General|Total Number of Pauses")) {
            throw new IllegalArgumentException("Reference contains no supported Pause Analysis with PT, FN and P-burst parameters.");
        }
        return values;
    }
    private static void require(String got, String expected, String parameter) {
        if (!same(got, expected)) throw new IllegalArgumentException("Reference " + parameter + " is '" + got + "'; analysis uses '" + expected + "'.");
    }
    static String html(PauseAnalysis analysis, String title, Comparison comparison) {
        StringBuilder out = new StringBuilder("<!doctype html><html><head><meta charset='UTF-8'><title>Pause Analysis</title><style>body{font:14px sans-serif;margin:24px;color:#222}table{border-collapse:collapse;margin-bottom:20px}th,td{border:1px solid #ddd;padding:6px;text-align:left}th{background:#eee}pre{white-space:pre-wrap}</style></head><body>");
        out.append("<h1>Pause Analysis — ").append(escape(title)).append("</h1><p>Calculation mode: ").append(analysis.internal ? "Internal events / reconstructed text" : "Retained Inputlog source events").append("</p><table>");
        pair(out, "Pause Threshold (ms)", analysis.pauseThreshold); pair(out, "P-Burst Threshold (ms)", analysis.burstThreshold);
        pair(out, "Pause Analysis Interval Type", "Fixed Number of Intervals"); pair(out, "Number of Intervals", analysis.intervalCount);
        pair(out, "Interval length (ms)", analysis.intervalMillis); out.append("</table><h2>Calculation notes</h2><ul>");
        for (String note : analysis.notes) out.append("<li>").append(escape(note)).append("</li>");
        out.append("</ul><table><tr><th colspan='2'>General Information</th></tr>"); String previous = "";
        for (PauseAnalysis.Metric metric : analysis.metrics) {
            if (!previous.equals(metric.section)) { out.append("<tr><th colspan='2'>").append(escape(metric.section)).append("</th></tr>"); previous = metric.section; }
            pair(out, metric.label, metric.value);
        }
        out.append("</table><table><tr><th colspan='2'>Reconstructed Text</th></tr><tr><td colspan='2'><pre>").append(escape(analysis.log.finalText)).append("</pre></td></tr></table>");
        if (comparison != null) {
            out.append("<table><tr><th>Reference comparison</th></tr></table><p>").append(escape(comparison.description())).append(".</p><ul>");
            for (String difference : comparison.differences) out.append("<li>").append(escape(difference)).append("</li>"); out.append("</ul>");
        }
        out.append("<table><tr><th colspan='6'>Observed action gaps</th></tr><tr><th>Event</th><th>Type</th><th>Elapsed (ms)</th><th>Gap (ms)</th><th>Location</th><th>Interval</th></tr>");
        for (PauseAnalysis.Observation o : analysis.observations) if (o.duration > 0 && o.duration >= analysis.pauseThreshold) {
            out.append("<tr>"); for (Object v : new Object[] {o.id, o.type, o.time, o.duration, GeneralAnalysis.LOCATIONS[o.location], o.interval + 1}) out.append("<td>").append(escape(String.valueOf(v))).append("</td>"); out.append("</tr>");
        }
        return out.append("</table></body></html>").toString();
    }
    private static void pair(StringBuilder out, String label, Object value) { out.append("<tr><td>").append(escape(label)).append("</td><td>").append(escape(String.valueOf(value))).append("</td></tr>"); }
    private static String escape(String value) { return GeneralAnalysisReport.escape(value); }
    public static void save(PauseAnalysis analysis, String title, Path output, Path reference) throws Exception {
        Comparison comparison = reference == null ? null : compare(analysis, Files.readString(reference));
        Files.writeString(output, html(analysis, title, comparison));
    }
}
