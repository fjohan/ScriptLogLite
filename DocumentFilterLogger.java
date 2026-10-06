import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.PrintWriter;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.Duration;
import javax.swing.JComboBox;
import javax.swing.Timer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.CaretEvent;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

/** Logs DocumentFilter editing methods and text-area caret and key events. */
public class DocumentFilterLogger {
    private static final Path LOG_PATH = Path.of("document-filter.log");

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--replay")) {
            if (args.length < 2 || args.length > 3) {
                throw new IllegalArgumentException("Usage: --replay LOG_FILE [SESSION_NUMBER]");
            }
            ReplayLog replay = ReplayLog.load(Path.of(args[1]),
                    args.length == 3 ? Integer.parseInt(args[2]) : -1);
            SwingUtilities.invokeLater(() -> new ReplayWindow(replay).show());
            return;
        }
        if (args.length == 1 && args[0].equals("--self-test")) {
            testReplay();
            return;
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
        PrintWriter log = new PrintWriter(Files.newBufferedWriter(LOG_PATH,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND), true);
        Runtime.getRuntime().addShutdownHook(new Thread(log::close));
        LoggingFilter filter = new LoggingFilter(log);
        filter.write("session initialText=" + LoggingFilter.quote(""));
        System.out.println("Logging to " + LOG_PATH.toAbsolutePath());

        if (args.length == 1 && args[0].equals("--demo")) {
            SwingUtilities.invokeAndWait(() -> edit(() -> {
                JTextArea text = createTextArea(filter);
                AbstractDocument document = (AbstractDocument) text.getDocument();
                document.insertString(0, "Hello", null);
                document.replace(0, 5, "World", null);
                text.setCaretPosition(1);
                text.moveCaretPosition(4);
                document.remove(0, 5);
            }));
            log.close();
            return;
        }
        SwingUtilities.invokeLater(() -> showWindow(filter, initialLog));
    }

    private static void showWindow(LoggingFilter filter, ReplayLog initialLog) {
        JTextArea text = createTextArea(filter);
        AbstractDocument document = (AbstractDocument) text.getDocument();
        if (initialLog != null) restoreLog(text, filter, initialLog);
        JFrame frame = new JFrame("DocumentFilter logger");
        JLabel status = new JLabel("Log contains the document and edit history.");
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("saved-document.log"));

        JPanel buttons = new JPanel();
        JButton insert = new JButton("insertString");
        insert.addActionListener(event -> edit(() ->
                document.insertString(text.getCaretPosition(), "Hello", null)));
        JButton replace = new JButton("replace");
        replace.addActionListener(event -> edit(() -> document.replace(
                text.getSelectionStart(),
                text.getSelectionEnd() - text.getSelectionStart(), "World", null)));
        JButton remove = new JButton("remove");
        remove.addActionListener(event -> edit(() -> {
            int start = text.getSelectionStart();
            int length = text.getSelectionEnd() - start;
            if (length == 0 && start < document.getLength()) {
                length = 1;
            }
            document.remove(start, length);
        }));
        buttons.add(insert);
        buttons.add(replace);
        buttons.add(remove);

        JButton save = new JButton("Save Log…");
        save.addActionListener(event -> {
            if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
            Path path = chooser.getSelectedFile().toPath();
            if (Files.exists(path) && JOptionPane.showConfirmDialog(frame,
                    "Replace " + path + "?", "Save Log", JOptionPane.YES_NO_OPTION)
                    != JOptionPane.YES_OPTION) return;
            try {
                filter.save(path);
                status.setText("Saved " + path.toAbsolutePath());
            } catch (Exception exception) {
                showError(frame, exception);
            }
        });
        JButton open = new JButton("Open Log…");
        open.addActionListener(event -> {
            if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
            try {
                Path path = chooser.getSelectedFile().toPath();
                ReplayLog loaded = ReplayLog.load(path, -1);
                if (JOptionPane.showConfirmDialog(frame,
                        "Open this log and replace the current session? Save your current log first "
                        + "if you want to keep it.", "Open Log", JOptionPane.OK_CANCEL_OPTION)
                        != JOptionPane.OK_OPTION) return;
                restoreLog(text, filter, loaded);
                status.setText("Opened " + path.toAbsolutePath() + " — continue editing, then Save Log.");
                text.requestFocusInWindow();
            } catch (Exception exception) {
                showError(frame, exception);
            }
        });
        JButton replay = new JButton("Replay current log");
        replay.addActionListener(event -> {
            try {
                new ReplayWindow(ReplayLog.parse(new ArrayList<>(filter.entries))).show();
            } catch (Exception exception) {
                showError(frame, exception);
            }
        });
        buttons.add(save);
        buttons.add(open);
        buttons.add(replay);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(status, java.awt.BorderLayout.NORTH);
        frame.add(new JScrollPane(text), java.awt.BorderLayout.CENTER);
        frame.add(buttons, java.awt.BorderLayout.SOUTH);
        frame.pack();
        frame.setSize(Math.max(950, frame.getWidth()), frame.getHeight());
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void showError(JFrame frame, Exception exception) {
        JOptionPane.showMessageDialog(frame, exception.getMessage(), "Log error",
                JOptionPane.ERROR_MESSAGE);
    }

    static void restoreLog(JTextArea text, LoggingFilter filter, ReplayLog loaded) {
        ReplayState state = loaded.states.get(loaded.states.size() - 1);
        filter.restoring = true;
        try {
            text.setText(state.text);
            text.setCaretPosition(state.mark);
            text.moveCaretPosition(state.dot);
        } finally {
            filter.restoring = false;
        }
        filter.entries.clear();
        filter.entries.addAll(loaded.lines);
        filter.lastTime = state.time;
        // The automatic audit file starts a checkpoint; saved logs retain the entire history.
        filter.audit(Instant.now() + " session initialText=" + LoggingFilter.quote(state.text));
        filter.audit(Instant.now() + " caretUpdate dot=" + state.dot + " mark=" + state.mark);
    }

    private static JTextArea createTextArea(LoggingFilter filter) {
        JTextArea text = new JTextArea(12, 50);
        ((AbstractDocument) text.getDocument()).setDocumentFilter(filter);
        text.addCaretListener(filter::recordCaret);
        text.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                filter.recordKey("keyPressed", event);
            }

            @Override
            public void keyReleased(KeyEvent event) {
                filter.recordKey("keyReleased", event);
            }
        });
        return text;
    }

    private static void edit(DocumentEdit action) {
        try {
            action.run();
        } catch (BadLocationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @FunctionalInterface
    private interface DocumentEdit {
        void run() throws BadLocationException;
    }

    public static class LoggingFilter extends DocumentFilter {
        private final PrintWriter log;
        final List<String> entries = new ArrayList<>();
        boolean restoring;
        Instant lastTime = Instant.MIN;

        synchronized void save(Path path) throws IOException {
            if (path.toAbsolutePath().normalize().equals(LOG_PATH.toAbsolutePath().normalize())
                    || (Files.exists(path) && Files.exists(LOG_PATH) && Files.isSameFile(path, LOG_PATH))) {
                throw new IOException("Choose a different filename from the active automatic log: " + LOG_PATH);
            }
            // Validate before writing, and replace the target only after the full write succeeds.
            ReplayLog.parse(new ArrayList<>(entries));
            Path target = path.toAbsolutePath();
            Path temporary = Files.createTempFile(target.getParent(), ".saved-log-", ".tmp");
            try {
                Files.write(temporary, entries, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        public LoggingFilter(PrintWriter log) {
            this.log = log;
        }

        @Override
        public void insertString(FilterBypass bypass, int offset, String string,
                AttributeSet attributes) throws BadLocationException {
            record("insertString", offset, 0, string, attributes, "");
            super.insertString(bypass, offset, string, attributes);
        }

        @Override
        public void replace(FilterBypass bypass, int offset, int length, String text,
                AttributeSet attributes) throws BadLocationException {
            record("replace", offset, length, text, attributes,
                    bypass.getDocument().getText(offset, length));
            super.replace(bypass, offset, length, text, attributes);
        }

        @Override
        public void remove(FilterBypass bypass, int offset, int length)
                throws BadLocationException {
            record("remove", offset, length, null, null,
                    bypass.getDocument().getText(offset, length));
            super.remove(bypass, offset, length);
        }

        public void recordCaret(CaretEvent event) {
            write("caretUpdate dot=" + event.getDot() + " mark=" + event.getMark()
                    + " selectionStart=" + Math.min(event.getDot(), event.getMark())
                    + " selectionEnd=" + Math.max(event.getDot(), event.getMark()));
        }

        public void recordKey(String method, KeyEvent event) {
            write(method + " keyCode=" + event.getKeyCode()
                    + " keyText=" + quote(KeyEvent.getKeyText(event.getKeyCode()))
                    + " keyChar=" + (event.getKeyChar() == KeyEvent.CHAR_UNDEFINED
                            ? "undefined" : quote(String.valueOf(event.getKeyChar())))
                    + " modifiers=" + event.getModifiersEx()
                    + " modifiersText=" + quote(KeyEvent.getModifiersExText(event.getModifiersEx()))
                    + " keyLocation=" + event.getKeyLocation());
        }

        private void record(String method, int offset, int length,
                String text, AttributeSet attributes, String oldText) {
            write(method + " offset=" + offset
                    + " length=" + length + " text=" + quote(text) + " oldText=" + quote(oldText)
                    + " attributes=" + quote(attributes == null ? null : attributes.toString()));
        }

        private synchronized void write(String message) {
            if (restoring) return;
            Instant now = Instant.now();
            if (now.isBefore(lastTime)) now = lastTime;
            lastTime = now;
            String entry = now + " " + message;
            audit(entry);
            entries.add(entry);
        }

        private void audit(String entry) {
            log.println(entry);
            if (log.checkError()) {
                throw new IllegalStateException("Unable to write " + LOG_PATH);
            }
            System.out.println(entry);
        }

        private static String quote(String text) {
            if (text == null) {
                return "null";
            }
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r")
                    .replace("\t", "\\t") + "\"";
        }
    }

    /** Immutable state after a recorded event; snapshots make reverse steps exact. */
    static class ReplayState {
        final Instant time;
        final String text;
        final int dot, mark;
        final String description;
        final boolean edit;

        ReplayState(Instant time, String text, int dot, int mark,
                String description, boolean edit) {
            this.time = time;
            this.text = text;
            this.dot = dot;
            this.mark = mark;
            this.description = description;
            this.edit = edit;
        }
    }

    static class ReplayLog {
        final List<ReplayState> states = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        // Each boundary includes an edit and the caret/key events following it.
        final List<Integer> boundaries = new ArrayList<>();
        static final Pattern FIELD = Pattern.compile(
                "(\\w+)=(\"(?:\\\\.|[^\"\\\\])*\"|\\S+)");

        static ReplayLog load(Path path, int session) throws Exception {
            List<List<String>> sessions = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                if (line.matches("\\S+ session .*")) sessions.add(new ArrayList<>());
                if (!sessions.isEmpty()) sessions.get(sessions.size() - 1).add(line);
            }
            if (sessions.isEmpty()) {
                throw new IllegalArgumentException("This log has no session marker. "
                        + "Record a new session with this version to enable replay.");
            }
            int index = session == -1 ? sessions.size() - 1 : session - 1;
            if (index < 0 || index >= sessions.size()) {
                throw new IllegalArgumentException("Session must be between 1 and " + sessions.size());
            }
            return parse(sessions.get(index));
        }

        static ReplayLog parse(List<String> lines) {
            ReplayLog result = new ReplayLog();
            result.lines.addAll(lines);
            String text = "";
            int dot = 0, mark = 0;
            Instant previous = null;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                try {
                    String[] parts = line.split(" ", 3);
                    Instant time = Instant.parse(parts[0]);
                    if (previous != null && time.isBefore(previous)) {
                        throw new IllegalArgumentException("timestamps run backwards");
                    }
                    previous = time;
                    String method = parts[1];
                    Map<String, String> fields = new HashMap<>();
                    Matcher matcher = FIELD.matcher(parts.length == 3 ? parts[2] : "");
                    while (matcher.find()) fields.put(matcher.group(1), decode(matcher.group(2)));
                    boolean edit = false;
                    if (i == 0) {
                        if (!method.equals("session")) throw new IllegalArgumentException("missing session");
                        text = required(fields, "initialText");
                    } else if (method.equals("insertString") || method.equals("replace")
                            || method.equals("remove")) {
                        edit = true;
                        int offset = Integer.parseInt(required(fields, "offset"));
                        int length = Integer.parseInt(required(fields, "length"));
                        String old = required(fields, "oldText");
                        String replacement = method.equals("remove") ? "" : fields.get("text");
                        if (replacement == null) replacement = "";
                        if (offset < 0 || length < 0 || offset > text.length() - length
                                || !text.substring(offset, offset + length).equals(old)) {
                            throw new IllegalArgumentException("edit does not match the document");
                        }
                        text = text.substring(0, offset) + replacement + text.substring(offset + length);
                        dot = Math.min(dot, text.length());
                        mark = Math.min(mark, text.length());
                    } else if (method.equals("caretUpdate")) {
                        dot = Integer.parseInt(required(fields, "dot"));
                        mark = Integer.parseInt(required(fields, "mark"));
                        if (dot < 0 || mark < 0 || dot > text.length() || mark > text.length()) {
                            throw new IllegalArgumentException("caret outside document");
                        }
                    } else if (!method.equals("keyPressed") && !method.equals("keyReleased")) {
                        throw new IllegalArgumentException("unknown event " + method);
                    }
                    result.states.add(new ReplayState(time, text, dot, mark,
                            line.substring(parts[0].length() + 1), edit));
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("Invalid replay event " + (i + 1)
                            + ": " + exception.getMessage(), exception);
                }
            }
            if (result.states.isEmpty()) throw new IllegalArgumentException("Empty session");
            result.boundaries.add(0);
            for (int i = 1; i < result.states.size(); i++) {
                if (result.states.get(i).edit) {
                    if (result.boundaries.size() > 1) {
                        result.boundaries.set(result.boundaries.size() - 1, i - 1);
                    }
                    result.boundaries.add(i);
                }
            }
            if (result.boundaries.size() > 1) {
                result.boundaries.set(result.boundaries.size() - 1, result.states.size() - 1);
            }
            return result;
        }

        static String required(Map<String, String> fields, String key) {
            String value = fields.get(key);
            if (value == null) throw new IllegalArgumentException("missing " + key);
            return value;
        }

        static String decode(String value) {
            if (value.equals("null")) return null;
            if (!value.startsWith("\"")) return value;
            StringBuilder decoded = new StringBuilder();
            for (int i = 1; i < value.length() - 1; i++) {
                char c = value.charAt(i);
                if (c == '\\') {
                    c = value.charAt(++i);
                    switch (c) {
                        case 'n': c = '\n'; break;
                        case 'r': c = '\r'; break;
                        case 't': c = '\t'; break;
                        case '\\': case '"': break;
                        default: throw new IllegalArgumentException("unknown escape");
                    }
                }
                decoded.append(c);
            }
            return decoded.toString();
        }

        int nextEdit(int position) {
            for (int boundary : boundaries) if (boundary > position) return boundary;
            return position;
        }

        int previousEdit(int position) {
            for (int i = boundaries.size() - 1; i >= 0; i--) {
                if (boundaries.get(i) < position) return boundaries.get(i);
            }
            return 0;
        }
    }

    static class ReplayWindow {
        final ReplayLog log;
        final JTextArea text = new JTextArea(12, 50);
        final JLabel status = new JLabel();
        final JButton play = new JButton("Play");
        final JComboBox<String> speed = new JComboBox<>(
                new String[] {"0.25", "0.5", "1", "2", "4", "8"});
        final Timer timer;
        int position;
        double elapsedNanos;
        long lastTick;
        double playbackSpeed = 1;

        ReplayWindow(ReplayLog log) {
            this.log = log;
            text.setEditable(false);
            speed.setSelectedItem("1");
            timer = new Timer(10, event -> tick());
            play.addActionListener(event -> {
                if (timer.isRunning()) { tick(); pause(); }
                else if (position < log.states.size() - 1) {
                    lastTick = System.nanoTime();
                    timer.start();
                    play.setText("Pause");
                }
            });
            // Accrue elapsed time at the previous speed before changing it.
            speed.addActionListener(event -> {
                if (timer.isRunning()) tick();
                playbackSpeed = multiplier();
            });
        }

        double multiplier() {
            return Double.parseDouble((String) speed.getSelectedItem());
        }

        void tick() {
            long now = System.nanoTime();
            long delta = now - lastTick;
            lastTick = now;
            advance(delta);
        }

        void advance(long realNanos) {
            elapsedNanos += realNanos * playbackSpeed;
            while (position < log.states.size() - 1) {
                double gap = Duration.between(log.states.get(position).time,
                        log.states.get(position + 1).time).toNanos();
                if (elapsedNanos < gap) break;
                elapsedNanos -= gap;
                position++;
            }
            render();
            if (position == log.states.size() - 1) pause();
        }

        void pause() {
            timer.stop();
            play.setText("Play");
        }

        void seek(int target) {
            pause();
            elapsedNanos = 0;
            position = target;
            render();
        }

        void render() {
            ReplayState state = log.states.get(position);
            if (!text.getText().equals(state.text)) text.setText(state.text);
            text.setCaretPosition(state.mark);
            text.moveCaretPosition(state.dot);
            status.setText("Event " + position + "/" + (log.states.size() - 1)
                    + " — " + state.description);
            status.setToolTipText(state.time + " " + state.description);
        }

        void show() {
            JButton forward = new JButton("Next edit");
            forward.addActionListener(event -> seek(log.nextEdit(position)));
            JButton backward = new JButton("Previous edit");
            backward.addActionListener(event -> seek(log.previousEdit(position)));
            JButton reset = new JButton("Restart");
            reset.addActionListener(event -> seek(0));
            JPanel controls = new JPanel();
            controls.add(backward);
            controls.add(forward);
            controls.add(play);
            controls.add(new JLabel("Speed ×"));
            controls.add(speed);
            controls.add(reset);
            JFrame frame = new JFrame("Log replay");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent event) { pause(); }
            });
            frame.add(status, java.awt.BorderLayout.NORTH);
            frame.add(new JScrollPane(text), java.awt.BorderLayout.CENTER);
            frame.add(controls, java.awt.BorderLayout.SOUTH);
            render();
            frame.setSize(900, 400);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        }
    }

    static void testReplay() throws Exception {
        String special = "A\n\"\\\t\r😀";
        List<String> lines = List.of(
                "2026-01-01T00:00:00Z session initialText=\"\"",
                "2026-01-01T00:00:01Z insertString offset=0 length=0 text="
                        + LoggingFilter.quote(special) + " oldText=\"\"",
                "2026-01-01T00:00:01.1Z caretUpdate dot=" + special.length() + " mark=0",
                "2026-01-01T00:00:02Z replace offset=0 length=" + special.length()
                        + " text=\"World\" oldText=" + LoggingFilter.quote(special),
                "2026-01-01T00:00:02.1Z keyReleased keyCode=65",
                "2026-01-01T00:00:03Z remove offset=0 length=5 text=null oldText=\"World\"");
        ReplayLog replay = ReplayLog.parse(lines);
        int first = replay.nextEdit(0);
        int second = replay.nextEdit(first);
        int third = replay.nextEdit(second);
        check(replay.states.get(first).text.equals(special), "escaped text");
        check(replay.states.get(first).dot == special.length()
                && replay.states.get(first).mark == 0, "selection");
        check(replay.states.get(second).text.equals("World"), "replace");
        check(replay.states.get(third).text.isEmpty(), "remove");
        check(replay.previousEdit(third) == second
                && replay.previousEdit(second) == first
                && replay.previousEdit(first) == 0, "reverse traversal");
        check(replay.nextEdit(third) == third && replay.previousEdit(0) == 0, "bounds");
        try {
            ReplayLog.parse(List.of(lines.get(0),
                    "2026-01-01T00:00:01Z remove offset=0 length=1 oldText=\"X\""));
            throw new AssertionError("Invalid edit accepted");
        } catch (IllegalArgumentException expected) { }
        Path fixture = Files.createTempFile("document-replay-test", ".log");
        try {
            List<String> sessions = new ArrayList<>();
            sessions.add("legacy entry ignored");
            sessions.addAll(lines);
            sessions.add("2026-01-02T00:00:00Z session initialText=\"second\"");
            Files.write(fixture, sessions, StandardCharsets.UTF_8);
            check(ReplayLog.load(fixture, -1).states.get(0).text.equals("second"), "latest session");
            check(ReplayLog.load(fixture, 1).states.size() == lines.size(), "first session");
        } finally {
            Files.deleteIfExists(fixture);
        }
        SwingUtilities.invokeAndWait(() -> {
            ReplayWindow window = new ReplayWindow(replay);
            for (String rate : new String[] {"0.5", "1", "2"}) {
                window.seek(0);
                window.speed.setSelectedItem(rate);
                window.advance((long) (1_500_000_000 / Double.parseDouble(rate)));
                check(window.position == first, "timed playback at " + rate);
                window.seek(third);
                window.seek(replay.previousEdit(window.position));
                check(window.text.getText().equals("World"), "render reverse");
            }
            window.seek(0);
            window.speed.setSelectedItem("1");
            window.advance(250_000_000);
            window.pause();
            check(window.position == 0, "partial interval");
            window.speed.setSelectedItem("2");
            window.advance(375_000_000);
            check(window.position == 1, "resume and speed change preserve elapsed time");
            window.seek(0);
            check(window.elapsedNanos == 0 && window.text.getText().isEmpty(), "restart");
        });
        testSaveAndContinue(lines.subList(0, 3), special);
        System.out.println("Replay self-test passed");
    }

    static void testSaveAndContinue(List<String> original, String special) throws Exception {
        Path directory = Files.createTempDirectory("document-log-roundtrip");
        Path saved = directory.resolve("saved.log");
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    LoggingFilter filter = new LoggingFilter(new PrintWriter(new java.io.StringWriter()));
                    filter.write("session initialText=\"\"");
                    JTextArea text = createTextArea(filter);
                    restoreLog(text, filter, ReplayLog.parse(original));
                    check(text.getText().equals(special), "open restores Unicode and escaped text");
                    check(text.getCaret().getDot() == special.length()
                            && text.getCaret().getMark() == 0, "open restores selection direction");
                    check(filter.entries.equals(original), "restore creates no synthetic events");
                    filter.save(saved);
                    check(Files.readAllLines(saved).equals(original), "save preserves history");

                    LoggingFilter continued = new LoggingFilter(new PrintWriter(new java.io.StringWriter()));
                    JTextArea next = createTextArea(continued);
                    restoreLog(next, continued, ReplayLog.load(saved, -1));
                    ((AbstractDocument) next.getDocument()).replace(0, special.length(), "Continued", null);
                    next.setCaretPosition(2);
                    next.moveCaretPosition(6);
                    continued.save(saved);
                    ReplayLog roundtrip = ReplayLog.load(saved, -1);
                    ReplayState finalState = roundtrip.states.get(roundtrip.states.size() - 1);
                    check(finalState.text.equals("Continued") && finalState.dot == 6
                            && finalState.mark == 2, "save after continued editing");
                    check(roundtrip.lines.subList(0, original.size()).equals(original),
                            "continued save retains original events");
                    int previous = roundtrip.previousEdit(roundtrip.states.size() - 1);
                    check(roundtrip.states.get(previous).text.equals(special),
                            "reverse replay crosses the save/open boundary");
                    restoreLog(next, continued, roundtrip);
                    check(next.getText().equals("Continued") && continued.entries.equals(roundtrip.lines),
                            "repeated reopen");
                    try {
                        continued.save(directory.resolve("missing").resolve("failed.log"));
                        throw new AssertionError("Save to missing directory succeeded");
                    } catch (IOException expected) { }
                    check(Files.readAllLines(saved).equals(roundtrip.lines), "failed save preserves existing log");
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
        } finally {
            Files.deleteIfExists(saved);
            Files.deleteIfExists(directory);
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
