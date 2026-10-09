package se.lu.scriptloglite.inputlog;

import java.io.StringReader;
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

/** Corrected revision/word reports and optional diagnostic comparison to Inputlog HTML. */
public final class RevisionAnalysisReport {
    static final class Comparison {
        String referenceText;
        boolean reconstructionMatches;
        int matched, missing, extra, equal, different;
        final List<String> differences = new ArrayList<>();
    }
    static Comparison compare(WordPausesAnalysis analysis, String html) throws Exception {
        List<List<String>> table = new ArrayList<>(); List<String> notation = new ArrayList<>();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            List<String> row; StringBuilder cell, marked;
            int hidden;
            final List<Boolean> spans = new ArrayList<>();
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                if (tag == HTML.Tag.TR) row = new ArrayList<>();
                if (tag == HTML.Tag.TD || tag == HTML.Tag.TH) {
                    cell = new StringBuilder();
                    Object style = attributes.getAttribute(HTML.Attribute.CLASS);
                    if (style != null && style.toString().contains("col_text")) marked = new StringBuilder();
                }
                if (tag == HTML.Tag.SPAN) {
                    Object style = attributes.getAttribute(HTML.Attribute.CLASS);
                    boolean skip = style != null && (style.toString().contains("superscript") || style.toString().contains("subscript"));
                    spans.add(skip); if (skip) hidden++;
                }
            }
            @Override public void handleText(char[] chars, int position) {
                if (cell != null) cell.append(chars);
                if (marked != null && hidden == 0) marked.append(chars);
            }
            @Override public void handleEndTag(HTML.Tag tag, int position) {
                if (tag == HTML.Tag.SPAN && !spans.isEmpty()) { if (spans.remove(spans.size() - 1)) hidden--; }
                if ((tag == HTML.Tag.TD || tag == HTML.Tag.TH) && cell != null) {
                    if (row != null) row.add(normalize(cell.toString())); cell = null;
                    if (marked != null) { notation.add(marked.toString()); marked = null; }
                }
                if (tag == HTML.Tag.TR && row != null) { table.add(row); row = null; }
            }
        }, true);
        Comparison result = new Comparison(); Map<String, ArrayDeque<List<String>>> words = new LinkedHashMap<>();
        boolean wordTable = false, nextText = false;
        for (List<String> row : table) {
            if (nextText && row.size() == 1) { result.referenceText = row.get(0); nextText = false; }
            if (row.size() == 1 && row.get(0).equals("Reconstructed Text")) nextText = true;
            if (row.size() >= 2 && normalize(row.get(0)).equals("Reconstructed Text")) result.referenceText = row.get(1);
            if (row.size() == 15 && row.get(0).equals("Revisions") && row.get(3).equals("Token")) { wordTable = true; continue; }
            if (wordTable && row.size() == 15) words.computeIfAbsent(row.get(3), ignored -> new ArrayDeque<>()).add(row);
        }
        if (result.referenceText == null && !wordTable && !notation.isEmpty()) result.referenceText = projection(notation.get(0));
        if (result.referenceText == null) throw new IllegalArgumentException("Reference contains no Inputlog Word Pauses or S-Notation reconstruction");
        result.reconstructionMatches = normalize(analysis.history.text).equals(normalize(result.referenceText));
        if (!result.reconstructionMatches) result.differences.add("Inputlog's reconstructed text differs from the internal replay projection.");
        if (wordTable) {
            // IDs and notation numbering differ between models. Compare token counts and timing only.
            int[] columns = {2, 6, 7, 8, 9, 10, 11, 12, 13};
            for (WordPausesAnalysis.Row row : analysis.rows) {
                ArrayDeque<List<String>> queue = words.get(row.token);
                if (queue == null || queue.isEmpty()) { result.extra++; result.differences.add("Generated token has no Inputlog counterpart: " + row.token + " at " + row.start); continue; }
                List<String> expected = queue.remove(); result.matched++;
                List<String> actual = row.cells();
                for (int column : columns) {
                    if (expected.get(column).equals(actual.get(column))) result.equal++;
                    else { result.different++; result.differences.add("Token " + row.token + " at " + row.start + ", " + WordPausesAnalysis.COLUMNS.get(column)
                            + ": Inputlog '" + expected.get(column) + "', ScriptLogLite '" + actual.get(column) + "'"); }
                }
            }
            for (ArrayDeque<List<String>> queue : words.values()) for (List<String> row : queue) {
                result.missing++; result.differences.add("Inputlog token absent from reconstructed text: " + row.get(3));
            }
        }
        return result;
    }
    private static String projection(String notation) {
        StringBuilder text = new StringBuilder(); int deleted = 0;
        for (char ch : notation.toCharArray()) {
            if (ch == '[') deleted++;
            else if (ch == ']') { if (deleted > 0) deleted--; }
            else if (deleted == 0 && ch != '{' && ch != '}' && ch != '|') text.append(ch == '·' ? ' ' : ch);
        }
        return text.toString();
    }
    private static String normalize(String text) { return text.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim(); }
    static String html(WordPausesAnalysis analysis, String title, boolean wordPauses, Comparison comparison) {
        RevisionHistory history = analysis.history;
        String name = wordPauses ? "Word Pauses" : "S-Notation";
        StringBuilder out = new StringBuilder("<!doctype html><html><head><meta charset='UTF-8'><title>").append(name)
                .append("</title><style>body{font:14px sans-serif;margin:24px;color:#222}table{border-collapse:collapse}th,td{border:1px solid #ddd;padding:6px;text-align:left}th{background:#eee}pre{white-space:pre-wrap;word-break:break-word}.scroll{overflow:auto}</style></head><body><h1>")
                .append(name).append(" — ").append(escape(title)).append("</h1><p>Calculated from internal typed events and reversible edits. ")
                .append(history.live.size()).append(" final UTF-16 units; ").append(history.revisions.size()).append(" revision groups. Projection matches replay exactly.</p>");
        out.append("<h2>Reconstructed Text</h2><pre>").append(escape(history.text)).append("</pre><h2>S-Notation</h2><pre>")
                .append(escape(history.notation())).append("</pre><p>· = whitespace; [deleted text]n; {inserted text}n; |n = revision break. Literal delimiters are backslash-escaped. Rendering may split an insertion into multiple labelled fragments after later revisions.</p>");
        out.append("<h2>Inserts in context</h2><pre>").append(escape(history.context(true))).append("</pre>");
        out.append("<h2>Calculation notes</h2><ul>");
        for (String note : wordPauses ? analysis.notes : history.notes) out.append("<li>").append(escape(note)).append("</li>");
        out.append("</ul>");
        if (comparison != null) {
            out.append("<h2>Inputlog reference comparison</h2><p>Reconstruction ").append(comparison.reconstructionMatches ? "matches" : "differs")
                    .append(" after whitespace normalization. ").append(comparison.matched).append(" matched tokens; ").append(comparison.missing)
                    .append(" reference-only tokens; ").append(comparison.extra).append(" generated-only tokens. ").append(comparison.equal).append(" equal / ")
                    .append(comparison.different).append(" different count/timing cells. Revision labels, notation and event IDs are excluded.</p><h3>Inputlog reconstruction</h3><pre>")
                    .append(escape(comparison.referenceText)).append("</pre><ul>");
            for (String difference : comparison.differences) out.append("<li>").append(escape(difference)).append("</li>");
            out.append("</ul><p>Repeated tokens match by spelling and occurrence in final order. S-Notation-only references have no word table; their text projection is a diagnostic, not an input to calculations.</p>");
        }
        if (wordPauses) {
            out.append("<h2>Word Pauses</h2><div class='scroll'><table><tr>");
            for (String column : WordPausesAnalysis.COLUMNS) out.append("<th>").append(escape(column)).append("</th>");
            out.append("</tr>");
            for (WordPausesAnalysis.Row row : analysis.rows) {
                out.append("<tr>"); for (String cell : row.cells()) out.append("<td>").append(escape(cell)).append("</td>"); out.append("</tr>");
            }
            out.append("</table></div>");
        }
        out.append("<h2>Revision ledger</h2><div class='scroll'><table><tr><th>Revision</th><th>Events</th><th>Offset</th><th>Start (ms)</th><th>End (ms)</th><th>Removed</th><th>Inserted</th><th>Provenance</th></tr>");
        for (RevisionHistory.Revision revision : history.revisions) {
            out.append("<tr><td>").append(revision.id).append("</td><td>").append(revision.firstEvent).append("–").append(revision.lastEvent)
                    .append("</td><td>").append(revision.offset).append("</td><td>").append(revision.start).append("</td><td>").append(revision.end)
                    .append("</td><td><pre>").append(escape(revision.removed)).append("</pre></td><td><pre>").append(escape(revision.inserted))
                    .append("</pre></td><td>").append(revision.cut ? "Ctrl+X cut" : revision.movedFrom == 0 ? "internal edits" : "inferred lineage from cut " + revision.movedFrom).append("</td></tr>");
        }
        return out.append("</table></div></body></html>").toString();
    }
    public static void save(WordPausesAnalysis analysis, String title, Path path, Path reference, boolean wordPauses) throws Exception {
        Comparison comparison = reference == null ? null : compare(analysis, Files.readString(reference));
        Files.writeString(path, html(analysis, title, wordPauses, comparison));
    }
    private static String escape(String value) { return GeneralAnalysisReport.escape(value); }
}
