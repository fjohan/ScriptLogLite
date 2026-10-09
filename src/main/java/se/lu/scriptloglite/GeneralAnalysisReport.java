package se.lu.scriptloglite;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** HTML presentation and comparison, kept independent of calculation. */
final class GeneralAnalysisReport {
    static final class Comparison {
        int matched, missing, extra;
        final int[] equal = new int[20], different = new int[20];
        final List<String> differences = new ArrayList<>();
    }
    static Comparison compare(GeneralAnalysis analysis, String reference) throws Exception {
        List<List<String>> expected = readRows(reference);
        if (expected.isEmpty()) throw new IllegalArgumentException("Reference contains no Inputlog General Analysis event table");
        Map<String, ArrayDeque<List<String>>> remaining = new LinkedHashMap<>();
        for (List<String> row : expected) remaining.computeIfAbsent(signature(row), ignored -> new ArrayDeque<>()).add(row);
        Comparison result = new Comparison();
        for (GeneralAnalysis.Row event : analysis.rows) {
            List<String> actual = event.cells();
            ArrayDeque<List<String>> candidates = remaining.get(signature(actual));
            if (candidates == null || candidates.isEmpty()) {
                result.extra++; result.differences.add("Generated row " + event.id + " has no reference match: " + signature(actual)); continue;
            }
            List<String> wanted = candidates.remove(); result.matched++;
            for (int column = 0; column < 20; column++) {
                if (normalize(actual.get(column)).equals(normalize(wanted.get(column)))) result.equal[column]++;
                else {
                    result.different[column]++;
                    result.differences.add("Reference row " + wanted.get(0) + ", generated source " + event.id + ", "
                            + GeneralAnalysis.COLUMNS.get(column) + ": expected '" + wanted.get(column) + "', got '" + actual.get(column) + "'");
                }
            }
        }
        for (ArrayDeque<List<String>> queue : remaining.values()) for (List<String> row : queue) {
            result.missing++; result.differences.add("Reference row " + row.get(0) + " has no generated match: " + signature(row));
        }
        return result;
    }
    // Match by observed output/type/start time: Inputlog may renumber source IDs in preprocessing.
    private static String signature(List<String> row) { return row.get(1) + "|" + normalize(row.get(2)) + "|" + row.get(8); }
    private static String normalize(String value) { return value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim(); }
    static List<List<String>> readRows(String html) throws Exception {
        List<List<String>> result = new ArrayList<>();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            List<String> row;
            StringBuilder cell;
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                if (tag == HTML.Tag.TR) row = new ArrayList<>();
                if (tag == HTML.Tag.TD || tag == HTML.Tag.TH) cell = new StringBuilder();
            }
            @Override public void handleText(char[] text, int position) { if (cell != null) cell.append(text); }
            @Override public void handleEndTag(HTML.Tag tag, int position) {
                if ((tag == HTML.Tag.TD || tag == HTML.Tag.TH) && cell != null) { if (row != null) row.add(normalize(cell.toString())); cell = null; }
                if (tag == HTML.Tag.TR && row != null) {
                    if (row.size() == 20 && row.get(0).matches("\\d+")) result.add(row);
                    row = null;
                }
            }
        }, true);
        return result;
    }
    static String html(GeneralAnalysis analysis, String title, Comparison comparison) {
        StringBuilder out = new StringBuilder("<!doctype html><html><head><meta charset='UTF-8'><title>General Analysis</title>"
                + "<style>body{font:14px sans-serif;margin:24px;color:#222}table{border-collapse:collapse;font-size:12px}"
                + "th,td{border:1px solid #ddd;padding:5px;text-align:right}th{background:#eee;position:sticky;top:0}"
                + "td:nth-child(3){text-align:left;white-space:pre-wrap}.scroll{overflow:auto;max-height:70vh}.note{max-width:1000px}</style></head><body>");
        out.append("<p>Calculation mode: ").append(analysis.internal ? "Internal events / reconstructed text" : "Retained Inputlog source events").append("</p>");
        out.append("<h1>General Analysis — ").append(escape(title)).append("</h1>");
        out.append("<p class='note'>Inputlog-style compatibility calculations. Times are milliseconds from the first timed event. "
                + "Shift/control combinations are grouped; source selections and statistics are excluded. "
                + "Pause locations use initial compatibility rules; differences from Inputlog are reported, not hidden.</p>");
        out.append("<p>").append(analysis.rows.size()).append(" analysis rows. Final reconstructed text: ")
                .append(analysis.log.finalText.length()).append(" UTF-16 units. Conversion audit: ")
                .append(analysis.checkedKeys).append(" keyboard document-length/key checks.</p>");
        for (String note : analysis.notes) out.append("<p>").append(escape(note)).append("</p>");
        if (!analysis.audit.isEmpty()) {
            out.append("<ul>"); for (String item : analysis.audit) out.append("<li>").append(escape(item)).append("</li>"); out.append("</ul>");
        } else if (analysis.checkedKeys > 0) out.append("<p>Conversion audit passed.</p>");
        else out.append("<p>No original source records available for an independent conversion audit.</p>");
        if (comparison != null) {
            out.append("<h2>Reference comparison</h2><p>Matched rows: ").append(comparison.matched).append(". Missing: ")
                    .append(comparison.missing).append(". Extra: ").append(comparison.extra).append(".</p><table><tr><th>Column</th><th>Equal</th><th>Different</th></tr>");
            for (int i = 0; i < 20; i++) out.append("<tr><td>").append(escape(GeneralAnalysis.COLUMNS.get(i))).append("</td><td>")
                    .append(comparison.equal[i]).append("</td><td>").append(comparison.different[i]).append("</td></tr>");
            out.append("</table><details><summary>All differences (").append(comparison.differences.size()).append(")</summary><ul>");
            for (String item : comparison.differences) out.append("<li>").append(escape(item)).append("</li>");
            out.append("</ul></details>");
        }
        out.append("<h2>Events</h2><div class='scroll'><table><thead><tr>");
        for (String column : GeneralAnalysis.COLUMNS) out.append("<th>").append(escape(column)).append("</th>");
        out.append("</tr></thead><tbody>");
        for (GeneralAnalysis.Row row : analysis.rows) { out.append("<tr>"); for (String cell : row.cells()) out.append("<td>").append(escape(cell)).append("</td>"); out.append("</tr>"); }
        out.append("</tbody></table></div><h2>Reconstructed final text</h2><pre>").append(escape(analysis.log.finalText)).append("</pre></body></html>");
        return out.toString();
    }
    static void save(GeneralAnalysis analysis, String title, Path target, Path reference) throws Exception {
        Comparison comparison = reference == null ? null : compare(analysis, Files.readString(reference, StandardCharsets.UTF_8));
        Files.writeString(target, html(analysis, title, comparison), StandardCharsets.UTF_8);
    }
    static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
