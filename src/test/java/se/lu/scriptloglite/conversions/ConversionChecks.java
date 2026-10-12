package se.lu.scriptloglite.conversions;

import se.lu.scriptloglite.ReplayLog;
import se.lu.scriptloglite.DirectoryHistory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Comparator;
import javax.swing.SwingUtilities;

public final class ConversionChecks {
    public static void run() throws Exception {
        Path root = Files.createTempDirectory("sll-conversions-");
        try {
            Path nativeInput = Path.of("exp_subj_json_1.json");
            if (Files.exists(nativeInput)) {
                String text = ReplayLog.load(nativeInput, -1).finalText;
                List<ConversionFormat> formats = List.of(ConversionFormat.JSON, ConversionFormat.RAW, ConversionFormat.IDFX, ConversionFormat.WEBSCRIPTLOG);
                for (ConversionFormat source : formats) {
                    Path from = root.resolve("source" + source.suffix);
                    Conversions.Result seed = Conversions.run(request(nativeInput, from, ConversionFormat.AUTO, source, false, false));
                    check(seed.converted == 1 && seed.failed == 0, "seed " + source);
                    for (ConversionFormat target : formats) {
                        Path output = root.resolve("pair-" + source.name() + "-" + target.name() + target.suffix);
                        Conversions.Result result = Conversions.run(request(from, output, source, target, false, false));
                        check(result.converted == 1 && result.failed == 0, "format pair " + source + " → " + target);
                        check(ReplayLog.load(output, -1).finalText.equals(text), "reconstructed text for " + source + " → " + target);
                    }
                }
                Path inputs = root.resolve("inputs"); Files.createDirectories(inputs.resolve("nested"));
                Files.copy(nativeInput, inputs.resolve("first.json")); Files.copy(nativeInput, inputs.resolve("nested/second.json"));
                Files.writeString(inputs.resolve("bad.json"), "[broken"); Files.writeString(inputs.resolve("ignored.md"), "not a log");
                Path outputs = inputs.resolve("outputs");
                var batch = request(inputs, outputs, ConversionFormat.SCRIPTLOGLITE, ConversionFormat.WEBSCRIPTLOG, true, false);
                var plan = Conversions.plan(batch); check(plan.size() == 3 && !Files.exists(outputs), "planning doesn't write");
                var result = Conversions.run(batch); check(result.converted == 2 && result.failed == 1, "batch continues past malformed file");
                check(Files.exists(outputs.resolve("nested/second_webscriptlog.txt")), "relative structure preserved");
                var again = Conversions.run(batch); check(again.converted == 0 && again.skipped == 2 && again.failed == 1, "outputs excluded and existing targets skipped");
                var overwrite = Conversions.run(request(inputs, outputs, ConversionFormat.JSON, ConversionFormat.WEBSCRIPTLOG, true, true));
                check(overwrite.converted == 2 && overwrite.failed == 1, "explicit overwrite");
                Path flat = root.resolve("flat"); var top = Conversions.run(request(inputs, flat, ConversionFormat.JSON, ConversionFormat.RAW, false, false));
                check(top.converted == 1 && top.failed == 1 && !Files.exists(flat.resolve("nested")), "nonrecursive default");
                Path existingDir = root.resolve("single-directory"); Files.createDirectories(existingDir);
                check(Conversions.run(request(nativeInput, existingDir, ConversionFormat.AUTO, ConversionFormat.RAW, false, false)).converted == 1, "individual file to existing directory");
                Path collision = root.resolve("collisions"); Files.createDirectories(collision);
                Files.copy(root.resolve("source.json"), collision.resolve("same.json")); Files.copy(root.resolve("source_raw.txt"), collision.resolve("same.txt"));
                var conflicting = Conversions.run(request(collision, root.resolve("collision-output"), ConversionFormat.SCRIPTLOGLITE, ConversionFormat.IDFX, false, true));
                check(conflicting.failed == 2 && conflicting.converted == 0, "duplicate stems do not overwrite each other");
                var filtered = Conversions.run(request(collision, root.resolve("filtered"), ConversionFormat.JSON, ConversionFormat.IDFX, false, false));
                check(filtered.converted == 1 && filtered.skipped == 1, "source format filter uses contents");
                String before = Files.readString(nativeInput);
                rejects(() -> Conversions.run(request(nativeInput, nativeInput, ConversionFormat.AUTO, ConversionFormat.RAW, false, true)), "same input/output rejected");
                check(Files.readString(nativeInput).equals(before), "input unchanged");
                Path alias = root.resolve("alias.json"); Files.createSymbolicLink(alias, nativeInput.toAbsolutePath());
                rejects(() -> Conversions.run(request(nativeInput, alias, ConversionFormat.AUTO, ConversionFormat.JSON, false, true)), "symlink alias rejected");
                rejects(() -> Conversions.plan(request(inputs, inputs, ConversionFormat.AUTO, ConversionFormat.JSON, true, true)), "same directories rejected");
                Path dry = root.resolve("dry-output");
                ConversionCli.run(new String[] {"--convert", nativeInput.toString(), dry.toString(), "--to", "json", "--dry-run"});
                check(!Files.exists(dry), "CLI dry run doesn't write");
                Path direct = root.resolve("cli.json");
                ConversionCli.run(new String[] {"--convert", root.resolve("source.idfx").toString(), direct.toString(), "--to", "json"});
                check(ReplayLog.load(direct, -1).finalText.equals(text), "CLI uses shared engine");
            }
            Path mobile = Path.of("wslog_QQQQQQ_09-10-2026_19_54_58.txt");
            if (Files.exists(mobile)) {
                Path xml = root.resolve("mobile.idfx"), back = root.resolve("mobile.json");
                check(Conversions.run(request(mobile, xml, ConversionFormat.WEBSCRIPTLOG, ConversionFormat.IDFX, false, false)).converted == 1, "mobile web to IDFX");
                check(Conversions.run(request(xml, back, ConversionFormat.IDFX, ConversionFormat.JSON, false, false)).converted == 1, "mobile IDFX to JSON");
                check(ReplayLog.load(back, -1).finalText.equals(ReplayLog.load(mobile, -1).finalText), "mobile full conversion round trip");
            }
            Path unknown = root.resolve("unknown.json");
            Files.writeString(unknown, "{\"header_records\":{\"starttime\":0,\"endtime\":10},\"text_records\":{},\"key_records\":{\"1\":{\"key\":\"a\"}}}");
            var unsupported = Conversions.run(request(unknown, root.resolve("unknown-output.json"), ConversionFormat.WEBSCRIPTLOG, ConversionFormat.JSON, false, false));
            check(unsupported.failed == 1 && !Files.exists(root.resolve("unknown-output.json")), "unknown structured web keys fail explicitly without partial output");
            SwingUtilities.invokeAndWait(() -> check(new ConversionPanel(new DirectoryHistory(root.resolve("preferences.properties"))).getComponentCount() == 2, "UI builds headlessly"));
        } finally { try (var files = Files.walk(root)) { for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(file); } }
        System.out.println("Conversions: all 16 format pairs, mobile IDFX round trip, batch errors/filtering/recursion, collisions, overwrite protection, dry-run and UI checked");
    }
    private static Conversions.Request request(Path a, Path b, ConversionFormat from, ConversionFormat to, boolean recursive, boolean overwrite) {
        return new Conversions.Request(a, b, from, to, recursive, overwrite, true, true);
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked call, String label) throws Exception { try { call.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError(label); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError("Conversions: " + label); }
}
