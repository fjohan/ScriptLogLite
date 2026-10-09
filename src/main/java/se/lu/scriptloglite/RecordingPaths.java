package se.lu.scriptloglite;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;

/** Creates the working directory and allocates unique recording folders and filenames. */
final class RecordingPaths {
    static Path workingRoot = Path.of(System.getProperty("user.home"), "ScriptLogLiteWD");

    static Path workingDirectory() {
        try { return Files.createDirectories(workingRoot).toAbsolutePath(); }
        catch (IOException exception) { throw new java.io.UncheckedIOException("Cannot create working directory " + workingRoot, exception); }
    }

    static Path allocateRecording(RecordingVariables variables, java.time.LocalDate date) throws IOException {
        Path group = Files.createDirectories(workingDirectory().resolve(variables.prefix()));
        int index = 1;
        Pattern numbered = Pattern.compile(Pattern.quote(date.toString()) + "_(\\d+)");
        try (java.util.stream.Stream<Path> children = Files.list(group)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                Matcher matcher = numbered.matcher(child.getFileName().toString());
                if (matcher.matches()) index = Math.max(index, Math.addExact(Integer.parseInt(matcher.group(1)), 1));
            }
        }
        while (true) {
            Path directory = group.resolve(date + "_" + index);
            try {
                Files.createDirectory(directory); // Reserve atomically, including across application processes.
                return nextLogFile(directory, variables.prefix());
            } catch (java.nio.file.FileAlreadyExistsException exception) { index = Math.addExact(index, 1); }
        }
    }

    static Path nextLogFile(Path directory, String prefix) {
        int index = 1;
        Path file = directory.resolve(prefix + "_sll_" + index + ".json");
        while (Files.exists(file) || Files.exists(LogFormat.RAW.path(file)) || Files.exists(LogFormat.IDFX.path(file))) {
            index = Math.addExact(index, 1);
            file = directory.resolve(prefix + "_sll_" + index + ".json");
        }
        return file;
    }
}
