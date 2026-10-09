package se.lu.scriptloglite.inputlog;

import se.lu.scriptloglite.ReplayLog;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Chronological writing score; presentation never participates in reconstructing the document. */
public final class LinearAnalysis {
    static final class Mark {
        final String kind, value;
        final long time;
        Mark(String kind, String value, long time) { this.kind = kind; this.value = value; this.time = time; }
    }
    static final class Interval {
        final long index;
        final List<Mark> marks = new ArrayList<>();
        Interval(long index) { this.index = index; }
        String score() { StringBuilder out = new StringBuilder(); for (Mark mark : marks) out.append(mark.value); return out.toString(); }
    }
    final ReplayLog log;
    final boolean internal;
    final long pauseThreshold, intervalSeconds;
    final List<Interval> intervals = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    long duration;
    public static LinearAnalysis analyze(ReplayLog log, long pauseThreshold, long intervalSeconds) {
        return new LinearAnalysis(log, InternalGeneralEvents.build(log), pauseThreshold, intervalSeconds, true);
    }
    public static LinearAnalysis analyzeSource(ReplayLog log, long pauseThreshold, long intervalSeconds) {
        ReplayLog imported = GeneralAnalysis.sourceHistory(log);
        LinearAnalysis result = new LinearAnalysis(imported, GeneralAnalysis.sourceRecords(log.metadata.get("inputlogGeneralEvents")), pauseThreshold, intervalSeconds, false);
        if (imported.events.size() < log.events.size()) result.notes.add("Source mode describes the imported prefix only; native continuation is excluded.");
        return result;
    }
    private LinearAnalysis(ReplayLog log, List<GeneralAnalysis.Source> all, long pauseThreshold, long intervalSeconds, boolean internal) {
        if (pauseThreshold < 0) throw new IllegalArgumentException("PT must be nonnegative milliseconds");
        if (intervalSeconds < 1 || intervalSeconds > Long.MAX_VALUE / 1000) throw new IllegalArgumentException("FL must be positive seconds within the supported range");
        this.log = log; this.pauseThreshold = pauseThreshold; this.intervalSeconds = intervalSeconds; this.internal = internal;
        List<GeneralAnalysis.Source> actions = new ArrayList<>();
        for (GeneralAnalysis.Source source : all) if (List.of("keyboard", "mouse", "focus", "insert", "replacement", "scrollChange").contains(source.type)) actions.add(source);
        java.util.Map<GeneralAnalysis.Source, Long> order = new java.util.IdentityHashMap<>();
        long orderingTime = 0;
        for (GeneralAnalysis.Source source : actions) { if (source.start() > 0) orderingTime = source.start(); order.put(source, orderingTime); }
        actions.sort(java.util.Comparator.comparingLong(order::get));
        if (!internal) {
            List<GeneralAnalysis.Source> compact = new ArrayList<>();
            GeneralAnalysis.Source lastObservation = null;
            for (GeneralAnalysis.Source source : actions) {
                boolean repeated = source.type.equals("keyboard") && source.end() == 0
                        && List.of("VK_UP", "VK_DOWN", "VK_LEFT", "VK_RIGHT").contains(source.key())
                        && lastObservation != null && lastObservation.type.equals("keyboard") && lastObservation.end() == 0
                        && lastObservation.key().equals(source.key());
                if (!repeated) compact.add(source);
                lastObservation = source;
            }
            actions = compact;
        }
        long origin = actions.stream().filter(s -> s.start() > 0).mapToLong(GeneralAnalysis.Source::start).min().orElse(0);
        long width = intervalSeconds * 1000;
        TreeMap<Long, Interval> scores = new TreeMap<>();
        GeneralAnalysis.Source previous = null;
        long anchor = origin, time = 0, previousInterval = 0;
        for (GeneralAnalysis.Source source : actions) {
            if (source.start() > 0) time = Math.max(0, source.start() - origin);
            duration = Math.max(duration, Math.max(time, source.end() > 0 ? source.end() - origin : time));
            long index = time / width;
            Interval interval = scores.computeIfAbsent(index, Interval::new);
            boolean pauseAction = List.of("keyboard", "mouse", "scrollChange").contains(source.type)
                    || internal && List.of("insert", "replacement").contains(source.type);
            if (pauseAction) {
                if (previous != null && previous.start() > 0) anchor = previous.type.equals("mouse") || previous.type.equals("scrollChange")
                        ? (previous.end() > 0 ? previous.end() : previous.start()) : previous.start();
                long gap = previous == null ? (source.type.equals("mouse") ? Math.max(0, source.end() - source.start()) : 0)
                        : internal ? Math.max(0, source.start() - anchor) : Math.abs(source.start() - anchor);
                if (gap > 0 && gap >= pauseThreshold) {
                    Interval destination = internal ? interval : scores.computeIfAbsent(previousInterval, Interval::new);
                    destination.marks.add(new Mark("pause", "{" + gap + "}", time));
                }
            }
            switch (source.type) {
                case "keyboard":
                    // Preserve missing-repeat observations internally. Legacy source rendering omits unreleased arrows.
                    if (!internal && source.end() == 0 && List.of("VK_UP", "VK_DOWN", "VK_LEFT", "VK_RIGHT").contains(source.key())) break;
                    String value = source.value();
                    if (value.equals(" ") && commandPrefix(source).isEmpty()) interval.marks.add(new Mark("text", "·", time));
                    else if (value.length() == 1 && !Character.isISOControl(value.charAt(0)) && commandPrefix(source).isEmpty())
                        interval.marks.add(new Mark("text", escapeLiteral(value), time));
                    else interval.marks.add(new Mark("command", "[" + commandPrefix(source) + keyName(source.key()) + "]", time));
                    break;
                case "mouse":
                    String type = source.win.getOrDefault("type", "");
                    String mouse = type.equals("movement") ? "Movement" : type.equals("click") ? source.win.getOrDefault("button", "") + " Click" : "Scroll";
                    interval.marks.add(new Mark("command", "[" + mouse + "]", time)); break;
                case "scrollChange": interval.marks.add(new Mark("command", "[Viewport " + source.win.get("x") + ":" + source.win.get("y") + "]", time)); break;
                case "insert": interval.marks.add(new Mark("edit", "<" + visible(GeneralAnalysis.decode(source.word.getOrDefault("before", ""))) + ">", time)); break;
                case "replacement":
                    if (internal) {
                        String inserted = GeneralAnalysis.decode(source.word.getOrDefault("newtext", ""));
                        interval.marks.add(new Mark("edit", "[" + (inserted.isEmpty() ? "REMOVE " : "REPLACE ") + source.word.get("start") + ":" + source.word.get("end")
                                + (inserted.isEmpty() ? "" : "→" + visible(inserted)) + "]", time));
                    }
                    // Inputlog's fixed-length HTML omits its replacement-event output.
                    break;
                default: break; // Focus affects pause timing without cluttering the score.
            }
            previous = source; previousInterval = index;
        }
        intervals.addAll(scores.values());
        notes.add("PT is milliseconds; a positive start-to-start gap at or above PT is shown as {milliseconds}. After mouse/viewport actions the preceding end is used; focus changes update the timing anchor. The first mouse duration is shown using Inputlog's initial-action convention; it is not idle time before recording. Overlaps produce zero idle gap internally.");
        notes.add("FL is seconds, anchored to the first timed action. Rows use elapsed-time labels; actions belong to [start,end) intervals. Sparse empty intervals are summarized as gaps, without allocating a row for every second of a long recording.");
        notes.add("Plain text is observed typing, not final text. Spaces display as ·; control/navigation keys as [KEY]; bulk inserts as <text>. Native unassociated replacements/deletions show their actual ranges. Literal notation delimiters are escaped. Key releases, caret markers and duplicate associated typing edits are omitted.");
        notes.add(internal ? "Calculated from internal typed events and edits, with retained mouse/focus context. Pause markers belong to the arriving action's interval. Coordinate-less external source keys are excluded from the document score. Missing releases are not invented."
                : "Source compatibility mode uses retained Inputlog observations, including external keys; replacements are omitted as in the legacy HTML, unreleased arrow runs retain their first timing observation with hidden key output, and boundary-crossing pauses stay with the preceding interval. Source clocks are displayed as elapsed time, not the reference's Windows-clock labels.");
    }
    private static String commandPrefix(GeneralAnalysis.Source source) {
        StringBuilder out = new StringBuilder();
        for (String modifier : source.modifiers()) {
            if (modifier.equals(source.key())) continue;
            boolean shift = modifier.endsWith("SHIFT");
            if (!shift || source.value().isEmpty() || List.of("VK_RETURN", "VK_BACK", "VK_DELETE", "VK_TAB").contains(source.key()))
                out.append(keyName(modifier)).append(" + ");
        }
        return out.toString();
    }
    private static String keyName(String key) {
        if (key.equals("VK_LCONTROL")) return "LCTRL";
        if (key.equals("VK_RCONTROL")) return "RCTRL";
        if (key.equals("VK_CONTROL")) return "CTRL";
        if (key.equals("VK_LMENU")) return "LALT";
        if (key.equals("VK_RMENU")) return "RALT";
        return key.replaceFirst("^VK_", "");
    }
    private static String escapeLiteral(String value) {
        StringBuilder out = new StringBuilder(); for (char ch : value.toCharArray()) {
            if ("[]{}<>\\·".indexOf(ch) >= 0) out.append('\\'); out.append(ch);
        }
        return out.toString();
    }
    private static String visible(String value) { return escapeLiteral(value).replace('\n', '·').replace('\r', '·').replace('\t', '·'); }
}
