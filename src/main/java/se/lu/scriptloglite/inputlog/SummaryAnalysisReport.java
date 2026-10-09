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

/** Presentation and fixture comparison never participate in calculating summary statistics. */
public final class SummaryAnalysisReport {
    static final class Comparison {
        int equal, different, missing, extra;
        final Map<String, String> expected = new LinkedHashMap<>();
        final List<String> differences = new ArrayList<>();
        String description() { return equal + " equal, " + different + " different, " + missing + " missing, " + extra + " extra metrics"; }
    }
    static Comparison compare(SummaryAnalysis analysis, String html) throws Exception {
        Map<String, String> values = readMetrics(html);
        Comparison result = new Comparison(); result.expected.putAll(values);
        for (SummaryAnalysis.Metric metric : analysis.metrics) {
            String wanted = values.remove(normalize(metric.label));
            if (wanted == null) {
                result.extra++; result.differences.add("Generated metric has no reference: " + metric.label);
            } else if (same(metric.value, wanted)) result.equal++;
            else {
                result.different++; result.differences.add(metric.label + ": expected '" + wanted + "', got '" + metric.value + "'");
            }
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result.missing++; result.differences.add("Reference metric not generated: " + entry.getKey() + " = " + entry.getValue());
        }
        return result;
    }
    private static boolean same(String a, String b) {
        if (normalize(a).equals(normalize(b))) return true;
        try { return new java.math.BigDecimal(a).compareTo(new java.math.BigDecimal(b)) == 0; }
        catch (NumberFormatException exception) { return false; }
    }
    private static String normalize(String value) { return value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim(); }
    static Map<String, String> readMetrics(String html) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            List<String> row;
            StringBuilder cell;
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                if (tag == HTML.Tag.TR) row = new ArrayList<>();
                if (tag == HTML.Tag.TD || tag == HTML.Tag.TH) cell = new StringBuilder();
            }
            @Override public void handleText(char[] text, int position) { if (cell != null) cell.append(text); }
            @Override public void handleEndTag(HTML.Tag tag, int position) {
                if ((tag == HTML.Tag.TD || tag == HTML.Tag.TH) && cell != null) {
                    if (row != null) row.add(normalize(cell.toString())); cell = null;
                }
                if (tag == HTML.Tag.TR && row != null) { rows.add(row); row = null; }
            }
        }, true);
        Map<String, String> result = new LinkedHashMap<>();
        boolean process = false;
        for (List<String> row : rows) {
            if (row.isEmpty()) continue;
            String label = row.get(0);
            if (label.equals("Pause Threshold (ms)") && row.size() >= 2 && !same(row.get(1), "0")) {
                throw new IllegalArgumentException("Only Summary Analysis with pause threshold 0 ms (PT0) is supported.");
            }
            if (List.of("Process Information", "Process Time").contains(label)) { process = true; continue; }
            if (List.of("Product Information", "Product/Process", "Writing Mode", "Reconstructed product", "Reference comparison").contains(label)) { process = false; continue; }
            if (!process || row.size() != 2 || List.of("Keystrokes Produced in This Session", "Words", "Sentences", "Paragraphs", "General").contains(label)) continue;
            if (result.put(label, row.get(1)) != null) throw new IllegalArgumentException("Duplicate Summary metric: " + label);
        }
        if (!result.containsKey("Total Process Time (s)") || !result.containsKey("- Total Typed (incl.spaces)")) {
            throw new IllegalArgumentException("Reference contains no supported Inputlog PT0 Summary Analysis table.");
        }
        return result;
    }
    static String html(SummaryAnalysis analysis, String title, Comparison comparison) {
        StringBuilder out = new StringBuilder("<!doctype html><html><head><meta charset='UTF-8'><title>Summary Analysis</title>"
                + "<style>body{font:14px sans-serif;margin:24px;color:#222}table{border-collapse:collapse;margin-bottom:20px}"
                + "th,td{border:1px solid #ddd;padding:6px;text-align:left}th{background:#eee}pre{white-space:pre-wrap}</style></head><body>");
        out.append("<h1>Summary Analysis — ").append(escape(title)).append("</h1><p>Calculation mode: ")
                .append(analysis.internal ? "Internal events / reconstructed text" : "Retained Inputlog source events").append("</p>");
        out.append("<h2>Metadata</h2><table>");
        pair(out, "Logfile", title); pair(out, "Analysis Creation", analysis.creationTime);
        pair(out, "Analysis Program", "ScriptLogLite"); pair(out, "Pause Threshold (ms)", "0");
        Object metadata = analysis.log.metadata.get("inputlogMeta");
        if (metadata instanceof Map) {
            Map<?, ?> meta = (Map<?, ?>) metadata;
            for (String[] field : new String[][] {{"Main Document", "__MainDocument"}, {"Log Creation", "__LogCreationDate"},
                    {"Log GUID", "__GUID"}, {"Logging Program Version Number", "__LogProgramVersion"}}) {
                pair(out, field[0], meta.get(field[1]));
            }
        }
        pair(out, "Participant", analysis.log.metadata.get("id_code")); pair(out, "Text Language", analysis.log.metadata.get("textLanguage"));
        out.append("</table><h2>Calculation notes</h2><ul>");
        for (String note : analysis.notes) out.append("<li>").append(escape(note)).append("</li>");
        out.append("</ul><table><tr><th colspan='2'>Process Information</th></tr>");
        String previous = "";
        for (SummaryAnalysis.Metric metric : analysis.metrics) {
            if (!metric.section.equals(previous)) {
                out.append("<tr><th colspan='2'>").append(escape(metric.section)).append("</th></tr>"); previous = metric.section;
            }
            pair(out, metric.label, metric.value);
        }
        out.append("</table><table><tr><th colspan='2'>Reconstructed product</th></tr>");
        for (SummaryAnalysis.Metric metric : analysis.product) pair(out, metric.label, metric.value);
        out.append("</table>");
        if (comparison != null) {
            out.append("<h2>Reference comparison</h2><p>").append(escape(comparison.description()))
                    .append(". Metadata, analysis timestamps and reconstructed-product additions are excluded.</p>");
            out.append("<table><tr><th>Metric</th><th>ScriptLogLite</th><th>Inputlog</th><th>Result</th></tr>");
            for (SummaryAnalysis.Metric metric : analysis.metrics) {
                String expected = comparison.expected.get(normalize(metric.label));
                out.append("<tr><td>").append(escape(metric.label)).append("</td><td>").append(escape(metric.value))
                        .append("</td><td>").append(escape(expected)).append("</td><td>")
                        .append(expected == null ? "No reference" : same(metric.value, expected) ? "Equal" : "Different").append("</td></tr>");
            }
            out.append("</table><ul>");
            for (String difference : comparison.differences) out.append("<li>").append(escape(difference)).append("</li>");
            out.append("</ul>");
        }
        return out.append("<h2>Reconstructed final text</h2><pre>").append(escape(analysis.log.finalText)).append("</pre></body></html>").toString();
    }
    public static void save(SummaryAnalysis analysis, String title, Path path, Path reference) throws Exception {
        Comparison comparison = reference == null ? null : compare(analysis, Files.readString(reference));
        Files.writeString(path, html(analysis, title, comparison));
    }
    private static void pair(StringBuilder out, String label, Object value) {
        out.append("<tr><td>").append(escape(label)).append("</td><td>").append(escape(value)).append("</td></tr>");
    }
    private static String escape(Object value) { return GeneralAnalysisReport.escape(value == null ? "" : value.toString()); }
}
