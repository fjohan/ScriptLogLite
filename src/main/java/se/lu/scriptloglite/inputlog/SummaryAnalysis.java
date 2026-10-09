package se.lu.scriptloglite.inputlog;

import se.lu.scriptloglite.ReplayLog;
import se.lu.scriptloglite.EditEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Process statistics from the event stream, with separate statistics of the replayed product. */
public final class SummaryAnalysis {
    static final class Metric {
        final String section, label, value;
        Metric(String section, String label, String value) { this.section = section; this.label = label; this.value = value; }
        String key() { return section + "|" + label; }
    }
    final ReplayLog log;
    final String creationTime = java.time.Instant.now().toString();
    final boolean internal;
    final List<Metric> metrics = new ArrayList<>();
    final List<Metric> product = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    private final List<Integer> words = new ArrayList<>(), sentenceChars = new ArrayList<>(), sentenceWords = new ArrayList<>(),
            paragraphChars = new ArrayList<>(), paragraphWords = new ArrayList<>(), paragraphSentences = new ArrayList<>();
    private int word, sentence, sentenceWord, paragraph, paragraphWord, paragraphSentence;
    private int typed, spaces, formatting, inserted, replaced;
    long processMillis;

    public static SummaryAnalysis analyze(ReplayLog log) { return new SummaryAnalysis(log, InternalGeneralEvents.build(log), true); }
    public static SummaryAnalysis analyzeSource(ReplayLog log) {
        ReplayLog imported = GeneralAnalysis.sourceHistory(log);
        SummaryAnalysis result = new SummaryAnalysis(imported, GeneralAnalysis.sourceRecords(log.metadata.get("inputlogGeneralEvents")), false);
        if (imported.events.size() < log.events.size()) result.notes.add("Source mode excludes " + (log.events.size() - imported.events.size()) + " later native events; internal mode includes the full history.");
        return result;
    }
    private SummaryAnalysis(ReplayLog log, List<GeneralAnalysis.Source> sources, boolean internal) {
        this.log = log; this.internal = internal;
        notes.add("Pause threshold: 0 ms (PT0). This implements the process-information and total-process-time sections of Summary Analysis; pause/burst modules and configurable Inputlog FSM rules are not implemented.");
        notes.add("Process measures count observed typing, including characters subsequently deleted. They are not counts of the final document. Paste/replacement text contributes separately.");
        notes.add("Word boundaries use whitespace and word punctuation; paragraphs use returns and focus changes; sentences use terminal punctuation followed by whitespace/end and focus changes. Empty units are omitted. These boundary rules may differ from Inputlog's FSM for other sessions.");
        notes.add("Compatibility means use total typed characters; medians use unit character counts excluding whitespace. Unit counts use Inputlog-style word/sentence/paragraph character categories, which differ from the Unicode punctuation categories used for Total Typed. Population deviations follow Inputlog's count > 2 and blank-for-zero conventions. Paragraph character deviation uses total typed non-whitespace as its denominator sum, as in Inputlog.");
        if (internal) {
            notes.add("Keyboard/edit values come only from typed internal events. Ordinary one-character typing/backspace edits are associated with their press; other edits count as inserts/replacements. Reconstructed product statistics come from replay, not retained Word statistics.");
            notes.add("A source Word replacement that changes no text has no internal edit. A cut is an actual deletion, not the source's replacement with a paragraph mark; replacement counts can therefore differ.");
        } else notes.add("Source mode uses retained Inputlog keyboard/edit records, including its deferred document-length test for replacement counts. Product statistics still use reconstructed text.");
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        boolean main = true;
        int previousLength = 0, pendingLength = 0, pendingText = 0;
        boolean pending = false;
        int importedCount = log.metadata.get("inputlogGeneralEventCount") instanceof Number
                ? ((Number) log.metadata.get("inputlogGeneralEventCount")).intValue() : Integer.MAX_VALUE;
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            GeneralAnalysis.Source source = sources.get(sourceIndex);
            if (internal && source.index != null && source.index >= importedCount) main = true;
            if (List.of("keyboard", "mouse", "focus", "scrollChange", "insert", "replacement").contains(source.type) && source.start() > 0) {
                first = Math.min(first, source.start()); last = Math.max(last, Math.max(source.start(), source.end()));
            }
            if (source.type.equals("focus")) {
                flushWord(); flushSentence(); flushParagraph();
                main = mainDocument(source.win.getOrDefault("title", ""), log.metadata);
            } else if (source.type.equals("insert")) {
                if (main) inserted += GeneralAnalysis.decode(source.word.getOrDefault("before", "")).length();
            } else if (source.type.equals("replacement")) {
                int count = GeneralAnalysis.decode(source.word.getOrDefault("newtext", "")).length();
                if (internal) { if (main) replaced += count; }
                else { pending = true; pendingLength = previousLength; pendingText = count; }
            } else if (source.type.equals("keyboard")) {
                if (!internal && pending) {
                    if (main && source.length() != pendingLength) replaced += pendingText;
                    pending = false;
                }
                previousLength = source.length();
                if (!main) continue;
                if (!source.modifiers().isEmpty()) formatting++;
                String value = source.value();
                if (value.length() != 1) continue;
                char ch = value.charAt(0);
                if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) spaces++;
                else if (Character.isLetter(ch) || number(ch) || punctuation(ch)) typed++;
                // Modifier/revision presses do not advance lexical units.
                if (source.key().endsWith("SHIFT") || source.key().endsWith("CONTROL") || source.key().endsWith("MENU")
                        || List.of("VK_BACK", "VK_DELETE", "VK_CLEAR", "VK_ESCAPE").contains(source.key())) continue;
                if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                    flushWord();
                    if (ch == '\n' || ch == '\r' || ch == '\f') flushParagraph();
                } else if (withinWord(ch) || ch == '¡' || ch == '¿') {
                    word++; sentence++; paragraph++;
                } else if ("&|\\\"(§^{}<>/,;:=+%*".indexOf(ch) >= 0) {
                    flushWord(); sentence++; paragraph++;
                } else if (".!?".indexOf(ch) >= 0) {
                    flushWord(); paragraph++;
                    // Decimal/abbreviation punctuation inside a token does not close a sentence.
                    String next = nextValue(sources, sourceIndex);
                    if (next.isEmpty() || next.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) flushSentence();
                }
            }
        }
        flushWord(); flushSentence(); flushParagraph();
        processMillis = first == Long.MAX_VALUE ? 0 : Math.max(0, last - first);
        populate();
        String text = log.finalText;
        addProduct("Characters (including LF and whitespace, UTF-16)", text.length());
        addProduct("Characters (excluding whitespace, UTF-16)", (int) text.chars().filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)).count());
        addProduct("Words (Unicode letter/number tokens)", (int) Pattern.compile("[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*").matcher(text).results().count());
        addProduct("Nonempty paragraphs", (int) text.lines().filter(line -> !line.isBlank()).count());
        long editInserts = log.events.stream().filter(e -> e instanceof EditEvent).mapToLong(e -> ((EditEvent) e).replacement().length()).sum();
        long editRemovals = log.events.stream().filter(e -> e instanceof EditEvent).mapToLong(e -> ((EditEvent) e).removed.length()).sum();
        addProduct("Inserted UTF-16 units (all actual edits)", editInserts);
        addProduct("Removed UTF-16 units (all actual edits)", editRemovals);
        notes.add("Missing mouse/focus logging cannot be inferred for native recordings. Process time runs from the first to last available timed action, including key releases, scroll changes and retained mouse durations; caret/session markers are excluded. Word page/line statistics are not inferred.");
    }
    private static String nextValue(List<GeneralAnalysis.Source> sources, int index) {
        for (int i = index + 1; i < sources.size(); i++) {
            GeneralAnalysis.Source next = sources.get(i);
            if (next.type.equals("focus")) return "";
            if (next.type.equals("keyboard") && !next.value().isEmpty() && !next.key().equals("VK_BACK")) return next.value();
        }
        return "";
    }
    private static boolean mainDocument(String title, Map<String, Object> metadata) {
        String lower = title.toLowerCase(Locale.ROOT);
        if (lower.contains("wordlog") || lower.contains("maindoc")) return true;
        Object meta = metadata.get("inputlogMeta");
        Object name = meta instanceof Map ? ((Map<?, ?>) meta).get("__MainDocument") : null;
        return name != null && lower.equals(name.toString().toLowerCase(Locale.ROOT));
    }
    private static boolean number(char ch) {
        int type = Character.getType(ch);
        return type == Character.DECIMAL_DIGIT_NUMBER || type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER;
    }
    private static boolean punctuation(char ch) {
        int type = Character.getType(ch);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION || type == Character.OTHER_PUNCTUATION;
    }
    private static boolean withinWord(char ch) { return Character.isLetter(ch) || number(ch) || "'`@_~".indexOf(ch) >= 0; }
    private void flushWord() {
        if (word == 0) return;
        words.add(word); sentenceWord++; paragraphWord++; word = 0;
    }
    private void flushSentence() {
        if (sentence == 0) return;
        sentenceChars.add(sentence); sentenceWords.add(sentenceWord); paragraphSentence++;
        sentence = 0; sentenceWord = 0;
    }
    private void flushParagraph() {
        if (paragraph == 0) return;
        paragraphChars.add(paragraph); paragraphWords.add(paragraphWord); paragraphSentences.add(paragraphSentence);
        paragraph = 0; paragraphWord = 0; paragraphSentence = 0;
    }
    private void populate() {
        int total = typed + spaces;
        String keys = "Keystrokes Produced in This Session";
        add(keys, "Total Keystrokes incl. Inserted and Replaced Characters in Main Document", total + formatting + inserted + replaced);
        add(keys, "- Total Non-Character Keys", formatting);
        add(keys, "- Characters Inserted", inserted); add(keys, "- Characters Replaced", replaced);
        add(keys, "- Total Typed (incl.spaces)", total); add(keys, "- Per Minute (incl. spaces)", rate(total));
        add(keys, "- Total Typed (excl.spaces)", typed); add(keys, "- Per Minute (excl.spaces)", rate(typed));
        add("Words", "Total Words in Main Document", words.size()); add("Words", "Per Minute", rate(words.size()));
        add("Words", "Mean Word Length", ratio(sum(words), words.size()));
        add("Words", "Median Word Length", median(words)); add("Words", "Standard Deviation Word Length", deviation(words, sum(words)));
        units("Sentences", "Sentence", sentenceChars, sentenceWords, null, total, words.size(), 0);
        units("Paragraphs", "Paragraph", paragraphChars, paragraphWords, paragraphSentences, total, words.size(), sentenceChars.size());
        add("Process Time", "Total Process Time (s)", decimal(processMillis / 1000.0));
    }
    private void units(String section, String unit, List<Integer> chars, List<Integer> wordCounts, List<Integer> sentences,
                       int total, int totalWords, int totalSentences) {
        add(section, "Total " + section + " in Main Document", chars.size());
        add(section, "Mean Characters/" + unit, ratio(total, chars.size()));
        add(section, "Median Characters/" + unit, median(chars));
        add(section, "Standard Deviation Characters/" + unit, deviation(chars, unit.equals("Paragraph") ? typed : sum(chars)));
        add(section, "Mean Words/" + unit, ratio(totalWords, chars.size()));
        add(section, "Median Words/" + unit, median(wordCounts));
        add(section, "Standard Deviation Words/" + unit, deviation(wordCounts, totalWords));
        if (sentences != null) {
            add(section, "Mean Sentences/" + unit, ratio(totalSentences, chars.size()));
            add(section, "Median Sentences/" + unit, median(sentences));
            add(section, "Standard Deviation Sentences/" + unit, deviation(sentences, totalSentences));
        }
    }
    private String rate(int value) { return processMillis == 0 ? "" : decimal(value * 60000.0 / processMillis); }
    private static String ratio(int total, int count) { return decimal(count == 0 ? 0 : total / (double) count); }
    static String decimal(double value) { return String.format(Locale.ROOT, "%.3f", value); }
    private static int sum(List<Integer> list) { return list.stream().mapToInt(Integer::intValue).sum(); }
    private static String median(List<Integer> list) {
        if (list.isEmpty()) return "0";
        List<Integer> sorted = new ArrayList<>(list); sorted.sort(Integer::compare);
        int middle = sorted.size() / 2;
        double result = sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + (double) sorted.get(middle)) / 2;
        return result == Math.rint(result) ? Long.toString((long) result) : decimal(result);
    }
    private static String deviation(List<Integer> list, int total) {
        if (list.size() <= 2) return "";
        double squares = list.stream().mapToDouble(x -> (double) x * x).sum();
        double variance = (squares - (double) total * total / list.size()) / list.size();
        return variance < 1e-4 ? "" : decimal(Math.sqrt(variance));
    }
    private void add(String section, String label, Object value) { metrics.add(new Metric(section, label, value.toString())); }
    private void addProduct(String label, long value) { product.add(new Metric("Reconstructed product", label, Long.toString(value))); }
}
