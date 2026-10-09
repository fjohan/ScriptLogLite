package se.lu.scriptloglite.inputlog;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** Standalone compact score, with comparison kept separate from calculation. */
public final class LinearAnalysisReport {
    static final class Comparison {
        int equal, different, missing, extra;
        final List<String> reference = new ArrayList<>(), differences = new ArrayList<>();
    }
    static Comparison compare(LinearAnalysis analysis, String html) throws Exception {
        List<List<String>> rows = new ArrayList<>(); List<String> scores = new ArrayList<>();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            List<String> row; StringBuilder cell, spans; int depth;
            boolean scoreTable;
            String scoreCell = "";
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                if (tag == HTML.Tag.TR) { row = new ArrayList<>(); scoreCell = ""; }
                if (tag == HTML.Tag.TD || tag == HTML.Tag.TH) { cell = new StringBuilder(); spans = new StringBuilder(); }
                if (tag == HTML.Tag.SPAN) depth++;
            }
            @Override public void handleText(char[] text, int position) {
                if (cell != null) cell.append(text);
                if (spans != null && depth > 0) spans.append(text);
            }
            @Override public void handleEndTag(HTML.Tag tag, int position) {
                if (tag == HTML.Tag.SPAN) depth = Math.max(0, depth - 1);
                if ((tag == HTML.Tag.TD || tag == HTML.Tag.TH) && cell != null) {
                    if (row != null) row.add(cell.toString().strip());
                    if (row != null && row.size() == 2) scoreCell = spans.toString().replace("\u200B", "");
                    cell = null; spans = null;
                }
                if (tag == HTML.Tag.TR && row != null) {
                    if (row.size() == 2 && row.get(0).equals("Interval") && row.get(1).equals("Output")) scoreTable = true;
                    else if (scoreTable && row.size() == 2) scores.add(scoreCell);
                    rows.add(row); row = null;
                }
                if (tag == HTML.Tag.TABLE) scoreTable = false;
            }
        }, true);
        boolean header = false; Long pt = null, fl = null;
        for (List<String> row : rows) if (row.size() == 2) {
            if (row.get(0).equals("Pause Threshold (ms)")) pt = Long.valueOf(row.get(1));
            if (row.get(0).equals("Length of Interval (sec)")) fl = Long.valueOf(row.get(1));
            if (row.get(0).equals("Linear Analysis Type") && !row.get(1).equals("Fixed Length Intervals")) throw new IllegalArgumentException("Only fixed-length interval references are supported");
            if (row.get(0).equals("Interval") && row.get(1).equals("Output")) header = true;
        }
        if (!header || pt == null || fl == null) throw new IllegalArgumentException("Reference contains no fixed-length Linear Analysis table and PT/FL parameters");
        if (pt != analysis.pauseThreshold || fl != analysis.intervalSeconds) throw new IllegalArgumentException("Reference parameters are PT=" + pt + " ms, FL=" + fl + " s; select those values before comparing");
        Comparison result = new Comparison(); result.reference.addAll(scores);
        java.util.Set<Long> generated = new java.util.HashSet<>();
        for (LinearAnalysis.Interval interval : analysis.intervals) {
            generated.add(interval.index);
            if (interval.index >= scores.size()) { result.extra++; result.differences.add("Generated interval " + interval.index + " has no reference row"); continue; }
            String actual = interval.score(), wanted = scores.get((int) interval.index);
            if (actual.equals(wanted)) result.equal++;
            else {
                result.different++; int first = 0;
                while (first < actual.length() && first < wanted.length() && actual.charAt(first) == wanted.charAt(first)) first++;
                result.differences.add("Interval " + interval.index + ", first difference at character " + first + ": Inputlog '"
                        + excerpt(wanted, first) + "', ScriptLogLite '" + excerpt(actual, first) + "'");
            }
        }
        for (int i = 0; i < scores.size(); i++) {
            if (!generated.contains((long) i)) result.missing++;
        }
        return result;
    }
    private static String excerpt(String text, int position) { return text.substring(Math.max(0, position - 15), Math.min(text.length(), position + 70)); }
    static String html(LinearAnalysis analysis, String title, Comparison comparison) {
        StringBuilder out = new StringBuilder("<!doctype html><html><head><meta charset='UTF-8'><title>Linear Analysis</title>"
                + "<style>body{font:14px sans-serif;margin:24px;color:#222}table{border-collapse:collapse;width:100%}th,td{border:1px solid #ddd;padding:10px;vertical-align:top;text-align:left}th{background:#eee}.score{font:15px monospace;white-space:pre-wrap;overflow-wrap:anywhere}.pause{color:#3344bb}.command{color:#777}.edit{color:#008055}.gap{color:#777}pre{white-space:pre-wrap}</style></head><body><h1>Linear Analysis — ")
                .append(escape(title)).append("</h1><p>Calculation mode: ").append(analysis.internal ? "Internal events" : "Retained Inputlog source events")
                .append(". PT = ").append(analysis.pauseThreshold).append(" ms; FL = ").append(analysis.intervalSeconds).append(" s.</p>");
        out.append("<table><tr><td>Pause Threshold (ms)</td><td>").append(analysis.pauseThreshold)
                .append("</td></tr><tr><td>Linear Analysis Type</td><td>Fixed Length Intervals</td></tr><tr><td>Length of Interval (sec)</td><td>").append(analysis.intervalSeconds).append("</td></tr></table><ul>");
        for (String note : analysis.notes) out.append("<li>").append(escape(note)).append("</li>");
        out.append("</ul><table><tr><th>Interval</th><th>Output</th></tr>");
        long previous = -1, width = analysis.intervalSeconds * 1000;
        for (LinearAnalysis.Interval interval : analysis.intervals) {
            if (interval.index > previous + 1) out.append("<tr><td colspan='2' class='gap'>No score actions from ").append(clock((previous + 1) * width))
                    .append(" to ").append(clock(interval.index * width)).append("</td></tr>");
            out.append("<tr><td>").append(clock(interval.index * width)).append("–").append(clock(interval.index * width + width)).append("</td><td class='score'>");
            for (LinearAnalysis.Mark mark : interval.marks) out.append("<span class='").append(mark.kind).append("' title='").append(mark.time).append(" ms'>").append(escape(mark.value)).append("</span>");
            out.append("</td></tr>"); previous = interval.index;
        }
        out.append("</table>");
        if (comparison != null) {
            out.append("<h2>Reference comparison</h2><p>").append(comparison.equal).append(" equal / ").append(comparison.different)
                    .append(" different score rows; ").append(comparison.missing).append(" missing / ").append(comparison.extra)
                    .append(" extra rows. Zero-width space formatting and interval clock labels are excluded.</p><ul>");
            for (String difference : comparison.differences) out.append("<li>").append(escape(difference)).append("</li>");
            out.append("</ul><h3>Inputlog scores</h3>");
            for (String score : comparison.reference) out.append("<pre>").append(escape(score)).append("</pre>");
        }
        return out.append("<h2>Reconstructed final text (separate from the chronological score)</h2><pre>").append(escape(analysis.log.finalText)).append("</pre></body></html>").toString();
    }
    static String clock(long millis) { long seconds = millis / 1000; return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60); }
    public static void save(LinearAnalysis analysis, String title, Path path, Path reference) throws Exception {
        Comparison comparison = reference == null ? null : compare(analysis, Files.readString(reference));
        Files.writeString(path, html(analysis, title, comparison));
    }
    private static String escape(String value) { return GeneralAnalysisReport.escape(value); }
}
