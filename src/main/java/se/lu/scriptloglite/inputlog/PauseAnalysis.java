package se.lu.scriptloglite.inputlog;

import se.lu.scriptloglite.ReplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pause distributions from observed actions, independently of report/reference data. */
public final class PauseAnalysis {
    static final class Metric {
        final String section, label, value;
        Metric(String section, String label, String value) { this.section = section; this.label = label; this.value = value; }
        String key() { return section + "|" + label; }
    }
    static final class Observation {
        final String id, type; final long time, duration; final int location, interval;
        Observation(GeneralAnalysis.Source source, long time, long duration, int location, int interval) {
            this.id = source.id; this.type = source.type; this.time = time; this.duration = duration;
            this.location = location; this.interval = interval;
        }
    }
    final ReplayLog log;
    final boolean internal;
    final long pauseThreshold, burstThreshold, processMillis, intervalMillis;
    final int intervalCount;
    final List<Metric> metrics = new ArrayList<>();
    final List<Observation> observations = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    private final Map<Integer, Double> criticalValues = new java.util.HashMap<>();
    public static PauseAnalysis analyze(ReplayLog log, long pt, int fn) { return analyze(log, pt, fn, 2000); }
    public static PauseAnalysis analyze(ReplayLog log, long pt, int fn, long pb) {
        return new PauseAnalysis(log, InternalGeneralEvents.build(log), true, pt, fn, pb);
    }
    public static PauseAnalysis analyzeSource(ReplayLog log, long pt, int fn) { return analyzeSource(log, pt, fn, 2000); }
    public static PauseAnalysis analyzeSource(ReplayLog log, long pt, int fn, long pb) {
        ReplayLog imported = GeneralAnalysis.sourceHistory(log);
        PauseAnalysis result = new PauseAnalysis(imported, GeneralAnalysis.sourceRecords(log.metadata.get("inputlogGeneralEvents")), false, pt, fn, pb);
        if (imported.events.size() < log.events.size()) result.notes.add("Source mode excludes later native events; internal mode includes the full history.");
        return result;
    }
    private PauseAnalysis(ReplayLog log, List<GeneralAnalysis.Source> all, boolean internal, long pt, int fn, long pb) {
        if (pt < 0 || pb <= 0 || fn < 1 || fn > 10000) throw new IllegalArgumentException("PT must be nonnegative, FN between 1 and 10000, and P-burst threshold positive.");
        this.log = log; this.internal = internal; pauseThreshold = pt; intervalCount = fn; burstThreshold = pb;
        List<GeneralAnalysis.Source> actions = new ArrayList<>();
        for (GeneralAnalysis.Source s : all) if (List.of("keyboard", "mouse", "focus", "insert", "replacement", "scrollChange").contains(s.type)) actions.add(s);
        Map<GeneralAnalysis.Source, Long> order = new IdentityHashMap<>(); long anchor = 0;
        for (GeneralAnalysis.Source s : actions) { if (s.start() > 0) anchor = s.start(); order.put(s, anchor); }
        // Inputlog puts focus observations before simultaneous actions. Otherwise a
        // following event can count the same gap again after the late focus anchor.
        actions.sort(Comparator.<GeneralAnalysis.Source>comparingLong(order::get)
                .thenComparingInt(s -> !internal && s.type.equals("focus") ? -1 : 0));
        long first = actions.stream().filter(s -> s.start() > 0).mapToLong(GeneralAnalysis.Source::start).min().orElse(0);
        long last = actions.stream().filter(s -> s.start() > 0).mapToLong(s -> Math.max(s.start(), s.end())).max().orElse(first);
        long origin = first;
        processMillis = Math.max(0, last - origin);
        intervalMillis = processMillis / fn + (processMillis % fn == 0 ? 0 : 1);
        notes.add("PT is inclusive and measured in milliseconds. FN divides process time into a fixed number of equal-duration intervals (ceiling to the next millisecond). Zero gaps are not pauses, even at PT0.");
        notes.add("Pause timing follows Inputlog's start-to-start keyboard convention; after mouse/viewport actions it uses the previous action end. Key holds are therefore included in keyboard gaps, not subtracted.");
        notes.add("Location rules are the same independently implemented lexical rules as General Analysis, not Inputlog's configurable FSM. Counts describe the writing process, including deleted typing; the final product is shown separately.");
        notes.add("Combined boundaries collect an AFTER gap, intervening modifier gaps, and the following BEFORE gap. They are a separate view of the same time and are not added to the total pause time.");
        notes.add("P-bursts are sequences separated by gaps at the independent P-burst threshold. Spaces and paragraph keys count as typed characters; pasted text does not. Bursts are not filtered by PT.");
        if (internal) {
            notes.add("Calculated from typed events and reconstructed text, including all arrow repeats and actual edits. Initial delay before the first action and time after the last action cannot be inferred. Intervals use the arriving action's time; overlapping gaps clamp to zero. Small-sample statistics remain mathematically defined where possible.");
        } else {
            notes.add("Compatibility source mode uses the imported session only, includes unreleased arrows and retains Inputlog's delayed interval assignment and final-event timing fix. This can assign a boundary-crossing pause to the previous interval and replace the final gap with the closing mouse duration. Combined boundaries retain its modifier augmentation. Small samples follow its blank/zero conventions; all discrepancies remain visible in reference comparison.");
        }
        List<GeneralAnalysis.Source> nonfocus = new ArrayList<>();
        for (GeneralAnalysis.Source s : actions) if (!s.type.equals("focus")) nonfocus.add(s);
        Map<GeneralAnalysis.Source, Integer> locations = new IdentityHashMap<>();
        for (int i = 0; i < actions.size(); i++) {
            GeneralAnalysis.Source s = actions.get(i);
            locations.put(s, s.type.equals("keyboard") ? GeneralAnalysis.pauseLocation(s, actions, i)
                    : s.type.equals("mouse") || s.type.equals("scrollChange") ? 16 : 10);
        }
        if (!nonfocus.isEmpty()) {
            GeneralAnalysis.Source initial = nonfocus.get(0);
            if (locations.get(initial) != 4) locations.put(initial, 8);
            if (nonfocus.size() > 1 && initial.type.equals("mouse") && initial.win.getOrDefault("type", "").equals("movement") && locations.get(nonfocus.get(1)) != 4) locations.put(nonfocus.get(1), 8);
            if (!internal || nonfocus.size() > 1) locations.put(nonfocus.get(nonfocus.size() - 1), 9);
            if (locations.containsValue(6) && nonfocus.size() > 1) locations.put(nonfocus.get(nonfocus.size() - 2), 7);
            for (int i = nonfocus.size() - 1; i >= 0; i--) {
                GeneralAnalysis.Source s = nonfocus.get(i);
                if (s.type.equals("keyboard") && locations.get(s) != 12) {
                    if (List.of(1, 2, 3, 15).contains(locations.get(s))) locations.put(s, 5); break;
                }
            }
        }
        Map<Integer, List<Long>> perLocation = new LinkedHashMap<>(), combined = new LinkedHashMap<>();
        List<List<Long>> intervals = new ArrayList<>(); for (int i = 0; i < fn; i++) intervals.add(new ArrayList<>());
        for (int i = 1; i < GeneralAnalysis.LOCATIONS.length; i++) perLocation.put(i, new ArrayList<>());
        for (int i : new int[] {2, 4, 6}) combined.put(i, new ArrayList<>());
        List<Long> total = new ArrayList<>(), burstTimes = new ArrayList<>(), burstChars = new ArrayList<>();
        GeneralAnalysis.Source previous = null; long previousPoint = origin, currentPoint = origin, collector = 0, previousGap = 0;
        int previousLocation = 0, legacyInterval = 0;
        for (int i = 0; i < actions.size(); i++) {
            GeneralAnalysis.Source s = actions.get(i); int location = locations.get(s);
            long time = Math.max(0, order.get(s) - origin);
            long gap = 0;
            if (!s.type.equals("focus")) {
                if (previous != null) {
                    if (previous.type.equals("mouse") || previous.type.equals("scrollChange")) previousPoint = Math.max(previous.start(), previous.end());
                    else if (previous.start() > 0) previousPoint = previous.start();
                }
            }
            if (!s.type.equals("focus") && (internal || !List.of("insert", "replacement").contains(s.type))) {
                if (s.start() > 0) currentPoint = s.start();
                gap = internal ? Math.max(0, currentPoint - previousPoint) : Math.abs(currentPoint - previousPoint);
                if (!internal && i == actions.size() - 1 && previous != null && previous.start() > 0) gap = previous.type.equals("mouse") ? Math.max(0, s.end() - s.start()) : 0;
            }
            int interval = intervalMillis == 0 ? 0 : (int) Math.min(fn - 1L, time / intervalMillis);
            if (!internal) interval = legacyInterval;
            observations.add(new Observation(s, time, gap, location, interval));
            if (gap > 0 && gap >= pt) { total.add(gap); intervals.get(interval).add(gap); perLocation.get(location).add(gap); }
            long augmented = gap + (!internal && previousLocation == 12 && List.of(2, 3, 4, 5, 6, 7).contains(location) ? previousGap : 0);
            if (List.of(3, 5, 7).contains(location)) collector = augmented;
            else if (List.of(2, 4, 6).contains(location)) {
                if (collector > 0) {
                    long boundary = collector + augmented;
                    if (boundary >= pt) combined.get(location).add(boundary);
                }
                collector = 0;
            } else if (location == 12) collector += collector > 0 ? gap : 0;
            else collector = 0;
            if (s.type.equals("focus")) { previousPoint = s.start(); collector = 0; }
            previous = s; previousLocation = location; previousGap = gap;
            if (!internal && intervalMillis > 0 && s.start() > 0) legacyInterval = (int) Math.min(fn - 1L, Math.max(0, (s.start() - first - 1) / intervalMillis));
        }
        calculateBursts(actions, origin, last, pb, internal, burstTimes, burstChars);
        long pauseTime = total.stream().mapToLong(Long::longValue).sum();
        add("Overview", "Total Process Time", clock(processMillis)); add("Overview", "Total Pause Time", clock(pauseTime));
        add("Overview", "Total Active Writing Time", clock(Math.max(0, processMillis - pauseTime)));
        add("Overview", "Total Process Time (s)", seconds(processMillis)); add("Overview", "Total Pause Time (s)", seconds(pauseTime));
        add("Overview", "Total Active Writing Time (s)", seconds(Math.max(0, processMillis - pauseTime)));
        add("Overview", "Proportion of Pause Time", processMillis == 0 ? "" : decimal(100.0 * pauseTime / processMillis) + " %");
        distributions("General", total, "Total Number of Pauses");
        GeneralAnalysis.Source firstKey = actions.stream().filter(s -> s.type.equals("keyboard")).findFirst().orElse(null);
        add("General", "Id of the First Key Event", firstKey == null ? "" : internal ? firstKey.id : Integer.toString(actions.indexOf(firstKey)));
        add("General", "Start Time of the First Key Event (ms)", firstKey == null ? "" : Long.toString(Math.max(0, firstKey.start() - origin)));
        String bs = "P-Bursts at " + pb + " ms";
        add(bs, "Number of P-Bursts", burstTimes.size());
        add(bs, "Number of P-Bursts per min.", processMillis == 0 ? "" : decimal(burstTimes.size() * 60000.0 / processMillis));
        add(bs, "Mean Process Time P-Bursts (s)", seconds(internal ? mean(burstTimes) : Math.floor(mean(burstTimes))));
        add(bs, "Median Process Time P-Bursts (s)", seconds(median(burstTimes)));
        add(bs, "Standard Deviation P-Bursts (s)", seconds(sd(burstTimes, false)));
        add(bs, "Mean Typed In P-Bursts (chars)", decimal(mean(burstChars)));
        add(bs, "Median Typed In P-Bursts (chars)", compact(median(burstChars)));
        add(bs, "Standard Deviation P-Bursts (chars)", decimal(sd(burstChars, false)));
        String[] names = {"", "Within Words", "Before Words", "After Words", "Before Sentences", "After Sentences", "Before Paragraphs", "After Paragraphs", "INITIAL PAUSES", "END PAUSES", "CHANGE PAUSES", "REVISION PAUSES", "COMBINATION KEY PAUSES", "", "", "UNKNOWN & UNDETERMINED PAUSES", "Mouse / Viewport Pauses"};
        for (int n : new int[] {1, 2, 4, 6, 3, 5, 7, 8, 9, 10, 11, 12, 15}) distributions(names[n], perLocation.get(n), "Number of Pauses");
        // Mouse gaps participate in the total; Inputlog doesn't present their own location table.
        if (internal && !perLocation.get(16).isEmpty()) distributions(names[16], perLocation.get(16), "Number of Pauses");
        for (int n : new int[] {2, 4, 6}) distributions(n == 2 ? "Between Words" : n == 4 ? "Between Sentences" : "Between Paragraphs", combined.get(n), "Number of Pauses");
        for (int i = 0; i < fn; i++) {
            String section = "Interval #" + (i + 1); add(section, "Start Time", clock(i * intervalMillis));
            distributions(section, intervals.get(i), "Number of Pauses");
        }
    }
    private static void calculateBursts(List<GeneralAnalysis.Source> actions, long origin, long last, long threshold,
                                        boolean internal, List<Long> times, List<Long> characters) {
        GeneralAnalysis.Source previous = null; long start = -1, chars = 0, previousPoint = origin;
        boolean focus = false;
        for (GeneralAnalysis.Source s : actions) {
            if (s.type.equals("focus")) { focus = true; continue; }
            if (s.start() == 0 || (!internal && List.of("insert", "replacement").contains(s.type))) continue;
            if (start < 0) start = s.start();
            if (previous != null) {
                previousPoint = previous.type.equals("mouse") || previous.type.equals("scrollChange")
                        ? Math.max(previous.start(), previous.end()) : previous.start();
                long gap = focus ? 0 : internal ? Math.max(0, s.start() - previousPoint) : Math.abs(s.start() - previousPoint);
                if (gap >= threshold) {
                    times.add(Math.max(0, Math.max(previous.start(), previous.end()) - start)); characters.add(chars);
                    start = s.start(); chars = 0;
                }
            }
            if (s.type.equals("keyboard") && countable(s.value()) && !s.word.isEmpty()) chars++;
            previous = s; focus = false;
        }
        if (start >= 0) { times.add(Math.max(0, last - start)); characters.add(chars); }
    }
    private static boolean countable(String text) {
        if (text.length() != 1) return false; char c = text.charAt(0);
        int type = Character.getType(c);
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isLetterOrDigit(c)
                || type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER
                || type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }
    private void distributions(String section, List<Long> values, String countLabel) {
        int n = values.size(); add(section, countLabel, n);
        boolean show = internal ? n > 0 : n > 1;
        add(section, "Arithmetic Mean of Pauses (s)", show ? seconds(mean(values)) : "");
        add(section, "Median Pause Time (s)", show ? seconds(median(values)) : "");
        double lm = n == 0 ? 0 : values.stream().mapToDouble(Math::log).average().orElse(0);
        final double observedLogMean = lm;
        double variance = n < 2 ? 0 : values.stream().mapToDouble(v -> Math.pow(Math.log(v) - observedLogMean, 2)).sum() / (n - 1);
        double logSd = Math.sqrt(variance), margin = n < 2 ? Double.NaN : criticalValues.computeIfAbsent(n - 1, PauseAnalysis::student95) * logSd / Math.sqrt(n);
        // Source compatibility: Inputlog's log helper returns zeros for fewer than three observations.
        if (!internal && n < 3) { lm = 0; margin = 0; logSd = 0; }
        add(section, "Geometric Mean of Pauses (s)", show ? seconds(Math.exp(lm)) : "");
        add(section, "95% CI Log-Transformed - Low Boundary (s)", show && n > 1 ? seconds(Math.exp(lm - margin)) : "");
        add(section, "95% CI Log-Transformed - High Boundary (s)", show && n > 1 ? seconds(Math.exp(lm + margin)) : "");
        add(section, "Coefficient of Variation", show && n > 1 ? decimal(100 * Math.sqrt(Math.expm1(logSd * logSd))) + " %" : "");
        add(section, "Standard Deviation (s)", show ? (sd(values, !internal) == 0 ? "0" : seconds(sd(values, !internal))) : "");
    }
    private void add(String section, String label, Object value) { metrics.add(new Metric(section, label, value == null ? "" : value.toString())); }
    static double mean(List<Long> values) { return values.stream().mapToDouble(Long::doubleValue).average().orElse(0); }
    static double median(List<Long> values) {
        if (values.isEmpty()) return 0; List<Long> sorted = new ArrayList<>(values); sorted.sort(Long::compare);
        int m = sorted.size() / 2; return sorted.size() % 2 == 1 ? sorted.get(m) : sorted.get(m - 1) / 2.0 + sorted.get(m) / 2.0;
    }
    static double sd(List<Long> values, boolean compatibility) {
        if (values.size() < (compatibility ? 3 : 2)) return 0; double mean = mean(values);
        return Math.sqrt(values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0));
    }
    static String seconds(double ms) { return decimal(ms / 1000); }
    static String decimal(double v) { return Double.isFinite(v) ? String.format(java.util.Locale.ROOT, "%.3f", v) : ""; }
    static String compact(double v) { return v == Math.rint(v) ? Long.toString((long) v) : decimal(v); }
    static String clock(long ms) { return LinearAnalysisReport.clock(ms); }
    // Student-t 97.5th percentile, using a regularized incomplete beta CDF and bisection.
    static double student95(int df) {
        double low = 0, high = 128;
        for (int i = 0; i < 70; i++) { double t = (low + high) / 2; double tail = beta(df / (df + t * t), df / 2.0, .5) / 2;
            if (tail > .025) low = t; else high = t; }
        return (low + high) / 2;
    }
    private static double beta(double x, double a, double b) {
        if (x <= 0) return 0; if (x >= 1) return 1;
        double factor = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b) + a * Math.log(x) + b * Math.log1p(-x));
        return x < (a + 1) / (a + b + 2) ? factor * fraction(x, a, b) / a : 1 - factor * fraction(1 - x, b, a) / b;
    }
    private static double fraction(double x, double a, double b) {
        double c = 1, d = 1 - (a + b) * x / (a + 1); d = 1 / nonzero(d); double h = d;
        for (int m = 1; m <= 200; m++) {
            double aa = m * (b - m) * x / ((a + 2 * m - 1) * (a + 2 * m));
            d = 1 / nonzero(1 + aa * d); c = nonzero(1 + aa / c); h *= d * c;
            aa = -(a + m) * (a + b + m) * x / ((a + 2 * m) * (a + 2 * m + 1));
            d = 1 / nonzero(1 + aa * d); c = nonzero(1 + aa / c); double delta = d * c; h *= delta;
            if (Math.abs(delta - 1) < 1e-14) break;
        }
        return h;
    }
    private static double nonzero(double x) { return Math.abs(x) < 1e-300 ? 1e-300 : x; }
    private static double logGamma(double x) {
        double[] c = {676.5203681218851, -1259.1392167224028, 771.3234287776531, -176.6150291621406, 12.507343278686905, -.13857109526572012, 9.984369578019572e-6, 1.5056327351493116e-7};
        double z = x - 1, sum = .9999999999998099;
        for (int i = 0; i < c.length; i++) sum += c[i] / (z + i + 1);
        double t = z + 7.5; return .9189385332046727 + (z + .5) * Math.log(t) - t + Math.log(sum);
    }
}
