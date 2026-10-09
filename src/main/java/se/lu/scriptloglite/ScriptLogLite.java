package se.lu.scriptloglite;

import se.lu.scriptloglite.inputlog.GeneralAnalysis;
import se.lu.scriptloglite.inputlog.GeneralAnalysisReport;
import se.lu.scriptloglite.inputlog.SummaryAnalysis;
import se.lu.scriptloglite.inputlog.SummaryAnalysisReport;
import se.lu.scriptloglite.inputlog.WordPausesAnalysis;
import se.lu.scriptloglite.inputlog.RevisionAnalysisReport;
import se.lu.scriptloglite.inputlog.PauseAnalysis;
import se.lu.scriptloglite.inputlog.PauseAnalysisReport;
import se.lu.scriptloglite.inputlog.LinearAnalysis;
import se.lu.scriptloglite.inputlog.LinearAnalysisReport;

import java.nio.file.Path;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.AbstractDocument;
import static se.lu.scriptloglite.EditorSupport.createTextArea;
import static se.lu.scriptloglite.EditorSupport.edit;
import static se.lu.scriptloglite.RecordingPaths.workingDirectory;
import static se.lu.scriptloglite.RecordingPaths.allocateRecording;
import static se.lu.scriptloglite.ThemeManager.installTheme;

public final class ScriptLogLite {
    public static void main(String[] args) throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            installTheme(Theme.NIMBUS);
        }
        if (args.length > 0 && java.util.List.of("--general-analysis", "--summary-analysis", "--s-notation", "--word-pauses", "--linear-analysis", "--pause-analysis").contains(args[0])) {
            boolean revisions = args[0].equals("--s-notation") || args[0].equals("--word-pauses");
            boolean pause = args[0].equals("--pause-analysis");
            boolean linear = args[0].equals("--linear-analysis");
            if (args.length < 3) throw new IllegalArgumentException("Usage: " + args[0] + " INPUT_LOG OUTPUT_HTML " + (linear ? "[--pt MILLISECONDS] [--fl SECONDS] " : pause ? "[--pt MILLISECONDS] [--fn NUMBER] [--pb MILLISECONDS] " : "") + (revisions ? "" : "[--source-events] ") + "[--compare INPUTLOG_HTML]");
            boolean sourceMode = false; Path reference = null;
            long pauseThreshold = 200, intervalSeconds = 60, burstThreshold = 2000; int intervalCount = 5;
            for (int i = 3; i < args.length; i++) {
                if (args[i].equals("--source-events")) sourceMode = true;
                else if (args[i].equals("--compare") && i + 1 < args.length) reference = Path.of(args[++i]);
                else if ((linear || pause) && args[i].equals("--pt") && i + 1 < args.length) pauseThreshold = Long.parseLong(args[++i]);
                else if (pause && args[i].equals("--fn") && i + 1 < args.length) intervalCount = Integer.parseInt(args[++i]);
                else if (pause && args[i].equals("--pb") && i + 1 < args.length) burstThreshold = Long.parseLong(args[++i]);
                else if (linear && args[i].equals("--fl") && i + 1 < args.length) intervalSeconds = Long.parseLong(args[++i]);
                else throw new IllegalArgumentException("Unknown or incomplete analysis option: " + args[i]);
            }
            if (revisions && sourceMode) throw new IllegalArgumentException("S-Notation and Word Pauses use internal edits; --source-events is not supported.");
            Path input = Path.of(args[1]), output = Path.of(args[2]);
            if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())
                    || reference != null && reference.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("Choose output separate from input/reference");
            }
            ReplayLog history = ReplayLog.load(input, -1);
            if (pause) {
                PauseAnalysis analysis = sourceMode ? PauseAnalysis.analyzeSource(history, pauseThreshold, intervalCount, burstThreshold) : PauseAnalysis.analyze(history, pauseThreshold, intervalCount, burstThreshold);
                PauseAnalysisReport.save(analysis, input.getFileName().toString(), output, reference); return;
            }
            if (linear) {
                LinearAnalysis analysis = sourceMode ? LinearAnalysis.analyzeSource(history, pauseThreshold, intervalSeconds) : LinearAnalysis.analyze(history, pauseThreshold, intervalSeconds);
                LinearAnalysisReport.save(analysis, input.getFileName().toString(), output, reference); return;
            }
            if (revisions) {
                RevisionAnalysisReport.save(WordPausesAnalysis.analyze(history), input.getFileName().toString(), output, reference, args[0].equals("--word-pauses"));
                return;
            }
            if (args[0].equals("--summary-analysis")) {
                SummaryAnalysis analysis = sourceMode ? SummaryAnalysis.analyzeSource(history) : SummaryAnalysis.analyze(history);
                SummaryAnalysisReport.save(analysis, input.getFileName().toString(), output, reference);
                return;
            }
            GeneralAnalysis analysis = sourceMode ? GeneralAnalysis.analyzeSource(history) : GeneralAnalysis.analyze(history);
            GeneralAnalysisReport.save(analysis, input.getFileName().toString(), output, reference);
            return;
        }
        if (args.length > 0 && args[0].equals("--export-webscriptlog")) {
            if (args.length != 3) throw new IllegalArgumentException("Usage: --export-webscriptlog INPUT_LOG OUTPUT_TXT");
            Path input = Path.of(args[1]), output = Path.of(args[2]);
            if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())
                    || java.nio.file.Files.exists(output) && java.nio.file.Files.isSameFile(input, output)) {
                throw new IllegalArgumentException("Choose an export separate from the input log");
            }
            System.out.println(WebScriptLogExporter.save(ReplayLog.load(input, -1), output).description()); return;
        }
        if (args.length > 0 && args[0].equals("--export-idfx")) {
            if (args.length != 3 && !(args.length == 4 && args[3].equals("--no-lite-labels"))) {
                throw new IllegalArgumentException("Usage: --export-idfx INPUT_LOG OUTPUT_IDFX [--no-lite-labels]");
            }
            Path input = Path.of(args[1]);
            Path output = LogFormat.IDFX.path(Path.of(args[2]));
            if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("Choose an output separate from the input");
            }
            ReplayLog log = ReplayLog.load(input, -1);
            new SaveSnapshot(log.events, log.metadata, 0, java.util.EnumSet.of(LogFormat.IDFX), args.length == 3).save(output);
            return;
        }
        if (!(args.length == 1 && args[0].equals("--self-test"))) workingDirectory();
        if (args.length > 0 && args[0].equals("--replay")) {
            if (args.length < 2 || args.length > 3) {
                throw new IllegalArgumentException("Usage: --replay LOG_FILE [SESSION_NUMBER]");
            }
            ReplayLog replay = ReplayLog.load(Path.of(args[1]),
                    args.length == 3 ? Integer.parseInt(args[2]) : -1);
            SwingUtilities.invokeLater(() -> {
                TabbedApplication app = new TabbedApplication();
                app.show();
                app.addReplay(replay, Path.of(args[1]).getFileName().toString());
            });
            return;
        }
        if (args.length == 1 && args[0].equals("--self-test")) {
                throw new IllegalArgumentException("Use ./run.sh --self-test or mvn test to run checks.");
        }
        ReplayLog opened = null;
        if (args.length > 0 && args[0].equals("--open")) {
            if (args.length < 2 || args.length > 3) {
                throw new IllegalArgumentException("Usage: --open LOG_FILE [SESSION_NUMBER]");
            }
            opened = ReplayLog.load(Path.of(args[1]),
                    args.length == 3 ? Integer.parseInt(args[2]) : -1);
        }
        final ReplayLog initialLog = opened;
        if (args.length == 1 && args[0].equals("--demo")) {
            LoggingFilter filter = new LoggingFilter();
            filter.session.automaticPath = allocateRecording(new RecordingVariables("exp", "_", "subj"), java.time.LocalDate.now());
            filter.startSession("");
            SwingUtilities.invokeAndWait(() -> edit(() -> {
                JTextArea text = createTextArea(filter);
                AbstractDocument document = (AbstractDocument) text.getDocument();
                document.insertString(0, "Hello", null);
                document.replace(0, 5, "World", null);
                text.setCaretPosition(1);
                text.moveCaretPosition(4);
                document.remove(0, 5);
            }));
            filter.session.saveAutomatic();
            return;
        }
        SwingUtilities.invokeLater(() -> {
            TabbedApplication app = new TabbedApplication();
            app.show();
            app.addDocument(initialLog, initialLog == null ? null : Path.of(args[1]));
        });
    }
}
