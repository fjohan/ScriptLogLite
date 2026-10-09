package se.lu.scriptloglite.inputlog;

import se.lu.scriptloglite.AnalysisTestSupport;
import se.lu.scriptloglite.CaretLogEvent;
import se.lu.scriptloglite.DirectoryHistory;
import se.lu.scriptloglite.EditEvent;
import se.lu.scriptloglite.EventType;
import se.lu.scriptloglite.KeyLogEvent;
import se.lu.scriptloglite.LogEvent;
import se.lu.scriptloglite.ReplayLog;
import se.lu.scriptloglite.SessionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;

public final class RevisionAnalysisChecks {
    public static void run() throws Exception {
        for (String subject : List.of("92", "96", "97")) {
            Path input = Path.of("JF_" + subject + ".idfx"); if (!Files.exists(input)) continue;
            ReplayLog log = ReplayLog.load(input, -1); WordPausesAnalysis analysis = WordPausesAnalysis.analyze(log);
            check(analysis.history.text.equals(log.finalText), "projection equals replay for JF_" + subject);
            check(SNotationAnalysis.analyze(log).reconstructedText().equals(log.finalText), "standalone notation uses same revision projection");
            check(GeneralAnalysis.analyze(log).audit.isEmpty(), "original Word keyboard lengths/values validate the imported history");
            if (subject.equals("96")) {
                check(log.finalText.equals("one two three four five six"), "JF_96 cut and paste reconstructed exactly");
                check(tokens(analysis).equals(List.of("one", "two", "three", "four", "five", "six")), "six final words, no stray se");
                check(analysis.history.revisions.size() == 2, "one cut and one insertion, no phantom replacement");
                check(analysis.history.revisions.get(0).removed.equals("three ") && analysis.history.revisions.get(1).inserted.equals("three "), "cut keeps selected trailing space exactly");
                check(analysis.history.revisions.get(1).movedFrom == 1, "exact selected content preserves inferred lineage");
                WordPausesAnalysis.Row moved = word(analysis, "three"), four = word(analysis, "four");
                check(moved.first != null && moved.firstPlacement > moved.first && moved.provenance.contains("inferred"), "original typing and later placement separated");
                check(four.before1 == null && four.between == null && four.before2 == null, "moved word does not invent a negative between-word pause");
                check(word(analysis, "one").first == 0 && word(analysis, "one").last == 664, "zero-origin press is valid and never becomes absolute clock");
            } else if (subject.equals("97")) {
                check(tokens(analysis).size() == 66, "JF_97 has 66 final whitespace-delimited words");
                check(log.finalText.startsWith("Robert Cohn was once middleweight boxing champion of Princeton."), "late early-typo correction reconstructed");
                check(!log.finalText.contains("Princetown"), "deleted typo is absent from final text");
                RevisionHistory.Revision last = analysis.history.revisions.get(analysis.history.revisions.size() - 1);
                check(last.removed.equals("w") && last.offset == 61, "last correction removes exactly the early typo");
                check(word(analysis, "Princeton.").last < last.start && word(analysis, "Princeton.").production == 3782,
                        "late deletion does not inflate surviving-character production duration");
                check(analysis.history.revisions.size() == 23, "JF_97 backspace groups and final correction retained");
                long outside = ((List<?>) log.metadata.get("inputlogAncillaryEvents")).stream()
                        .filter(item -> ((Map<?, ?>) item).get("type").equals("keyboard")).count();
                check(outside == 21, "21 coordinate-less external keyboard events retained as ancillary activity");
                check(GeneralAnalysis.analyze(log).checkedKeys == 445, "all 445 document keyboard records independently checked");
            } else {
                check(log.finalText.startsWith("KJELL HÖGLUND\n\nJag"), "title only at new destination");
                check(log.finalText.indexOf("KJELL") == log.finalText.lastIndexOf("KJELL") && !log.finalText.contains("JELL HÖGLUND\n\n\n"), "no residual title at source");
                check(tokens(analysis).size() == 36, "JF_92 final word token count");
                check(word(analysis, "(just").within == null, "late parenthesis does not create invalid within-word duration");
            }
            check(cells(analysis).equals(cells(WordPausesAnalysis.analyze(AnalysisTestSupport.jsonRoundTrip(log)))), "JSON retains revision identity and timing for " + subject);
            check(cells(analysis).equals(cells(WordPausesAnalysis.analyze(AnalysisTestSupport.rawRoundTrip(log)))), "raw retains revision identity and timing for " + subject);
            ReplayLog poisoned = new ReplayLog(log.events); poisoned.metadata.putAll(log.metadata);
            poisoned.metadata.put("inputlogGeneralEvents", List.of(Map.of("invalid", "POISON")));
            check(cells(analysis).equals(cells(WordPausesAnalysis.analyze(poisoned))), "source keyboard/edit payloads cannot affect revision analysis");
            Path wp = Path.of(subject.equals("92") ? "JF_20261008_92_WP.html" : "JF_20261009_" + subject + "_WP.html");
            if (Files.exists(wp)) {
                RevisionAnalysisReport.Comparison comparison = RevisionAnalysisReport.compare(analysis, Files.readString(wp));
                if (subject.equals("97")) {
                    check(comparison.reconstructionMatches && comparison.matched == 66 && comparison.missing == 6 && comparison.extra == 0,
                            "JF_97 reference agrees on text and all words; punctuation/boundary rows differ");
                    check(comparison.equal == 510 && comparison.different == 84, "JF_97 comparison retains explicit timing-definition differences");
                } else check(!comparison.reconstructionMatches && comparison.missing > 0, "comparison exposes wrong Inputlog reconstruction and tokens for " + subject);
                check(comparison.equal > 0 && comparison.different > 0, "same-session timing comparison reports agreements and differences");
            }
            if (subject.equals("96") && Files.exists(Path.of("JF_20261009_96_SN.html")))
                check(!RevisionAnalysisReport.compare(analysis, Files.readString(Path.of("JF_20261009_96_SN.html"))).reconstructionMatches, "SN reference projection independently exposes source reconstruction bug");
            check(RevisionAnalysisReport.html(analysis, "<unsafe>&title", true, null).contains("&lt;unsafe&gt;&amp;title"), "HTML title escaped");
        }
        NativeSession nativeLog = new NativeSession(""); nativeLog.type("one two four five three six");
        nativeLog.cut(18, 24); nativeLog.paste(8, "three ");
        WordPausesAnalysis nativeAnalysis = WordPausesAnalysis.analyze(nativeLog.log());
        check(tokens(nativeAnalysis).equals(List.of("one", "two", "three", "four", "five", "six")), "native selection cut/paste works without Inputlog metadata");
        check(nativeAnalysis.history.revisions.get(1).movedFrom == 1, "native move links exact clipboard content");
        Path nativeJson = Path.of("exp_subj_json_1.json"), nativeRaw = Path.of("exp_subj_raw_1.txt");
        if (Files.exists(nativeJson) && Files.exists(nativeRaw)) check(cells(WordPausesAnalysis.analyze(ReplayLog.load(nativeJson, -1)))
                .equals(cells(WordPausesAnalysis.analyze(ReplayLog.load(nativeRaw, -1)))), "native JSON/raw samples produce identical revision and word timing results");
        nativeLog.paste(nativeLog.text.length(), "three ");
        WordPausesAnalysis copied = WordPausesAnalysis.analyze(nativeLog.log());
        check(copied.history.text.equals(nativeLog.text.toString()) && copied.history.revisions.get(2).movedFrom == 1, "repeated paste preserves lineage without mutating original tombstones");
        nativeLog.command(KeyEvent.VK_C); nativeLog.paste(0, "three ");
        WordPausesAnalysis changedClipboard = WordPausesAnalysis.analyze(nativeLog.log());
        check(changedClipboard.rows.get(0).first == null && changedClipboard.rows.get(0).provenance.equals("bulk edit"), "copy command invalidates uncertain prior clipboard lineage");
        NativeSession corrections = new NativeSession(""); corrections.type("abc"); corrections.backspace(); corrections.backspace(); corrections.type("xy ");
        WordPausesAnalysis corrected = WordPausesAnalysis.analyze(corrections.log());
        check(corrected.history.text.equals("axy ") && corrected.history.revisions.size() == 1 && corrected.history.revisions.get(0).removed.equals("bc"), "backspaces group in original character order");
        NativeSession nested = new NativeSession(""); nested.type("abc def"); nested.remove(4, 5); nested.remove(2, 5);
        WordPausesAnalysis nestedAnalysis = WordPausesAnalysis.analyze(nested.log());
        check(nestedAnalysis.history.text.equals("abf") && nestedAnalysis.history.notation().contains("[d]"), "nested deletion tombstones preserved, excluded from text");
        NativeSession literals = new NativeSession("[x] {y} | ·\\"); literals.replace(1, 2, "<&>");
        WordPausesAnalysis literalAnalysis = WordPausesAnalysis.analyze(literals.log());
        check(literalAnalysis.history.text.equals(literals.text.toString()) && literalAnalysis.history.notation().contains("\\["), "literal markup does not affect reconstruction");
        check(RevisionAnalysisReport.html(literalAnalysis, "Literal", false, null).contains("&lt;&amp;&gt;"), "literal HTML text escaped");
        NativeSession missing = new NativeSession(""); missing.press(KeyEvent.VK_A, "a", 0); missing.insert(0, "a");
        WordPausesAnalysis unknown = WordPausesAnalysis.analyze(missing.log());
        check(word(unknown, "a").first != null && word(unknown, "a").last == null && word(unknown, "a").production == null, "missing release is unknown, not zero duration");
        NativeSession bulk = new NativeSession(""); bulk.insert(0, "a b");
        WordPausesAnalysis bulkAnalysis = WordPausesAnalysis.analyze(bulk.log());
        check(bulkAnalysis.rows.get(0).first == null && bulkAnalysis.rows.get(0).within == null && bulkAnalysis.rows.get(0).firstPlacement != null, "bulk edit has placement but no invented typing times");
        NativeSession initial = new NativeSession("before"); WordPausesAnalysis preexisting = WordPausesAnalysis.analyze(initial.log());
        check(preexisting.rows.get(0).firstPlacement == null && preexisting.rows.get(0).first == null, "preexisting text has unavailable timing");
        java.util.Random random = new java.util.Random(31996);
        NativeSession fuzz = new NativeSession("");
        for (int i = 0; i < 240; i++) {
            int start = random.nextInt(fuzz.text.length() + 1), end = start + random.nextInt(fuzz.text.length() - start + 1);
            fuzz.replace(start, end, List.of("", "a b", "[q]", "\n", "🙂", "xy").get(random.nextInt(6)));
            check(WordPausesAnalysis.analyze(fuzz.log()).history.text.equals(fuzz.text.toString()), "random insert/delete/replace projection at " + i);
        }
        try { RevisionAnalysisReport.compare(nativeAnalysis, "<html>no table</html>"); throw new AssertionError("Invalid revision reference accepted"); }
        catch (IllegalArgumentException expected) { }
        SwingUtilities.invokeAndWait(() -> check(new RevisionAnalysisPanel(nativeAnalysis, "Native", true, new DirectoryHistory(Path.of("target", "revision-test.properties"))).getComponentCount() == 2, "revision tab builds headlessly"));
        System.out.println("Revisions/Word Pauses: JF_92/96/97 reconstruction, external keys, native moves, tombstones, timing gaps, JSON/raw and randomized edits checked");
    }
    private static final class NativeSession {
        final List<LogEvent> events = new ArrayList<>(); final StringBuilder text;
        final Instant start = Instant.parse("2026-01-01T00:00:00Z"); long clock = 10;
        NativeSession(String initial) { events.add(new SessionEvent(start, initial)); text = new StringBuilder(initial); }
        void press(int code, String value, int modifiers) { events.add(new KeyLogEvent(start.plusMillis(clock), EventType.KEY_PRESSED, code, KeyEvent.getKeyText(code), value, modifiers, "", 1)); }
        void command(int code) { clock += 20; press(code, "", InputEvent.CTRL_DOWN_MASK); }
        void type(String value) {
            for (char ch : value.toCharArray()) {
                clock += 30; int code = ch == '\n' ? KeyEvent.VK_ENTER : KeyEvent.getExtendedKeyCodeForChar(ch);
                press(code, "" + ch, 0); insert(text.length(), "" + ch);
                events.add(new KeyLogEvent(start.plusMillis(clock + 5), EventType.KEY_RELEASED, code, KeyEvent.getKeyText(code), "" + ch, 0, "", 1)); clock += 5;
            }
        }
        void cut(int from, int to) { events.add(new CaretLogEvent(start.plusMillis(clock), to, from)); command(KeyEvent.VK_X); remove(from, to); }
        void paste(int offset, String value) { command(KeyEvent.VK_V); insert(offset, value); }
        void backspace() { clock += 10; press(KeyEvent.VK_BACK_SPACE, "\b", 0); remove(text.length() - 1, text.length()); }
        void insert(int offset, String value) { replace(offset, offset, value); }
        void remove(int from, int to) { replace(from, to, ""); }
        void replace(int from, int to, String value) {
            clock++; String old = text.substring(from, to);
            events.add(new EditEvent(start.plusMillis(clock), old.isEmpty() ? EventType.INSERT : value.isEmpty() ? EventType.REMOVE : EventType.REPLACE, from, old, value));
            text.replace(from, to, value);
        }
        ReplayLog log() { return new ReplayLog(events); }
    }
    private static List<String> tokens(WordPausesAnalysis analysis) { List<String> result = new ArrayList<>(); for (WordPausesAnalysis.Row row : analysis.rows) result.add(row.token); return result; }
    private static WordPausesAnalysis.Row word(WordPausesAnalysis analysis, String token) { return analysis.rows.stream().filter(row -> row.token.equals(token)).findFirst().orElseThrow(); }
    private static List<String> cells(WordPausesAnalysis analysis) {
        List<String> result = new ArrayList<>(); result.add(analysis.history.text); result.add(analysis.history.notation());
        for (WordPausesAnalysis.Row row : analysis.rows) result.add(row.cells().toString()); return result;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError("Revision analysis: " + message); }
}
