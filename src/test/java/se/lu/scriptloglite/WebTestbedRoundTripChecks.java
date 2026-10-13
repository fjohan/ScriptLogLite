package se.lu.scriptloglite;

import java.nio.file.*;
import java.util.*;
import java.io.StringWriter;

/** All observed web streams must survive conversion, including duplicate carets. */
final class WebTestbedRoundTripChecks {
    static void run() throws Exception {
        Path fixture = Path.of("testbed-wscr.txt");
        if (!Files.exists(fixture)) return;
        String text = Files.readString(fixture);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        Object expected = new Json(text).parse();
        ReplayLog original = ReplayLog.load(fixture, -1);
        compare(expected, original, "direct");
        compare(expected, AnalysisTestSupport.jsonRoundTrip(original), "JSON");
        compare(expected, AnalysisTestSupport.rawRoundTrip(original), "raw");
        Path directory = Files.createTempDirectory("sll-web-testbed-");
        try {
            Path idfx = directory.resolve("test.idfx");
            try (var writer = Files.newBufferedWriter(idfx)) { InputlogExporter.write(original.events, original.metadata, writer); }
            ReplayLog converted = ReplayLog.load(idfx, -1);
            compare(expected, converted, "labelled IDFX");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
        System.out.println("Web testbed: JSON/raw/labelled IDFX reproduce all original streams and timestamps exactly; every text state and reverse replay checked");
    }
    private static void compare(Object expected, ReplayLog log, String route) throws Exception {
        StringWriter writer = new StringWriter();
        WebScriptLogExporter.write(log, writer);
        if (!expected.equals(new Json(writer.toString()).parse())) throw new AssertionError("Web testbed stream mismatch: " + route);
        List<String> states = new ArrayList<>();
        ReplayCursor cursor = new ReplayCursor(log);
        for (int i = 0; i < log.events.size(); i++) if (log.events.get(i) instanceof EditEvent) {
            cursor.seek(i); states.add(cursor.state().text);
        }
        Map<?, ?> root = (Map<?, ?>) expected;
        List<String> snapshots = new ArrayList<>();
        ((Map<?, ?>) root.get("text_records")).entrySet().stream()
                .sorted(Comparator.comparingLong(e -> Long.parseLong(e.getKey().toString())))
                .forEach(e -> snapshots.add(e.getValue().toString()));
        if (!states.equals(snapshots)) throw new AssertionError("Web testbed text states: " + route);
        for (int i = log.events.size() - 1; i >= 0; i--) cursor.seek(i);
        if (!cursor.state().text.equals(log.initialText)) throw new AssertionError("Web testbed reverse replay: " + route);
    }
}
