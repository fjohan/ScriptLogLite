import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Source-launchable build helper for the dependency-free shell launcher. */
public final class CompileSources {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK with the Java compiler is required.");
        Path output = Path.of("target", "launcher-classes");
        Files.createDirectories(output);
        try (var paths = Files.walk(output)) {
            for (Path path : (Iterable<Path>) paths.filter(p -> p.toString().endsWith(".class"))::iterator) {
                Files.delete(path);
            }
        }
        List<String> options = new ArrayList<>();
        if (Files.isRegularFile(Path.of(System.getProperty("java.home"), "lib", "ct.sym"))) {
            options.addAll(List.of("--release", "11"));
        } else {
            System.err.println("Compiler compatibility data missing; checking Java 11 syntax/class files only.");
            options.addAll(List.of("-source", "11", "-target", "11", "-Xlint:-options"));
        }
        options.addAll(List.of("-encoding", "UTF-8", "-d", output.toString()));
        try (var paths = Files.walk(Path.of("src", "main", "java"))) {
            paths.filter(p -> p.toString().endsWith(".java")).sorted(Comparator.naturalOrder())
                    .forEach(p -> options.add(p.toString()));
        }
        if (args.length == 1 && args[0].equals("--tests")) {
            options.add("src/test/java/se/lu/scriptloglite/ScriptLogLiteChecks.java");
            options.add("src/test/java/se/lu/scriptloglite/InputlogChecks.java");
            options.add("src/test/java/se/lu/scriptloglite/GeneralAnalysisChecks.java");
            options.add("src/test/java/se/lu/scriptloglite/SummaryAnalysisChecks.java");
        }
        int result = compiler.run(null, System.out, System.err, options.toArray(new String[0]));
        if (result != 0) System.exit(result);
    }
}
