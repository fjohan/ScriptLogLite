package se.lu.scriptloglite.conversions;

import se.lu.scriptloglite.ConversionCodec;
import se.lu.scriptloglite.ReplayLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.Writer;

/** Shared file/batch conversion engine. Each conversion imports to typed events first. */
public final class Conversions {
    private Conversions() { }
    public static final class Request {
        public final Path input, output;
        public final ConversionFormat from, to;
        public final boolean recursive, overwrite, replace, labels;
        public Request(Path input, Path output, ConversionFormat from, ConversionFormat to,
                       boolean recursive, boolean overwrite, boolean replace, boolean labels) {
            this.input = input.toAbsolutePath().normalize(); this.output = output.toAbsolutePath().normalize();
            this.from = java.util.Objects.requireNonNull(from); this.to = java.util.Objects.requireNonNull(to);
            this.recursive = recursive; this.overwrite = overwrite; this.replace = replace; this.labels = labels;
            if (!to.target()) throw new IllegalArgumentException("Choose json, raw, idfx or webscriptlog as output");
        }
    }
    public static final class Entry {
        public final Path input, output;
        public final ConversionFormat format;
        public final String problem;
        Entry(Path input, Path output, ConversionFormat format, String problem) { this.input = input; this.output = output; this.format = format; this.problem = problem; }
        public String toString() { return input + " → " + output + (problem == null ? "" : " [" + problem + "]"); }
    }
    public static final class Result {
        public int converted, skipped, failed;
        public final List<String> lines = new ArrayList<>();
        public String summary() { return converted + " converted, " + skipped + " skipped, " + failed + " failed"; }
    }
    public static ConversionFormat detect(Path path) throws Exception {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String start = text.stripLeading();
        if (start.startsWith("<")) return ConversionFormat.IDFX;
        if (start.startsWith("[")) return ConversionFormat.JSON;
        if (start.startsWith("{") && text.contains("\"text_records\"") && text.contains("\"header_records\"")) return ConversionFormat.WEBSCRIPTLOG;
        if (text.lines().anyMatch(line -> line.equals("#")) || text.lines().anyMatch(line -> line.matches("\\S+ session .*"))) return ConversionFormat.RAW;
        throw new IllegalArgumentException("Unrecognized log format");
    }
    public static List<Entry> plan(Request request) throws Exception {
        if (!Files.exists(request.input)) throw new IllegalArgumentException("Input does not exist: " + request.input);
        boolean batch = Files.isDirectory(request.input);
        if (request.input.equals(request.output) || Files.exists(request.output) && Files.isSameFile(request.input, request.output)) throw new IllegalArgumentException("Choose output separate from input");
        if (batch && Files.exists(request.output) && !Files.isDirectory(request.output)) throw new IllegalArgumentException("Batch output must be a directory");
        List<Path> inputs = new ArrayList<>();
        if (batch) {
            try (var files = Files.walk(request.input, request.recursive ? Integer.MAX_VALUE : 1)) {
                files.filter(Files::isRegularFile).filter(p -> !request.output.startsWith(request.input) || !p.toAbsolutePath().normalize().startsWith(request.output))
                        .filter(Conversions::candidate).sorted().forEach(inputs::add);
            }
        } else inputs.add(request.input);
        Set<Path> sourcePaths = new HashSet<>(); for (Path p : inputs) sourcePaths.add(p.toRealPath());
        List<Entry> entries = new ArrayList<>();
        for (Path source : inputs) {
            Path destination = request.output;
            if (batch || Files.isDirectory(destination)) {
                Path relative = batch ? request.input.relativize(source) : source.getFileName();
                String name = relative.getFileName().toString(); int dot = name.lastIndexOf('.'); if (dot > 0) name = name.substring(0, dot);
                Path parent = relative.getParent(); destination = request.output.resolve(parent == null ? Path.of(name + request.to.suffix) : parent.resolve(name + request.to.suffix));
            }
            ConversionFormat format = null; String problem = null;
            try {
                format = detect(source);
                if (!request.from.accepts(format)) problem = "skip: source format is " + format;
                else if (sourcePaths.contains(Files.exists(destination) ? destination.toRealPath() : destination.toAbsolutePath().normalize())) problem = "error: output would overwrite an input";
                else if (Files.exists(destination) && !request.overwrite) problem = "skip: output exists";
                else if (Files.isDirectory(destination)) problem = "error: output is a directory";
            } catch (Exception exception) { problem = "error: " + exception.getMessage(); }
            entries.add(new Entry(source, destination, format, problem));
        }
        Map<Path, Integer> counts = new HashMap<>();
        for (Entry entry : entries) if (entry.problem == null) counts.merge(entry.output.toAbsolutePath().normalize(), 1, Integer::sum);
        List<Entry> checked = new ArrayList<>();
        for (Entry e : entries) checked.add(e.problem == null && counts.get(e.output.toAbsolutePath().normalize()) > 1
                ? new Entry(e.input, e.output, e.format, "error: multiple inputs map to this output") : e);
        return List.copyOf(checked);
    }
    private static boolean candidate(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".json") || name.endsWith(".txt") || name.endsWith(".idfx") || name.endsWith(".log");
    }
    public static Result run(Request request) throws Exception { return run(request, plan(request)); }
    private static Result run(Request request, List<Entry> entries) {
        Result result = new Result();
        for (Entry entry : entries) {
            if (entry.problem != null) {
                if (entry.problem.startsWith("skip:")) result.skipped++; else result.failed++;
                result.lines.add(entry.toString()); continue;
            }
            Path temporary = null;
            try {
                // Recheck actual content and target just before writing; plan is not permission to overwrite.
                if (!request.from.accepts(detect(entry.input))) throw new IllegalArgumentException("Source format changed since planning");
                if (Files.exists(entry.output) && Files.isSameFile(entry.input, entry.output)) throw new IllegalArgumentException("Output aliases input");
                if (!request.overwrite && Files.exists(entry.output)) { result.skipped++; result.lines.add(entry.input + " [skip: output exists]"); continue; }
                ReplayLog log = ReplayLog.load(entry.input, -1);
                Files.createDirectories(entry.output.getParent());
                temporary = Files.createTempFile(entry.output.getParent(), ".conversion-", ".tmp");
                List<String> notes;
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { notes = ConversionCodec.write(log, request.to, writer, request.replace, request.labels); }
                // No-replace moves intentionally avoid ATOMIC_MOVE, whose existing-target behavior is implementation dependent.
                if (!request.overwrite) Files.move(temporary, entry.output);
                else {
                    try { Files.move(temporary, entry.output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, entry.output, StandardCopyOption.REPLACE_EXISTING); }
                }
                result.converted++; result.lines.add("OK " + entry);
                for (String note : notes) result.lines.add("  " + note);
            } catch (Exception exception) { result.failed++; result.lines.add("FAILED " + entry.input + ": " + exception.getMessage()); }
            finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { } }
        }
        return result;
    }
}
