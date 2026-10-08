package se.lu.scriptloglite;

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
            filter.session.automaticPath = allocateRecording(new RecordingVariables("expr", "_", "subj"), java.time.LocalDate.now());
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
