package se.lu.scriptloglite.inputlog;

import se.lu.scriptloglite.ReplayLog;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Word timing from surviving character identities, without parsing revision markup. */
public final class WordPausesAnalysis {
    static final List<String> COLUMNS = List.of("Revisions", "S-Notation", "#Chars", "Token", "Start WordID", "End WordID",
            "Start WordTime", "End WordTime", "BfrWord-2", "BfrWord-1", "Btwn Word", "Word Prod", "Within Word", "AftWord+1", "Target",
            "Final Start", "Final End", "Placement Start", "Placement End", "Provenance", "Timing Status");
    static final class Row {
        String token, notation, revisionIds, provenance, status;
        int start, end;
        Integer firstKey, lastKey;
        Long first, last, before2, before1, between, production, within, after, firstPlacement, lastPlacement;
        Long lastPress, boundary;
        List<String> cells() {
            return List.of(revisionIds, notation, "" + token.length(), token, cell(firstKey), cell(lastKey), cell(first), cell(last),
                    cell(before2), cell(before1), cell(between), cell(production), cell(within), cell(after), "", "" + start, "" + end,
                    cell(firstPlacement), cell(lastPlacement), provenance, status);
        }
        private static String cell(Object value) { return value == null ? "-" : value.toString(); }
    }
    final RevisionHistory history;
    final List<Row> rows = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    public static WordPausesAnalysis analyze(ReplayLog log) { return new WordPausesAnalysis(SNotationAnalysis.analyze(log).history); }
    private WordPausesAnalysis(RevisionHistory history) {
        this.history = history; notes.addAll(history.notes);
        notes.add("One row per final non-whitespace token, including attached punctuation. #Chars and Final Start/End are UTF-16 coordinates in reconstructed text, with exclusive end. Character provenance is retained through inferred cut/paste.");
        notes.add("Times are milliseconds from the first observed action (zero is valid). Start/End WordID refer to internal key-event indices, not final positions or Inputlog IDs. Start WordTime is the earliest surviving original character press; End WordTime is its latest release. Missing timing is shown as -, never substituted by an absolute clock or zero.");
        notes.add("Word Prod spans earliest press to latest release only when every character has observed typing timing. It describes surviving-character production, not all deleted attempts. Bulk insert timing is available only as Placement Start/End; no per-character typing times are invented.");
        notes.add("Within Word sums start-to-start intervals in final character order (zero for a one-character token); it is unavailable when that order reverses in time. BfrWord-2 is the preceding token's AfterWord gap when the words remain chronological; BfrWord-1 is this first press minus preceding boundary press; Btwn Word is this first press minus preceding last-character press. AftWord+1 is the following whitespace press minus this last-character press. Negative or unknown intervals are unavailable, not clamped or made absolute. Before-word fields of the first token are zero by convention, not a measured initial pause.");
        notes.add("These timing rules deliberately correct Inputlog's absolute/relative mixing and markup alignment. Immediate deleted attempts are visible in notation/revision history but are not silently folded into surviving-character timing. Target-list matching and Inputlog's special one-letter/deletion endpoint heuristics are not implemented.");
        Matcher matcher = Pattern.compile("\\S+", Pattern.UNICODE_CHARACTER_CLASS).matcher(history.text);
        Row previous = null;
        while (matcher.find()) {
            Row row = new Row(); row.start = matcher.start(); row.end = matcher.end(); row.token = matcher.group();
            List<RevisionHistory.Atom> atoms = history.live.subList(row.start, row.end);
            List<RevisionHistory.Node> fragment = history.fragment(row.start, row.end);
            row.notation = RevisionHistory.notation(fragment); row.revisionIds = revisions(fragment);
            Set<String> provenance = new LinkedHashSet<>(), status = new LinkedHashSet<>();
            boolean presses = true, releases = true, ordered = true;
            Long preceding = null; long within = 0;
            for (RevisionHistory.Atom atom : atoms) {
                provenance.add(atom.provenance);
                if (atom.placement != null) {
                    row.firstPlacement = row.firstPlacement == null ? atom.placement : Math.min(row.firstPlacement, atom.placement);
                    row.lastPlacement = row.lastPlacement == null ? atom.placement : Math.max(row.lastPlacement, atom.placement);
                }
                if (atom.start == null) presses = false;
                else {
                    if (row.first == null || atom.start < row.first) { row.first = atom.start; row.firstKey = atom.keyIndex; }
                    if (preceding != null) {
                        if (atom.start < preceding) ordered = false;
                        else within += atom.start - preceding;
                    }
                    preceding = atom.start;
                }
                if (atom.end == null) releases = false;
                else if (row.last == null || atom.end > row.last) { row.last = atom.end; row.lastKey = atom.keyIndex; }
                if (atom.movedFrom != 0) status.add("inferred move/copy lineage");
            }
            if (!presses) { row.first = null; row.firstKey = null; status.add("some characters lack typing times"); }
            if (!releases) { row.last = null; row.lastKey = null; status.add("some characters lack releases"); }
            if (!ordered) status.add("character order reverses in time");
            row.production = presses && releases ? difference(row.last, row.first) : null;
            row.within = presses && ordered ? within : null;
            row.lastPress = atoms.get(atoms.size() - 1).start;
            if (row.end < history.live.size()) row.boundary = history.live.get(row.end).start;
            row.after = difference(row.boundary, row.lastPress);
            if (previous != null) {
                row.before1 = difference(row.first, previous.boundary);
                row.between = difference(row.first, previous.lastPress);
                row.before2 = row.before1 != null && row.between != null ? previous.after : null;
                if (row.first != null && previous.lastPress != null && row.first < previous.lastPress) status.add("final word order differs from production order");
            } else { row.before2 = 0L; row.before1 = 0L; row.between = 0L; }
            row.provenance = String.join(", ", provenance);
            row.status = status.isEmpty() ? "observed" : String.join("; ", status);
            rows.add(row); previous = row;
        }
    }
    private static Long difference(Long later, Long earlier) { return later == null || earlier == null || later < earlier ? null : later - earlier; }
    private static String revisions(List<RevisionHistory.Node> nodes) {
        Set<String> ids = new LinkedHashSet<>(); collect(nodes, ids); return String.join(" ", ids);
    }
    private static void collect(List<RevisionHistory.Node> nodes, Set<String> ids) {
        for (RevisionHistory.Node node : nodes) {
            if (node instanceof RevisionHistory.Atom) {
                RevisionHistory.Atom atom = (RevisionHistory.Atom) node;
                if (atom.insertedRevision != 0) ids.add(atom.insertedRevision + "-I");
                if (atom.movedFrom != 0) ids.add(atom.movedFrom + "-MOVE");
            } else if (node instanceof RevisionHistory.Deletion) {
                RevisionHistory.Deletion deletion = (RevisionHistory.Deletion) node;
                ids.add(deletion.revision.id + "-D"); collect(deletion.children, ids);
            }
        }
    }
}
