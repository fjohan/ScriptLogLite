package se.lu.scriptloglite.inputlog;

import se.lu.scriptloglite.ReplayLog;
import se.lu.scriptloglite.ReplayCursor;
import se.lu.scriptloglite.LogEvent;
import se.lu.scriptloglite.KeyLogEvent;
import se.lu.scriptloglite.EditEvent;
import se.lu.scriptloglite.EventType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Independently calculated Inputlog-style event table and conversion checks. */
public final class GeneralAnalysis {
    static final List<String> COLUMNS = List.of("#Id", "Event Type", "Output", "Position Full", "Position",
            "DocLength Full", "DocLength", "Character Production", "StartTime", "StartClock", "EndTime",
            "EndClock", "ActionTime", "PauseTime", "PauseLocation", "PauseLocation", "IntervalFixedSize",
            "IntervalFixedNumber", "X", "Y");
    static final String[] LOCATIONS = {"", "WITHIN WORDS", "BEFORE WORDS", "AFTER WORDS", "BEFORE SENTENCES",
            "AFTER SENTENCES", "BEFORE PARAGRAPHS", "AFTER PARAGRAPHS", "INITIAL", "END", "CHANGE", "REVISION",
            "COMBINATION KEY", "EYETRACK", "SPEECH", "UNKNOWN", "MOUSE"};
    static final Pattern ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    final List<Row> rows = new ArrayList<>();
    final List<String> audit = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    int checkedKeys;
    final ReplayLog log;
    final boolean internal;

    static final class Source {
        String id, type;
        Map<String, String> win = new LinkedHashMap<>(), word = new LinkedHashMap<>();
        Integer index;
        Integer production;
        long start() { return number(win.get("startTime"), 0); }
        long end() { return number(win.get("endTime"), 0); }
        int position() { return (int) number(word.get("position"), 0); }
        int length() { return (int) number(word.get("documentLength"), 0); }
        String key() { return win.getOrDefault("key", ""); }
        String value() {
            if (key().equals("VK_RETURN")) return "\n";
            if (key().equals("VK_BACK")) return "\b";
            String value = decode(win.getOrDefault("value", ""));
            return value.equals("\\w") ? " " : value;
        }
        List<String> modifiers() {
            List<String> result = new ArrayList<>();
            Matcher matcher = Pattern.compile("VK_[A-Z0-9_]+").matcher(win.getOrDefault("keyboardstate", ""));
            while (matcher.find()) if (modifier(matcher.group())) result.add(matcher.group());
            return result;
        }
    }
    static final class Row {
        String id, type, output = "";
        Integer position, length, x, y;
        int displayPosition, displayLength, production, location, sizeInterval = 1, numberInterval;
        Long start, end;
        long action, pause;
        List<String> cells() {
            return List.of(id, type, printable(output), string(position), "" + displayPosition, string(length),
                    "" + displayLength, "" + production, string(start), clock(start), string(end), clock(end),
                    "" + action, "" + pause, "" + location, LOCATIONS[location], "" + sizeInterval,
                    "" + numberInterval, string(x), string(y));
        }
    }
    public static GeneralAnalysis analyze(ReplayLog log) throws Exception {
        return new GeneralAnalysis(log, InternalGeneralEvents.build(log), true);
    }
    public static GeneralAnalysis analyzeSource(ReplayLog log) {
        ReplayLog imported = sourceHistory(log);
        GeneralAnalysis result = new GeneralAnalysis(imported, sourceRecords(log.metadata.get("inputlogGeneralEvents")), false);
        if (imported.events.size() < log.events.size()) result.notes.add("Source mode describes the imported session only; " + (log.events.size() - imported.events.size()) + " later native events are excluded. Internal mode includes the complete current history.");
        return result;
    }
    static ReplayLog sourceHistory(ReplayLog log) {
        if (!(log.metadata.get("inputlogGeneralEvents") instanceof List)) {
            throw new IllegalArgumentException("No retained Inputlog source events; reopen the original IDFX to use source mode.");
        }
        Object countValue = log.metadata.get("inputlogGeneralEventCount");
        if (!(countValue instanceof Number)) throw new IllegalArgumentException("Missing imported-session boundary; reopen the IDFX for source mode.");
        int count = ((Number) countValue).intValue();
        if (count < 1 || count > log.events.size()) throw new IllegalArgumentException("Invalid imported-session boundary");
        ReplayLog imported = log;
        if (count < log.events.size()) {
            imported = new ReplayLog(log.events.subList(0, count)); imported.metadata.putAll(log.metadata);
        }
        return imported;
    }

    static List<Source> sourceRecords(Object records) {
        List<Source> all = new ArrayList<>();
        if (!(records instanceof List)) return all;
        for (Object item : (List<?>) records) {
            Map<?, ?> map = (Map<?, ?>) item;
            Source source = new Source(); source.id = map.get("id").toString(); source.type = map.get("type").toString();
            if (map.get("convertedKeyIndex") instanceof Number) source.index = ((Number) map.get("convertedKeyIndex")).intValue();
            for (Object partItem : (List<?>) map.get("parts")) {
                Map<?, ?> part = (Map<?, ?>) partItem;
                Map<String, String> fields = part.get("type").equals("winlog") ? source.win : source.word;
                for (Object fieldItem : (List<?>) part.get("fields")) {
                    Map<?, ?> field = (Map<?, ?>) fieldItem;
                    fields.put(field.get("name").toString(), field.get("value").toString());
                }
            }
            all.add(source);
        }
        return all;
    }
    private GeneralAnalysis(ReplayLog log, List<Source> all, boolean internal) {
        this.log = log; this.internal = internal;
        if (!internal) auditKeys(all);
        else {
            notes.add("Calculated directly from typed events and reconstructed text. No IDFX export/reimport is used.");
            notes.add("Document lengths and character production include one virtual final paragraph mark for Inputlog comparison; the reconstructed text itself does not.");
            notes.add("Nearby matching typing/backspace edits are combined with their key press. Other edits remain separate rows, with their actual edit timestamps.");
            notes.add("Key pairs use typed stroke IDs when available. Older logs infer the latest matching code/location press. A zero action duration for an unreleased key means unavailable timing, not an instantaneous keystroke.");
            notes.add("X/Y in scrollChange rows are viewport offsets; X/Y in retained mouse rows are screen coordinates.");
            notes.add("Positions/document lengths and character production are reconstructed; source-only Word no-op replacements are absent. Mouse/focus context, when available, comes from retained ancillary records.");
            if (log.metadata.get("inputlogGeneralEventCount") instanceof Number
                    && ((Number) log.metadata.get("inputlogGeneralEventCount")).intValue() == log.events.size()) {
                try { auditKeys(sourceRecords(log.metadata.get("inputlogGeneralEvents"))); }
                catch (RuntimeException exception) { audit.add("Original source audit could not be completed: " + exception.getMessage()); }
            } else notes.add("Original source checks are unavailable for this history; edits are validated by replay.");
        }
        Map<Source, Long> allOrder = new java.util.IdentityHashMap<>();
        long orderingTime = 0;
        for (Source source : all) { if (source.start() > 0) orderingTime = source.start(); allOrder.put(source, orderingTime); }
        List<Source> numbered = new ArrayList<>(all);
        numbered.sort(java.util.Comparator.comparingLong(allOrder::get));
        Map<Source, String> analysisIds = new java.util.IdentityHashMap<>();
        for (int i = 0; i < numbered.size(); i++) analysisIds.put(numbered.get(i), Integer.toString(i));
        List<Source> filtered = new ArrayList<>();
        for (Source source : all) if (List.of("keyboard", "mouse", "focus", "insert", "replacement", "scrollChange").contains(source.type)
                && !(source.type.equals("keyboard") && source.end() == 0 && List.of("VK_UP", "VK_DOWN", "VK_LEFT", "VK_RIGHT").contains(source.key()))) filtered.add(source);
        // Timed entries can arrive out of order; Word-only entries stay attached to their predecessor.
        long anchor = 0;
        Map<Source, Long> ordering = new java.util.IdentityHashMap<>();
        for (Source source : filtered) { if (source.start() > 0) anchor = source.start(); ordering.put(source, anchor); }
        filtered.sort(java.util.Comparator.comparingLong(ordering::get));
        long origin = filtered.stream().filter(s -> s.start() > 0).mapToLong(Source::start).findFirst().orElse(0);
        long last = filtered.stream().filter(s -> s.end() > 0).mapToLong(Source::end).max().orElse(origin) - origin;
        int length = 0, position = 0, deleted = 0, size = 1;
        long previousPoint = origin, currentPoint = origin;
        Source previous = null;
        Row previousRow = null;
        boolean previousComposite = false;
        Row pendingWordRow = null;
        for (int i = 0; i < filtered.size(); i++) {
            Source source = filtered.get(i);
            Row row = new Row(); row.id = analysisIds.get(source); row.type = source.type;
            if (!internal && (source.type.equals("replacement") || source.type.equals("insert")) && !rows.isEmpty()) pendingWordRow = rows.get(rows.size() - 1);
            boolean composite = source.type.equals("keyboard") && !source.modifiers().isEmpty();
            if (source.start() > 0) {
                row.start = source.start() - origin;
                row.end = source.end() == 0 ? row.start : source.end() - origin;
                row.action = Math.max(0, row.end - row.start);
                row.numberInterval = last == 0 ? 0 : (int) Math.ceil(row.start * 10.0 / last);
                if (row.start > size * 60000L) { row.sizeInterval = size; size++; }
                else row.sizeInterval = size;
            } else if (previousRow != null) {
                // Inputlog's HTML renderer carries timing into untimed Word rows.
                row.start = previousRow.start; row.end = previousRow.end;
                row.numberInterval = previousRow.numberInterval;
            }
            if (source.start() == 0) row.sizeInterval = size;
            if (!source.type.equals("focus") && previous != null) {
                if ((previous.type.equals("mouse") || previous.type.equals("scrollChange")) && previous.end() > 0) previousPoint = previous.end();
                else if (List.of("keyboard", "focus").contains(previous.type) && previous.start() > 0) previousPoint = previous.start();
            }
            if (source.type.equals("keyboard") || source.type.equals("mouse") || source.type.equals("scrollChange")) {
                if (source.start() > 0) currentPoint = source.start();
                row.pause = Math.abs(currentPoint - previousPoint);
            }
            switch (source.type) {
                case "keyboard":
                    row.position = source.position(); row.length = source.length();
                    if (length > source.length()) deleted += length - source.length();
                    length = source.length(); position = source.position();
                    row.output = keyOutput(source);
                    row.location = pauseLocation(source, filtered, i);
                    break;
                case "mouse":
                    String mouseType = source.win.getOrDefault("type", "");
                    row.output = mouseType.equals("movement") ? "Movement" : mouseType.equals("click")
                            ? source.win.getOrDefault("button", "") + " Click" : "Scroll";
                    row.x = (int) number(source.win.get("x"), 0); row.y = (int) number(source.win.get("y"), 0);
                    row.location = 16; break;
                case "scrollChange":
                    row.x = (int) number(source.win.get("x"), 0); row.y = (int) number(source.win.get("y"), 0);
                    row.output = "Viewport [" + row.x + ":" + row.y + "]"; row.location = 16; break;
                case "focus": row.output = source.win.getOrDefault("title", ""); row.location = 10; break;
                case "replacement":
                    row.output = "[" + source.word.get("start") + ":" + source.word.get("end") + "] " + decode(source.word.getOrDefault("newtext", ""));
                    if (internal) { row.position = source.position(); row.length = length = source.length(); }
                    row.location = 10; break;
                case "insert":
                    row.output = "[" + decode(source.word.getOrDefault("before", "")) + "]";
                    int inserted = decode(source.word.getOrDefault("before", "")).length();
                    length = internal ? source.length() : length + inserted; row.length = length;
                    row.position = position = internal ? source.position() : length - inserted + 1;
                    row.location = 10; break;
                default: throw new IllegalArgumentException("Unsupported general event " + source.type);
            }
            if (internal && (source.type.equals("mouse") || source.type.equals("focus") || source.type.equals("scrollChange"))) {
                row.position = source.position(); row.length = source.length();
            }
            row.displayLength = row.length == null ? length : row.length;
            row.displayPosition = row.position == null ? position : row.position;
            row.production = internal && source.production != null ? source.production : length + deleted;
            if (pendingWordRow != null && row.position != null && row.position > 0) {
                // Preserve Inputlog's legacy backfill convention for comparison;
                // the conversion audit above always uses the unmodified history.
                pendingWordRow.position = row.position; pendingWordRow.length = row.length;
                pendingWordRow = null;
            }
            if (composite && !previousComposite && !rows.isEmpty()) {
                Row modifier = rows.remove(rows.size() - 1);
                row.pause = modifier.pause;
                row.action = row.end == null || modifier.start == null ? 0 : Math.max(0, row.end - modifier.start);
            }
            previousComposite = composite;
            rows.add(row); previous = source; previousRow = row;
        }
        int displayPosition = 0, displayLength = 0;
        for (Row row : rows) {
            if (row.position != null) displayPosition = row.position;
            if (row.length != null) displayLength = row.length;
            row.displayPosition = displayPosition; row.displayLength = displayLength;
        }
        markEndpoints();
    }
    private void auditKeys(List<Source> sources) {
        ReplayCursor cursor = new ReplayCursor(log);
        java.util.Set<String> releases = new java.util.HashSet<>();
        for (LogEvent event : log.events) if (event instanceof KeyLogEvent && event.type == EventType.KEY_RELEASED) {
            KeyLogEvent release = (KeyLogEvent) event;
            releases.add(release.keyText + ":" + Duration.between(log.events.get(0).time, release.time).toMillis());
        }
        for (Source source : sources) if (source.type.equals("keyboard")) {
            if (source.index == null || source.index < 1 || source.index >= log.events.size()
                    || !(log.events.get(source.index) instanceof KeyLogEvent)) {
                audit.add("Source event " + source.id + ": converted key link is missing or invalid."); continue;
            }
            cursor.seek(source.index - 1);
            int actual = cursor.state().text.length() + 1;
            checkedKeys++;
            if (actual != source.length()) audit.add("Source event " + source.id + ": reconstructed Word length " + actual + " != logged " + source.length());
            KeyLogEvent key = (KeyLogEvent) log.events.get(source.index);
            Object meta = log.metadata.get("inputlogMeta");
            if (meta instanceof Map && ((Map<?, ?>) meta).containsKey("__LogRelativeCreationDate")) {
                long origin = Long.parseLong(((Map<?, ?>) meta).get("__LogRelativeCreationDate").toString());
                long pressed = Duration.between(log.events.get(0).time, key.time).toMillis();
                if (pressed != source.start() - origin) audit.add("Source event " + source.id + ": converted press timing differs.");
                if (source.end() >= source.start()) {
                    boolean releaseFound = releases.contains(source.key() + ":" + (source.end() - origin));
                    if (!releaseFound) audit.add("Source event " + source.id + ": converted release timing is missing or differs.");
                }
            }
            if (!source.key().equals(key.keyText) || !source.value().equals(key.keyChar)) audit.add("Source event " + source.id + ": converted key/value differs.");
            if (Boolean.parseBoolean(source.word.getOrDefault("replay", "false"))) {
                EditEvent edit = null;
                for (int i = source.index + 1; i < log.events.size(); i++) {
                    LogEvent candidate = log.events.get(i);
                    if (candidate instanceof KeyLogEvent && candidate.type == EventType.KEY_PRESSED) break;
                    if (candidate instanceof EditEvent) { edit = (EditEvent) candidate; break; }
                }
                boolean backspace = source.key().equals("VK_BACK");
                if (edit == null || edit.offset != source.position() - (backspace ? 1 : 0)
                        || !edit.replacement().equals(backspace ? "" : source.value())
                        || edit.removed.length() != (backspace ? 1 : 0)) {
                    audit.add("Source event " + source.id + ": converted replay edit differs from the recorded key/position.");
                }
            }
        }
    }
    private void markEndpoints() {
        List<Row> nonfocus = new ArrayList<>(); for (Row row : rows) if (!row.type.equals("focus")) nonfocus.add(row);
        if (nonfocus.isEmpty()) return;
        if (nonfocus.get(0).location != 4) nonfocus.get(0).location = 8;
        if (nonfocus.size() > 1 && nonfocus.get(0).output.equals("Movement")) nonfocus.get(1).location = 8;
        nonfocus.get(nonfocus.size() - 1).location = 9;
        boolean hasParagraph = rows.stream().anyMatch(r -> r.location == 6);
        if (hasParagraph && nonfocus.size() > 1) nonfocus.get(nonfocus.size() - 2).location = 7;
        for (int i = rows.size() - 1; i >= 0; i--) if (rows.get(i).type.equals("keyboard") && rows.get(i).location != 12) {
            Row row = rows.get(i); if (row.location == 1 || row.location == 15 || row.location == 2 || row.location == 3) row.location = 5; break;
        }
    }
    private static int pauseLocation(Source source, List<Source> list, int index) {
        String key = source.key(), value = source.value();
        if (modifier(key) || value.isEmpty() && !source.modifiers().isEmpty()) return 12;
        if (List.of("VK_BACK", "VK_DELETE", "VK_CLEAR", "VK_ESCAPE").contains(key)) return 11;
        if (value.isEmpty()) return 15;
        if (value.equals("\n")) {
            Source preceding = previousCharacter(list, index);
            if (preceding != null && preceding.value().equals("\n") && paragraphCharacters(list, list.indexOf(preceding)) < 3) return 2;
            Source next = null;
            for (int i = index + 1; i < list.size(); i++) {
                if (list.get(i).type.equals("mouse") || list.get(i).type.equals("focus")) break;
                if (list.get(i).type.equals("keyboard") && !modifier(list.get(i).key())) { next = list.get(i); break; }
            }
            return next != null && next.value().equals("\n") && paragraphCharacters(list, index) >= 3 ? 7 : 6;
        }
        if (value.equals(" ") || value.equals("\t")) {
            Source preceding = previousCharacter(list, index);
            return preceding != null && (preceding.value().equals(" ") || preceding.value().equals("\t") || preceding.value().equals("\n")) ? 2 : 3;
        }
        if (!wordCharacter(value) && !"&|\\\"(§^{}<>/,;:=+%*-".contains(value)) return 15;
        Source preceding = previousCharacter(list, index);
        if (preceding == null && wordCharacter(value)) return 4;
        if (preceding != null && (preceding.value().equals(" ") || preceding.value().equals("\t") || preceding.value().equals("\n"))) return 2;
        if (value.length() == 1 && "&|\\\"(§^{}<>/,;:=+%*".contains(value)) return 3;
        return 1;
    }
    private static int paragraphCharacters(List<Source> list, int index) {
        int count = 0;
        for (int i = index - 1; i >= 0; i--) {
            Source source = list.get(i);
            if (source.type.equals("mouse") || source.type.equals("focus") || source.type.equals("scrollChange") || source.value().equals("\n")) break;
            if (source.type.equals("keyboard") && wordCharacter(source.value())) count++;
        }
        return count;
    }
    private static Source previousCharacter(List<Source> list, int index) {
        for (int i = index - 1; i >= 0; i--) if (eligible(list.get(i))) return list.get(i);
        return null;
    }
    private static boolean eligible(Source source) {
        return source.type.equals("keyboard") && !modifier(source.key()) && !source.key().equals("VK_BACK")
                && !source.value().isEmpty();
    }
    private static boolean modifier(String key) { return key.endsWith("SHIFT") || key.endsWith("CONTROL") || key.endsWith("MENU"); }
    private static boolean wordCharacter(String value) { return value.length() == 1 && (Character.isLetterOrDigit(value.charAt(0)) || "'`@_~".contains(value)); }
    private static String keyOutput(Source source) {
        StringBuilder prefix = new StringBuilder();
        for (String state : source.modifiers()) if (!state.equals(source.key()) && (!state.endsWith("SHIFT") || modifier(source.key()) || source.value().isEmpty() || List.of("VK_DOWN", "VK_UP", "VK_LEFT", "VK_RIGHT", "VK_BACK", "VK_RETURN", "VK_TAB", "VK_DELETE").contains(source.key()))) prefix.append(keyName(state)).append(" + ");
        String value = source.value(), output;
        if (value.equals(" ")) output = "SPACE";
        else if (value.equals("\t")) output = "TAB";
        else if (value.length() == 1 && !Character.isISOControl(value.charAt(0))) output = value;
        else output = keyName(source.key());
        return prefix + output;
    }
    private static String keyName(String name) { return name.equals("VK_LCONTROL") ? "LCTRL" : name.equals("VK_RCONTROL") ? "RCTRL" : name.replaceFirst("^VK_", ""); }
    static String decode(String value) {
        Matcher matcher = ESCAPE.matcher(value); StringBuffer result = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(matcher.group(1), 16))));
        matcher.appendTail(result); return result.toString().replace("\r\n", "\n").replace('\r', '\n');
    }
    private static long number(String value, long fallback) { return value == null || value.isEmpty() ? fallback : Long.parseLong(value); }
    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private static String printable(String value) { return value.replace('\n', '·').replace('\r', '·').replace('\t', '·'); }
    private static String clock(Long millis) { return millis == null ? "" : String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", millis / 3600000, millis / 60000 % 60, millis / 1000 % 60); }
}
