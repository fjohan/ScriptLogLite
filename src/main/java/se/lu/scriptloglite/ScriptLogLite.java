package se.lu.scriptloglite;

import java.awt.Point;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.Duration;
import javax.swing.JComboBox;
import javax.swing.Timer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JTabbedPane;
import javax.swing.JToolBar;
import javax.swing.BorderFactory;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.AbstractAction;
import javax.swing.Action;
import java.awt.event.InputEvent;
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
public class ScriptLogLite {
    private static Path workingRoot = Path.of(System.getProperty("user.home"), "ScriptLogLiteWD");

    static Path workingDirectory() {
        try { return Files.createDirectories(workingRoot).toAbsolutePath(); }
        catch (IOException exception) { throw new java.io.UncheckedIOException("Cannot create working directory " + workingRoot, exception); }
    }

    static final class RecordingVariables {
        final String experiment, condition, subject;
        RecordingVariables(String experiment, String condition, String subject) {
            for (String value : List.of(experiment, condition, subject)) {
                if (!value.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Invalid recording identifier");
            }
            this.experiment = experiment; this.condition = condition; this.subject = subject;
            if (prefix().equals(".") || prefix().equals("..")) throw new IllegalArgumentException("Invalid recording directory");
        }
        String prefix() { return experiment + condition + subject; }
    }

    static Path allocateRecording(RecordingVariables variables, java.time.LocalDate date) throws IOException {
        Path group = Files.createDirectories(workingDirectory().resolve(variables.prefix()));
        int index = 1;
        Pattern numbered = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_(\\d+)");
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
        while (Files.exists(file)) {
            index = Math.addExact(index, 1);
            file = directory.resolve(prefix + "_sll_" + index + ".json");
        }
        return file;
    }

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
        if (args.length == 1 && args[0].equals("--demo")) {
            LoggingFilter filter = new LoggingFilter();
            filter.automaticPath = allocateRecording(new RecordingVariables("expr", "_", "subj"), java.time.LocalDate.now());
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
            filter.saveAutomatic();
            return;
        }
        SwingUtilities.invokeLater(() -> {
            TabbedApplication app = new TabbedApplication();
            app.show();
            app.addDocument(initialLog, initialLog == null ? null : Path.of(args[1]));
        });
    }

    enum Theme {
        NIMBUS("Nimbus", "javax.swing.plaf.nimbus.NimbusLookAndFeel"),
        LIGHT("Light", "com.formdev.flatlaf.FlatLightLaf"),
        DARK("Dark", "com.formdev.flatlaf.FlatDarkLaf");

        final String label, className;
        Theme(String label, String className) { this.label = label; this.className = className; }
    }

    // Loading by name keeps dependency-free headless log checks/source launching available.
    static void installTheme(Theme theme) throws Exception {
        try {
            javax.swing.UIManager.setLookAndFeel(theme.className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("FlatLaf is missing. Start with ./run.sh or build with Maven; "
                    + "see README.md for the classpath instructions.", exception);
        }
    }

    static final class DirectoryHistory {
        final Path file;
        final java.util.Properties properties = new java.util.Properties();
        DirectoryHistory(Path file) {
            this.file = file;
            if (Files.isRegularFile(file)) {
                try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                } catch (IOException exception) { System.err.println("Cannot read directory preferences: " + exception.getMessage()); }
            }
        }
        Path directory(String operation) {
            String saved = properties.getProperty(operation);
            if (saved != null) {
                try {
                    Path path = Path.of(saved);
                    if (Files.isDirectory(path)) return path;
                } catch (java.nio.file.InvalidPathException ignored) { }
            }
            return workingDirectory();
        }
        void remember(String operation, Path selectedFile) {
            properties.setProperty(operation, selectedFile.toAbsolutePath().normalize().getParent().toString());
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                try (java.io.Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    properties.store(writer, "ScriptLogLite last open/save directories");
                }
            } catch (IOException exception) { System.err.println("Cannot save directory preferences: " + exception.getMessage()); }
        }
    }

    /** One application window with independent document and replay tabs. */
    static class TabbedApplication {
        final JTabbedPane tabs = new JTabbedPane();
        final JToolBar toolbar = new JToolBar();
        final Map<java.awt.Component, ReplayWindow> replays = new HashMap<>();
        final JMenuBar menus = new JMenuBar();
        final JMenu tabsMenu = new JMenu("Tabs");
        final JLabel status = new JLabel("Ready");
        final List<DocumentTab> documents = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<LoggingFilter> recordings = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Action> documentActions = new ArrayList<>();
        final JFileChooser openChooser = new JFileChooser();
        final JFileChooser saveChooser = new JFileChooser();
        final DirectoryHistory directories;
        final RecordingVariables recordingVariables = new RecordingVariables("expr", "_", "subj");
        final Map<Theme, javax.swing.JRadioButtonMenuItem> themeChoices = new java.util.EnumMap<>(Theme.class);
        Theme theme = Theme.NIMBUS;
        final Timer autosave;
        final BackgroundSaver saver;
        boolean closeAllRequested, exitRequested;
        JFrame frame;
        int sequence;

        TabbedApplication() {
            this(new DirectoryHistory(Path.of(System.getProperty("user.home"), ".config", "scriptloglite", "directories.properties")));
        }

        TabbedApplication(DirectoryHistory directories) {
            this.directories = directories;
            tabs.setPreferredSize(new Dimension(1100, 700));
            tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            tabs.addChangeListener(event -> updateActions());
            toolbar.setFloatable(false);
            toolbar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            openChooser.setCurrentDirectory(directories.directory("open").toFile());
            saveChooser.setCurrentDirectory(directories.directory("save").toFile());
            JMenu file = menu("File", KeyEvent.VK_F);
            toolbar.add(item(file, "New", KeyEvent.VK_N, false, () -> addDocument(null, null)));
            toolbar.add(item(file, "Open Log…", KeyEvent.VK_O, false, () -> open(false)));
            file.addSeparator();
            toolbar.add(item(file, "Save Log", KeyEvent.VK_S, true, () -> save(activeDocument(), false)));
            toolbar.addSeparator();
            Action saveAs = item(file, "Save Log As…", 0, true, () -> save(activeDocument(), true));
            saveAs.putValue(Action.ACCELERATOR_KEY,
                    KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
            file.addSeparator();
            item(file, "Close", KeyEvent.VK_W, false, this::closeActive);
            item(file, "Exit", KeyEvent.VK_Q, false, this::exit);

            JMenu editMenu = menu("Edit", KeyEvent.VK_E);
            item(editMenu, "Cut", KeyEvent.VK_X, true, () -> activeDocument().text.cut());
            item(editMenu, "Copy", KeyEvent.VK_C, true, () -> activeDocument().text.copy());
            item(editMenu, "Paste", KeyEvent.VK_V, true, () -> activeDocument().text.paste());
            item(editMenu, "Select All", KeyEvent.VK_A, true, () -> activeDocument().text.selectAll());
            editMenu.addSeparator();
            item(editMenu, "Insert Hello", 0, true, () -> activeDocument().insert());
            item(editMenu, "Replace Selection with World", 0, true, () -> activeDocument().replace());
            item(editMenu, "Remove Selection / Next Character", 0, true, () -> activeDocument().remove());

            JMenu view = menu("View", KeyEvent.VK_V);
            toolbar.add(item(view, "Replay Current Log", KeyEvent.VK_R, true, () -> {
                DocumentTab document = activeDocument();
                try {
                    addReplay(document.filter.replay(), document.title);
                } catch (Exception exception) { showError(frame, exception); }
            }));
            item(view, "Open Log for Replay…", 0, false, () -> open(true));
            view.addSeparator();
            JMenu appearance = new JMenu("Theme");
            javax.swing.ButtonGroup themeGroup = new javax.swing.ButtonGroup();
            for (Theme choice : Theme.values()) {
                javax.swing.JRadioButtonMenuItem option = new javax.swing.JRadioButtonMenuItem(
                        choice.label, choice == theme);
                themeGroup.add(option);
                appearance.add(option);
                themeChoices.put(choice, option);
                option.addActionListener(event -> {
                    try { changeTheme(choice); }
                    catch (Exception exception) {
                        themeChoices.get(theme).setSelected(true);
                        showError(frame, exception);
                    }
                });
            }
            view.add(appearance);
            tabsMenu.setMnemonic(KeyEvent.VK_T);
            menus.add(tabsMenu);
            tabsMenu.addMenuListener(new javax.swing.event.MenuListener() {
                public void menuSelected(javax.swing.event.MenuEvent event) { rebuildTabsMenu(); }
                public void menuDeselected(javax.swing.event.MenuEvent event) { }
                public void menuCanceled(javax.swing.event.MenuEvent event) { }
            });
            rebuildTabsMenu();
            JMenu help = menu("Help", KeyEvent.VK_H);
            item(help, "About", 0, false, () -> JOptionPane.showMessageDialog(frame,
                    "ScriptLogLite\nEach document has its own JSON edit history.\n"
                    + "Save and open logs to continue writing, or replay them at any speed.",
                    "About", JOptionPane.INFORMATION_MESSAGE));
            updateActions();
            saver = new BackgroundSaver(exception -> {
                stopAutosave();
                closeAllRequested = exitRequested = false;
                showError(frame, exception);
            });
            autosave = new Timer(500, event -> {
                for (DocumentTab document : documents) saver.automatic(document.filter);
            });
        }

        void stopAutosave() { autosave.stop(); }

        JMenu menu(String name, int mnemonic) {
            JMenu menu = new JMenu(name);
            menu.setMnemonic(mnemonic);
            menus.add(menu);
            return menu;
        }

        void changeTheme(Theme selected) throws Exception {
            // Look-and-feel updates must not turn view restoration into recorded edits.
            Map<DocumentTab, Point> positions = new HashMap<>();
            Map<DocumentTab, Point> selections = new HashMap<>();
            for (DocumentTab document : documents) {
                JScrollPane scroll = (JScrollPane) document.text.getClientProperty("logScrollPane");
                positions.put(document, new Point(scroll.getViewport().getViewPosition()));
                selections.put(document, new Point(document.text.getCaret().getDot(), document.text.getCaret().getMark()));
                document.filter.restoring = true;
            }
            try {
                installTheme(selected);
                for (java.awt.Window window : java.awt.Window.getWindows()) {
                    SwingUtilities.updateComponentTreeUI(window);
                }
                if (frame == null) {
                    SwingUtilities.updateComponentTreeUI(tabs);
                    SwingUtilities.updateComponentTreeUI(menus);
                    SwingUtilities.updateComponentTreeUI(toolbar);
                }
                SwingUtilities.updateComponentTreeUI(openChooser);
                SwingUtilities.updateComponentTreeUI(saveChooser);
                for (Map.Entry<DocumentTab, Point> entry : positions.entrySet()) {
                    Point selection = selections.get(entry.getKey());
                    entry.getKey().text.setCaretPosition(selection.y);
                    entry.getKey().text.moveCaretPosition(selection.x);
                    JScrollPane scroll = (JScrollPane) entry.getKey().text.getClientProperty("logScrollPane");
                    applyScroll(scroll, entry.getValue().x, entry.getValue().y);
                }
                for (ReplayWindow viewer : replays.values()) viewer.render();
                theme = selected;
                themeChoices.get(theme).setSelected(true);
            } finally {
                for (DocumentTab document : documents) document.filter.restoring = false;
            }
        }

        Action item(JMenu menu, String name, int key, boolean requiresDocument, Runnable command) {
            Action action = new AbstractAction(name) {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    try { if (!requiresDocument || activeDocument() != null) command.run(); }
                    catch (Exception exception) { showError(frame, exception); }
                    if (activeDocument() == null || !activeDocument().saving) updateActions();
                }
            };
            if (key != 0) action.putValue(Action.ACCELERATOR_KEY,
                    KeyStroke.getKeyStroke(key, InputEvent.CTRL_DOWN_MASK));
            menu.add(new JMenuItem(action));
            if (requiresDocument) documentActions.add(action);
            return action;
        }

        void show() {
            frame = new JFrame("ScriptLogLite");
            frame.setJMenuBar(menus);
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent event) { exit(); }
            });
            frame.add(toolbar, java.awt.BorderLayout.NORTH);
            frame.add(tabs, java.awt.BorderLayout.CENTER);
            frame.add(status, java.awt.BorderLayout.SOUTH);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            autosave.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                for (LoggingFilter recording : recordings) saver.automatic(recording);
                saver.close();
            }, "scriptloglite-save-shutdown"));
        }

        DocumentTab activeDocument() {
            java.awt.Component selected = tabs.getSelectedComponent();
            return selected instanceof DocumentTab ? (DocumentTab) selected : null;
        }

        void updateActions() {
            for (Action action : documentActions) action.setEnabled(activeDocument() != null);
            DocumentTab active = activeDocument();
            status.setText(active == null ? "Ready — File → New or Open Log"
                    : active.title + " — automatic log: " + active.filter.automaticPath.toAbsolutePath());
        }

        DocumentTab addDocument(ReplayLog loaded, Path path) {
            int id = ++sequence;
            LoggingFilter filter = new LoggingFilter();
            try { filter.automaticPath = allocateRecording(recordingVariables, java.time.LocalDate.now()); }
            catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
            filter.startSession("");
            DocumentTab document = new DocumentTab(this, filter, "Untitled " + id);
            if (loaded != null) restoreLog(document.text, filter, loaded);
            document.savedPath = path;
            document.savedEntries = loaded == null ? 1 : filter.entries.size();
            document.updateTitle();
            documents.add(document);
            recordings.add(filter);
            addTab(document, document.title);
            if (loaded != null) {
                ReplayState state = loaded.states.get(loaded.states.size() - 1);
                SwingUtilities.invokeLater(() -> restoreScroll(document.text, filter, state));
            }
            document.text.requestFocusInWindow();
            return document;
        }

        void addReplay(ReplayLog replay, String title) {
            ReplayWindow viewer = new ReplayWindow(replay);
            JPanel panel = viewer.panel();
            replays.put(panel, viewer);
            addTab(panel, "Replay — " + title);
        }

        void addTab(java.awt.Component panel, String title) {
            tabs.addTab(title, panel);
            JPanel header = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
            header.setOpaque(false);
            JLabel label = new JLabel(title);
            JButton close = new JButton("×");
            close.setToolTipText("Close tab");
            close.setFocusable(false);
            close.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
            close.setContentAreaFilled(false);
            close.addActionListener(event -> closeTab(panel));
            header.add(label);
            header.add(close);
            java.awt.event.MouseAdapter select = new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent event) { activate(panel); }
            };
            header.addMouseListener(select);
            label.addMouseListener(select);
            tabs.setTabComponentAt(tabs.indexOfComponent(panel), header);
            activate(panel);
        }

        void renameTab(java.awt.Component panel, String title) {
            int index = tabs.indexOfComponent(panel);
            if (index < 0) return;
            tabs.setTitleAt(index, title);
            JPanel header = (JPanel) tabs.getTabComponentAt(index);
            if (header != null) ((JLabel) header.getComponent(0)).setText(title);
        }

        void activate(java.awt.Component panel) {
            tabs.setSelectedComponent(panel);
            if (panel instanceof DocumentTab) ((DocumentTab) panel).text.requestFocusInWindow();
            updateActions();
        }

        boolean closeTab(java.awt.Component panel) {
            if (panel instanceof DocumentTab) return closeDocument((DocumentTab) panel);
            ReplayWindow viewer = replays.remove(panel);
            if (viewer != null) viewer.pause();
            tabs.remove(panel);
            updateActions();
            return true;
        }

        void open(boolean replay) {
            openChooser.setCurrentDirectory(directories.directory("open").toFile());
            openChooser.setSelectedFile(null);
            if (openChooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
            Path path = openChooser.getSelectedFile().toPath();
            try {
                ReplayLog loaded = ReplayLog.load(path, -1);
                directories.remember("open", path);
                if (replay) addReplay(loaded, path.getFileName().toString());
                else addDocument(loaded, path);
            } catch (Exception exception) { showError(frame, exception); }
        }

        boolean save(DocumentTab document, boolean saveAs) { return save(document, saveAs, () -> { }); }

        boolean save(DocumentTab document, boolean saveAs, Runnable afterSave) {
            if (document == null || document.saving) return false;
            Path path = document.savedPath;
            if (saveAs || path == null) {
                Path directory = directories.directory("save");
                saveChooser.setCurrentDirectory(directory.toFile());
                saveChooser.setSelectedFile(directory.resolve(path == null ? document.filter.automaticPath.getFileName().toString()
                        : path.getFileName().toString()).toFile());
                if (saveChooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return false;
                path = saveChooser.getSelectedFile().toPath();
                if (Files.exists(path) && JOptionPane.showConfirmDialog(frame,
                        "Replace " + path + "?", "Save Log", JOptionPane.YES_NO_OPTION)
                        != JOptionPane.YES_OPTION) return false;
            }
            try {
                for (LoggingFilter recording : recordings) {
                    if (path.toAbsolutePath().normalize().equals(recording.automaticPath.toAbsolutePath().normalize())
                            || (Files.exists(path) && Files.exists(recording.automaticPath)
                            && Files.isSameFile(path, recording.automaticPath))) {
                        throw new IOException("Choose a filename other than an active automatic log.");
                    }
                }
                final Path target = path;
                SaveSnapshot snapshot = document.filter.snapshot();
                document.saving = true;
                status.setText("Saving " + target.toAbsolutePath() + "…");
                saver.named(snapshot, target, exception -> {
                    document.saving = false;
                    if (exception != null) {
                        closeAllRequested = exitRequested = false;
                        showError(frame, exception);
                        return;
                    }
                    directories.remember("save", target);
                    document.savedPath = target;
                    // Edits recorded after the snapshot must remain marked as unsaved.
                    document.savedEntries = snapshot.events.size();
                    document.updateTitle();
                    status.setText("Saved " + target.toAbsolutePath());
                    afterSave.run();
                });
                return true;
            } catch (Exception exception) { showError(frame, exception); return false; }
        }

        boolean closeDocument(DocumentTab document) {
            if (document.saving) {
                status.setText("Saving " + document.title + " — close when saving finishes");
                closeAllRequested = exitRequested = false;
                return false;
            }
            if (document.filter.entries.size() != document.savedEntries) {
                int answer = JOptionPane.showConfirmDialog(frame,
                        "Save changes to " + document.title + "?", "Close Document",
                        JOptionPane.YES_NO_CANCEL_OPTION);
                if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) {
                    closeAllRequested = exitRequested = false;
                    return false;
                }
                if (answer == JOptionPane.YES_OPTION) {
                    boolean started = save(document, false, () -> {
                        if (closeDocument(document) && closeAllRequested) closeAll();
                    });
                    if (!started) closeAllRequested = exitRequested = false;
                    return false; // Removal happens only after the save succeeds.
                }
            }
            saver.automatic(document.filter);
            documents.remove(document);
            tabs.remove(document);
            document.filter.onRecord = () -> { };
            updateActions();
            return true;
        }

        void closeActive() {
            java.awt.Component active = tabs.getSelectedComponent();
            if (active != null) closeTab(active);
        }

        boolean closeAll() {
            closeAllRequested = true;
            while (tabs.getTabCount() > 0) {
                if (!closeTab(tabs.getComponentAt(0))) return false;
            }
            closeAllRequested = false;
            if (exitRequested) {
                autosave.stop();
                if (frame != null) frame.dispose();
                exitRequested = false;
            }
            return true;
        }

        void exit() {
            exitRequested = true;
            closeAll();
        }

        void cycleTab(int direction) {
            int count = tabs.getTabCount();
            if (count > 0) tabs.setSelectedIndex(Math.floorMod(tabs.getSelectedIndex() + direction, count));
        }

        void rebuildTabsMenu() {
            tabsMenu.removeAll();
            Action next = item(tabsMenu, "Next Tab", 0, false, () -> cycleTab(1));
            next.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN,
                    InputEvent.CTRL_DOWN_MASK));
            Action previous = item(tabsMenu, "Previous Tab", 0, false, () -> cycleTab(-1));
            previous.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP,
                    InputEvent.CTRL_DOWN_MASK));
            item(tabsMenu, "Close All", 0, false, this::closeAll);
            tabsMenu.addSeparator();
            for (int i = 0; i < tabs.getTabCount(); i++) {
                java.awt.Component panel = tabs.getComponentAt(i);
                item(tabsMenu, tabs.getTitleAt(i), 0, false, () -> activate(panel));
            }
        }
    }

    static class DocumentTab extends JPanel {
        final TabbedApplication owner;
        final LoggingFilter filter;
        final JTextArea text;
        final String untitled;
        Path savedPath;
        int savedEntries;
        boolean saving;
        String title;

        DocumentTab(TabbedApplication owner, LoggingFilter filter, String untitled) {
            super(new java.awt.BorderLayout());
            title = untitled;
            this.owner = owner;
            this.filter = filter;
            this.untitled = untitled;
            filter.onRecord = this::updateTitle;
            text = createTextArea(filter);
            add(createScrollPane(text, filter), java.awt.BorderLayout.CENTER);

        }

        void updateTitle() {
            String name = savedPath == null ? untitled : savedPath.getFileName().toString();
            title = name + (filter.entries.size() != savedEntries ? " *" : "");
            owner.renameTab(this, title);
        }
        AbstractDocument document() { return (AbstractDocument) text.getDocument(); }
        void insert() { edit(() -> document().insertString(text.getCaretPosition(), "Hello", null)); }
        void replace() { edit(() -> document().replace(text.getSelectionStart(),
                text.getSelectionEnd() - text.getSelectionStart(), "World", null)); }
        void remove() {
            edit(() -> {
                int start = text.getSelectionStart(), length = text.getSelectionEnd() - start;
                if (length == 0 && start < document().getLength()) length = 1;
                document().remove(start, length);
            });
        }
    }

    static JScrollPane createScrollPane(JTextArea text, LoggingFilter filter) {
        JScrollPane scroll = new JScrollPane(text, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        text.putClientProperty("logScrollPane", scroll);
        Point[] previous = {new Point()};
        scroll.getViewport().addChangeListener(event -> {
            Point position = scroll.getViewport().getViewPosition();
            if (!position.equals(previous[0])) {
                previous[0] = new Point(position);
                filter.append(new ScrollLogEvent(filter.timestamp(), position.x, position.y));
            }
        });
        return scroll;
    }

    static void applyScroll(JScrollPane scroll, int x, int y) {
        Dimension preferred = scroll.getViewport().getView().getPreferredSize();
        Dimension extent = scroll.getViewport().getExtentSize();
        boolean wrapped = scroll.getViewport().getView() instanceof JTextArea
                && ((JTextArea) scroll.getViewport().getView()).getLineWrap();
        scroll.getViewport().setViewSize(new Dimension(
                wrapped ? extent.width : Math.max(preferred.width, extent.width),
                Math.max(preferred.height, extent.height)));
        Dimension size = scroll.getViewport().getViewSize();
        scroll.getViewport().setViewPosition(new Point(
                Math.min(x, Math.max(0, size.width - extent.width)),
                Math.min(y, Math.max(0, size.height - extent.height))));
    }

    static void restoreScroll(JTextArea text, LoggingFilter filter, ReplayState state) {
        JScrollPane scroll = (JScrollPane) text.getClientProperty("logScrollPane");
        if (scroll == null) return;
        boolean previous = filter.restoring;
        filter.restoring = true;
        try {
            applyScroll(scroll, state.scrollX, state.scrollY);
        } finally {
            filter.restoring = previous;
        }
    }

    private static void showError(JFrame frame, Exception exception) {
        JOptionPane.showMessageDialog(frame, exception.getMessage(), "Log error",
                JOptionPane.ERROR_MESSAGE);
    }

    static void restoreLog(JTextArea text, LoggingFilter filter, ReplayLog loaded) {
        if (!loaded.metadata.isEmpty() && !loaded.metadata.containsKey("recordingStartTime")) {
            // Imported experiment clocks have no wall-clock epoch. Keep their intervals,
            // but attach the last event to now so continuation does not add decades of idle time.
            Instant originalStart = loaded.states.get(0).time;
            Instant originalEnd = loaded.states.get(loaded.states.size() - 1).time;
            Instant newStart = Instant.now().minus(Duration.between(originalStart, originalEnd));
            List<LogEvent> rebased = new ArrayList<>();
            for (LogEvent event : loaded.events) {
                rebased.add(event.at(newStart.plus(Duration.between(originalStart, event.time))));
            }
            ReplayLog adjusted = ReplayLog.parse(rebased);
            adjusted.metadata.putAll(loaded.metadata);
            adjusted.metadata.put("recordingStartTime", newStart.toString());
            loaded = adjusted;
        }
        ReplayState state = loaded.states.get(loaded.states.size() - 1);
        filter.restoring = true;
        try {
            text.setText(state.text);
            text.setCaretPosition(state.mark);
            text.moveCaretPosition(state.dot);
            restoreScroll(text, filter, state);
        } finally {
            filter.restoring = false;
        }
        synchronized (filter) {
            filter.entries.clear();
            filter.entries.addAll(loaded.events);
            filter.metadata.clear();
            filter.metadata.putAll(loaded.metadata);
            filter.changed();
            filter.lastTime = state.time;
        }
    }

    private static JTextArea createTextArea(LoggingFilter filter) {
        JTextArea text = new JTextArea(12, 50);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 16));
        text.setMargin(new java.awt.Insets(12, 12, 12, 12));
        filter.metadata.putIfAbsent("fontFamily", text.getFont().getFamily());
        filter.metadata.putIfAbsent("fontSize", text.getFont().getSize());
        text.addComponentListener(new java.awt.event.ComponentAdapter() {
            private void capture() {
                synchronized (filter) {
                    JScrollPane pane = (JScrollPane) text.getClientProperty("logScrollPane");
                    javax.swing.JViewport viewport = pane == null ? null : pane.getViewport();
                    Dimension size = viewport == null ? text.getSize() : viewport.getExtentSize();
                    java.awt.Component area = viewport == null ? text : viewport;
                    filter.metadata.put("TextAreaWidth", size.width);
                    filter.metadata.put("TextAreaHeight", size.height);
                    Point location = area.isShowing() ? area.getLocationOnScreen() : area.getLocation();
                    filter.metadata.put("TextAreaX", location.x);
                    filter.metadata.put("TextAreaY", location.y);
                    filter.changed();
                }
            }
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) { capture(); }
            @Override
            public void componentMoved(java.awt.event.ComponentEvent event) { capture(); }
        });
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

    enum EventType {
        SESSION("session", 0), INSERT("insertString", 101), REMOVE("remove", 102),
        REPLACE("replace", 103), CARET("caretUpdate", 104), SCROLL("scrollChange", 107),
        KEY_PRESSED("keyPressed", 207), KEY_RELEASED("keyReleased", 208);
        final String name;
        final int id;
        EventType(String name, int id) { this.name = name; this.id = id; }
        static EventType named(String name) {
            for (EventType type : values()) if (type.name.equals(name)) return type;
            throw new IllegalArgumentException("Unknown event " + name);
        }
    }

    /** Immutable values; recording never formats or parses a log line. */
    static abstract class LogEvent {
        final Instant time;
        final EventType type;
        LogEvent(Instant time, EventType type) { this.time = time; this.type = type; }
        abstract LogEvent at(Instant time);
        String description() { return type.name; }
    }

    static final class SessionEvent extends LogEvent {
        final String initialText;
        SessionEvent(Instant time, String initialText) { super(time, EventType.SESSION); this.initialText = initialText; }
        LogEvent at(Instant time) { return new SessionEvent(time, initialText); }
    }

    static final class EditEvent extends LogEvent {
        final int offset;
        final String removed, inserted; // null inserted is preserved for JSON str:null
        EditEvent(Instant time, EventType type, int offset, String removed, String inserted) {
            super(time, type);
            this.offset = offset; this.removed = removed; this.inserted = inserted;
        }
        String replacement() { return inserted == null ? "" : inserted; }
        void apply(StringBuilder text) { replace(text, removed, replacement()); }
        void undo(StringBuilder text) { replace(text, replacement(), removed); }
        private void replace(StringBuilder text, String expected, String replacement) {
            if (offset < 0 || offset > text.length() - expected.length()
                    || !text.substring(offset, offset + expected.length()).equals(expected)) {
                throw new IllegalArgumentException("Edit does not match document at offset " + offset);
            }
            text.replace(offset, offset + expected.length(), replacement);
        }
        LogEvent at(Instant time) { return new EditEvent(time, type, offset, removed, inserted); }
        String description() { return type.name + " offset=" + offset + " length=" + removed.length(); }
    }

    static final class CaretLogEvent extends LogEvent {
        final int dot, mark;
        CaretLogEvent(Instant time, int dot, int mark) { super(time, EventType.CARET); this.dot = dot; this.mark = mark; }
        LogEvent at(Instant time) { return new CaretLogEvent(time, dot, mark); }
        String description() { return type.name + " dot=" + dot + " mark=" + mark; }
    }

    static final class ScrollLogEvent extends LogEvent {
        final int x, y;
        ScrollLogEvent(Instant time, int x, int y) { super(time, EventType.SCROLL); this.x = x; this.y = y; }
        LogEvent at(Instant time) { return new ScrollLogEvent(time, x, y); }
        String description() { return type.name + " viewX=" + x + " viewY=" + y; }
    }

    static final class KeyLogEvent extends LogEvent {
        final int keyCode;
        final String keyText, keyChar, modifiersText;
        final Integer modifiers, keyLocation;
        KeyLogEvent(Instant time, EventType type, int keyCode, String keyText, String keyChar,
                Integer modifiers, String modifiersText, Integer keyLocation) {
            super(time, type); this.keyCode = keyCode; this.keyText = keyText; this.keyChar = keyChar;
            this.modifiers = modifiers; this.modifiersText = modifiersText; this.keyLocation = keyLocation;
        }
        LogEvent at(Instant time) {
            return new KeyLogEvent(time, type, keyCode, keyText, keyChar, modifiers, modifiersText, keyLocation);
        }
        String description() { return type.name + " keyCode=" + keyCode; }
    }

    static final class SaveSnapshot {
        final List<LogEvent> events;
        final Map<String, Object> metadata;
        final long revision;
        SaveSnapshot(List<LogEvent> events, Map<String, Object> metadata, long revision) {
            this.events = List.copyOf(events);
            this.metadata = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            this.revision = revision;
        }
        void save(Path path) throws IOException {
            Path target = path.toAbsolutePath();
            Path temporary = Files.createTempFile(target.getParent(), ".saved-log-", ".tmp");
            try {
                try (java.io.Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    JsonLog.write(events, metadata, writer);
                    writer.write('\n');
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(temporary); }
        }
    }

    /** Single writer preserves ordering; pending autosaves coalesce to the latest revision per document. */
    static final class BackgroundSaver implements AutoCloseable {
        final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "scriptloglite-save"); thread.setDaemon(true); return thread;
        });
        final Map<LoggingFilter, SaveSnapshot> pending = new LinkedHashMap<>();
        boolean draining, closing;
        final java.util.function.Consumer<Exception> onError;
        BackgroundSaver(java.util.function.Consumer<Exception> onError) { this.onError = onError; }

        synchronized void automatic(LoggingFilter filter) {
            if (closing) throw new IllegalStateException("Save worker is closed");
            synchronized (filter) {
                if (!filter.dirty || filter.entries.isEmpty()) return;
                pending.put(filter, filter.snapshot());
            }
            if (!draining) {
                draining = true;
                writer.execute(this::drain);
            }
        }
        private void drain() {
            while (true) {
                Map<LoggingFilter, SaveSnapshot> batch;
                synchronized (this) {
                    batch = new LinkedHashMap<>(pending);
                    pending.clear();
                }
                for (Map.Entry<LoggingFilter, SaveSnapshot> entry : batch.entrySet()) {
                    LoggingFilter filter = entry.getKey();
                    SaveSnapshot snapshot = entry.getValue();
                    try {
                        synchronized (filter) { if (snapshot.revision <= filter.automaticRevision) continue; }
                        snapshot.save(filter.automaticPath);
                        filter.savedAutomatically(snapshot.revision);
                    } catch (Exception exception) { report(exception); }
                }
                synchronized (this) {
                    if (pending.isEmpty()) { draining = false; return; }
                    if (!closing) {
                        // Yield to explicit Save requests already waiting in the executor.
                        writer.execute(this::drain);
                        return;
                    }
                }
                // At shutdown, drain the remainder without submitting new executor tasks.
            }
        }
        void named(SaveSnapshot snapshot, Path path, java.util.function.Consumer<Exception> completion) {
            writer.execute(() -> {
                Exception failure = null;
                try { snapshot.save(path); } catch (Exception exception) { failure = exception; }
                final Exception result = failure;
                SwingUtilities.invokeLater(() -> completion.accept(result));
            });
        }
        void report(Exception exception) {
            System.err.println("Log save failed: " + exception.getMessage());
            SwingUtilities.invokeLater(() -> onError.accept(exception));
        }
        java.util.concurrent.Future<?> flush() {
            java.util.concurrent.CompletableFuture<Void> complete = new java.util.concurrent.CompletableFuture<>();
            writer.execute(new Runnable() {
                public void run() {
                    synchronized (BackgroundSaver.this) {
                        if (!draining && pending.isEmpty()) complete.complete(null);
                        else writer.execute(this);
                    }
                }
            });
            return complete;
        }
        public void close() {
            synchronized (this) { closing = true; writer.shutdown(); }
            try {
                while (!writer.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) { }
            } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        }
    }

    public static class LoggingFilter extends DocumentFilter {
        final List<LogEvent> entries = new ArrayList<>();
        final Map<String, Object> metadata = new LinkedHashMap<>();
        boolean dirty = true;
        long revision, automaticRevision = -1;
        Path automaticPath = workingDirectory().resolve("unassigned.json");
        Runnable onRecord = () -> { };
        boolean restoring;
        Instant lastTime = Instant.MIN;

        public LoggingFilter() { metadata.put("startTime", System.nanoTime()); }
        synchronized Instant timestamp() {
            Instant now = Instant.now();
            return now.isBefore(lastTime) ? lastTime : now;
        }
        synchronized void append(LogEvent event) {
            if (restoring) return;
            if (event.time.isBefore(lastTime)) event = event.at(lastTime);
            entries.add(event);
            lastTime = event.time;
            changed();
            onRecord.run();
        }
        synchronized void changed() { dirty = true; revision++; }
        void startSession(String text) { append(new SessionEvent(timestamp(), text)); }
        synchronized ReplayLog replay() {
            ReplayLog result = new ReplayLog(entries);
            result.metadata.putAll(metadata);
            return result;
        }
        synchronized SaveSnapshot snapshot() { return new SaveSnapshot(entries, metadata, revision); }
        void save(Path path) throws IOException {
            if (path.toAbsolutePath().normalize().equals(automaticPath.toAbsolutePath().normalize())
                    || (Files.exists(path) && Files.exists(automaticPath) && Files.isSameFile(path, automaticPath))) {
                throw new IOException("Choose another filename than the active automatic log");
            }
            snapshot().save(path);
        }
        // Synchronous only for command-line demo/tests, never called by GUI save actions.
        void saveAutomatic() throws IOException {
            SaveSnapshot snapshot;
            synchronized (this) { if (!dirty || entries.isEmpty()) return; snapshot = snapshot(); }
            snapshot.save(automaticPath);
            savedAutomatically(snapshot.revision);
        }
        synchronized void savedAutomatically(long savedRevision) {
            automaticRevision = Math.max(automaticRevision, savedRevision);
            if (revision == savedRevision) dirty = false;
        }
        @Override
        public void insertString(FilterBypass bypass, int offset, String string, AttributeSet attributes)
                throws BadLocationException {
            // Validate before recording an edit that Swing would reject.
            bypass.getDocument().getText(offset, 0);
            append(new EditEvent(timestamp(), EventType.INSERT, offset, "", string));
            super.insertString(bypass, offset, string, attributes);
        }
        @Override
        public void replace(FilterBypass bypass, int offset, int length, String text, AttributeSet attributes)
                throws BadLocationException {
            String old = bypass.getDocument().getText(offset, length);
            append(new EditEvent(timestamp(), EventType.REPLACE, offset, old, text));
            super.replace(bypass, offset, length, text, attributes);
        }
        @Override
        public void remove(FilterBypass bypass, int offset, int length) throws BadLocationException {
            String old = bypass.getDocument().getText(offset, length);
            append(new EditEvent(timestamp(), EventType.REMOVE, offset, old, null));
            super.remove(bypass, offset, length);
        }
        public void recordCaret(CaretEvent event) {
            append(new CaretLogEvent(timestamp(), event.getDot(), event.getMark()));
        }
        public void recordKey(String method, KeyEvent event) {
            append(new KeyLogEvent(timestamp(), EventType.named(method), event.getKeyCode(),
                    KeyEvent.getKeyText(event.getKeyCode()), event.getKeyChar() == KeyEvent.CHAR_UNDEFINED
                    ? "undefined" : String.valueOf(event.getKeyChar()), event.getModifiersEx(),
                    KeyEvent.getModifiersExText(event.getModifiersEx()), event.getKeyLocation()));
        }
        static String quote(String text) { return text == null ? "null" : Json.quote(text); }
    }

    /** Transient state returned for display/testing; full text is never retained per event. */
    static class ReplayState {
        final Instant time;
        final String text;
        final int dot, mark, scrollX, scrollY;
        final String description;
        final boolean edit;
        ReplayState(LogEvent event, String text, ViewState view) {
            time = event.time; this.text = text; dot = view.dot; mark = view.mark;
            scrollX = view.x; scrollY = view.y; description = event.description(); edit = event instanceof EditEvent;
        }
    }
    static final class ViewState {
        final int dot, mark, x, y;
        ViewState(int dot, int mark, int x, int y) { this.dot = dot; this.mark = mark; this.x = x; this.y = y; }
    }

    static class ReplayLog {
        final List<LogEvent> events;
        final Map<String, Object> metadata = new LinkedHashMap<>();
        final List<ViewState> views = new ArrayList<>();
        final List<Integer> boundaries = new ArrayList<>();
        final java.util.NavigableMap<Integer, String> checkpoints = new java.util.TreeMap<>();
        final String initialText, finalText;
        // Compatibility convenience: get() materializes one state, not an array of text snapshots.
        final List<ReplayState> states = new java.util.AbstractList<ReplayState>() {
            public int size() { return events.size(); }
            public ReplayState get(int index) { ReplayCursor cursor = new ReplayCursor(ReplayLog.this); cursor.seek(index); return cursor.state(); }
        };
        static final Pattern FIELD = Pattern.compile("(\\w+)=(\"(?:\\\\.|[^\"\\\\])*+\"|\\S+)");

        ReplayLog(List<LogEvent> input) {
            if (input.isEmpty() || !(input.get(0) instanceof SessionEvent)) {
                throw new IllegalArgumentException("Missing session");
            }
            events = List.copyOf(input);
            initialText = ((SessionEvent) events.get(0)).initialText;
            StringBuilder text = new StringBuilder(initialText);
            int dot = 0, mark = 0, x = 0, y = 0, edits = 0;
            Instant previous = events.get(0).time;
            checkpoints.put(0, initialText);
            boundaries.add(0);
            for (int i = 0; i < events.size(); i++) {
                LogEvent event = events.get(i);
                try {
                    if (event.time.isBefore(previous)) throw new IllegalArgumentException("timestamps run backwards");
                    previous = event.time;
                    if (event instanceof EditEvent) {
                        EditEvent edit = (EditEvent) event;
                        if (edit.type == EventType.INSERT && !edit.removed.isEmpty()) {
                            throw new IllegalArgumentException("insertString cannot remove text");
                        }
                        edit.apply(text);
                        dot = Math.min(dot, text.length()); mark = Math.min(mark, text.length());
                        if (++edits % 256 == 0) checkpoints.put(i, text.toString());
                        if (boundaries.size() > 1) boundaries.set(boundaries.size() - 1, i - 1);
                        boundaries.add(i);
                    } else if (event instanceof CaretLogEvent) {
                        CaretLogEvent caret = (CaretLogEvent) event;
                        dot = caret.dot; mark = caret.mark;
                        if (dot < 0 || mark < 0 || dot > text.length() || mark > text.length()) {
                            throw new IllegalArgumentException("caret outside document");
                        }
                    } else if (event instanceof ScrollLogEvent) {
                        ScrollLogEvent scroll = (ScrollLogEvent) event; x = scroll.x; y = scroll.y;
                        if (x < 0 || y < 0) throw new IllegalArgumentException("negative scroll position");
                    } else if (i != 0 && event instanceof SessionEvent) {
                        throw new IllegalArgumentException("unexpected session marker");
                    }
                    views.add(new ViewState(dot, mark, x, y));
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("Invalid replay event " + (i + 1) + ": " + exception.getMessage(), exception);
                }
            }
            finalText = text.toString();
            if (boundaries.size() > 1) boundaries.set(boundaries.size() - 1, events.size() - 1);
        }

        static ReplayLog parse(List<?> input) {
            if (!input.isEmpty() && input.get(0) instanceof LogEvent) {
                List<LogEvent> events = new ArrayList<>();
                for (Object item : input) events.add((LogEvent) item);
                return new ReplayLog(events);
            }
            List<LogEvent> events = new ArrayList<>();
            for (Object item : input) events.add(legacyEvent((String) item));
            return new ReplayLog(events);
        }
        static LogEvent legacyEvent(String line) {
            String[] parts = line.split(" ", 3);
            Instant time = Instant.parse(parts[0]);
            EventType type = EventType.named(parts[1]);
            Map<String, String> fields = new HashMap<>();
            Matcher matcher = FIELD.matcher(parts.length > 2 ? parts[2] : "");
            while (matcher.find()) fields.put(matcher.group(1), decode(matcher.group(2)));
            switch (type) {
                case SESSION: return new SessionEvent(time, required(fields, "initialText"));
                case INSERT: case REMOVE: case REPLACE:
                    String old = required(fields, "oldText");
                    if (old.length() != Integer.parseInt(required(fields, "length"))) {
                        throw new IllegalArgumentException("oldText length mismatch");
                    }
                    return new EditEvent(time, type, Integer.parseInt(required(fields, "offset")), old,
                            type == EventType.REMOVE ? null : fields.get("text"));
                case CARET: return new CaretLogEvent(time, Integer.parseInt(required(fields, "dot")), Integer.parseInt(required(fields, "mark")));
                case SCROLL: return new ScrollLogEvent(time, Integer.parseInt(required(fields, "x")), Integer.parseInt(required(fields, "y")));
                default: return new KeyLogEvent(time, type, Integer.parseInt(required(fields, "keyCode")),
                        fields.get("keyText"), fields.get("keyChar"), optionalInteger(fields, "modifiers"),
                        fields.get("modifiersText"), optionalInteger(fields, "keyLocation"));
            }
        }
        static Integer optionalInteger(Map<String, String> fields, String key) {
            return fields.containsKey(key) ? Integer.valueOf(fields.get(key)) : null;
        }
        static String required(Map<String, String> fields, String key) {
            String value = fields.get(key);
            if (value == null) throw new IllegalArgumentException("missing " + key);
            return value;
        }
        static String decode(String value) { return value.startsWith("\"") ? (String) new Json(value).parse() : value.equals("null") ? null : value; }
        static ReplayLog load(Path path, int session) throws Exception {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.stripLeading().startsWith("[")) {
                if (session != -1 && session != 1) throw new IllegalArgumentException("JSON holds one session");
                return JsonLog.load(content);
            }
            List<List<String>> sessions = new ArrayList<>();
            for (String line : content.split("\\R")) {
                if (line.isBlank()) continue;
                if (line.matches("\\S+ session .*")) sessions.add(new ArrayList<>());
                if (!sessions.isEmpty()) sessions.get(sessions.size() - 1).add(line);
            }
            if (sessions.isEmpty()) throw new IllegalArgumentException("Log has no session marker");
            int index = session == -1 ? sessions.size() - 1 : session - 1;
            if (index < 0 || index >= sessions.size()) throw new IllegalArgumentException("Invalid session number");
            return parse(sessions.get(index));
        }
        int nextEdit(int position) { for (int boundary : boundaries) if (boundary > position) return boundary; return position; }
        int previousEdit(int position) { for (int i = boundaries.size() - 1; i >= 0; i--) if (boundaries.get(i) < position) return boundaries.get(i); return 0; }
    }

    /** One mutable text buffer: edits apply forward and undo in reverse order. */
    static final class ReplayCursor {
        final ReplayLog log;
        final StringBuilder text;
        String cachedText;
        int position;
        ReplayCursor(ReplayLog log) {
            this.log = log; text = new StringBuilder(log.initialText); cachedText = log.initialText;
        }
        void seek(int target) {
            if (target < 0 || target >= log.events.size()) throw new IndexOutOfBoundsException("Replay position " + target);
            Map.Entry<Integer, String> checkpoint = log.checkpoints.floorEntry(target);
            if (Math.abs(target - position) > target - checkpoint.getKey()) {
                text.setLength(0); text.append(checkpoint.getValue());
                cachedText = checkpoint.getValue(); position = checkpoint.getKey();
            }
            while (position < target) {
                LogEvent event = log.events.get(++position);
                if (event instanceof EditEvent) { ((EditEvent) event).apply(text); cachedText = null; }
            }
            while (position > target) {
                LogEvent event = log.events.get(position--);
                if (event instanceof EditEvent) { ((EditEvent) event).undo(text); cachedText = null; }
            }
        }
        ReplayState state() {
            if (cachedText == null) cachedText = text.toString();
            return new ReplayState(log.events.get(position), cachedText, log.views.get(position));
        }
    }

    static final class ReplayCaret extends javax.swing.text.DefaultCaret {
        @Override public void focusLost(java.awt.event.FocusEvent event) { setVisible(true); setSelectionVisible(true); }
        @Override protected void adjustVisibility(java.awt.Rectangle rectangle) { } // Only recorded scrolling moves the view.
    }

    static final class SpacedTextArea extends JTextArea {
        double spacing = 1.0;
        @Override public java.awt.FontMetrics getFontMetrics(java.awt.Font font) {
            java.awt.FontMetrics base = super.getFontMetrics(font);
            double factor = spacing > 0 && Double.isFinite(spacing) ? spacing : 1.0;
            if (factor == 1.0) return base;
            return new java.awt.FontMetrics(font) {
                @Override public int getHeight() { return Math.max(1, (int) Math.ceil(base.getHeight() * factor)); }
                @Override public int getAscent() { return base.getAscent(); }
                @Override public int getDescent() { return base.getDescent(); }
                @Override public int getLeading() { return getHeight() - getAscent() - getDescent(); }
                @Override public int charWidth(char c) { return base.charWidth(c); }
                @Override public int charWidth(int c) { return base.charWidth(c); }
                @Override public int stringWidth(String value) { return base.stringWidth(value); }
                @Override public int charsWidth(char[] values, int offset, int length) { return base.charsWidth(values, offset, length); }
            };
        }
    }

    static class ReplayWindow {
        final ReplayLog log;
        final ReplayCursor cursor;
        final SpacedTextArea text = new SpacedTextArea();
        final JScrollPane scroll = new JScrollPane(text, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        final javax.swing.JSlider timeline = new javax.swing.JSlider() {
            @Override public Point getMousePosition() throws java.awt.HeadlessException {
                return java.awt.GraphicsEnvironment.isHeadless() ? null : super.getMousePosition();
            }
        };
        final JButton stop = new JButton("Stop");
        final JButton fastForward = new JButton("Fast forward ×4");
        final JButton beginning = new JButton("Beginning");
        final JButton end = new JButton("End");
        final long durationNanos;
        final int recordedWidth, recordedHeight, recordedX, recordedY;
        boolean updatingTimeline;
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
            cursor = new ReplayCursor(log);
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setMargin(new java.awt.Insets(12, 12, 12, 12));
            String family = String.valueOf(log.metadata.getOrDefault("fontFamily", java.awt.Font.SANS_SERIF));
            int size = metadataInt("fontSize", 16);
            text.setFont(new java.awt.Font(family, java.awt.Font.PLAIN, Math.max(1, size)));
            Object spacing = log.metadata.get("lineSpacing");
            text.spacing = spacing instanceof Number ? Math.max(0.1, ((Number) spacing).doubleValue()) : 1.0;
            text.setCaret(new ReplayCaret());
            text.getCaret().setBlinkRate(0);
            text.getCaret().setVisible(true);
            text.getCaret().setSelectionVisible(true);
            recordedWidth = Math.max(1, metadataInt("TextAreaWidth", 720));
            recordedHeight = Math.max(1, metadataInt("TextAreaHeight", 450));
            recordedX = metadataInt("TextAreaX", 0);
            recordedY = metadataInt("TextAreaY", 0);
            long eventDuration = offset(log.events.size() - 1);
            long headerDuration = log.metadata.get("endTime") instanceof Number && log.metadata.get("startTime") instanceof Number
                    ? ((Number) log.metadata.get("endTime")).longValue() - ((Number) log.metadata.get("startTime")).longValue() : 0;
            durationNanos = Math.max(eventDuration, Math.max(0, headerDuration));
            setupTimeline();
            stop.addActionListener(event -> pause());
            fastForward.addActionListener(event -> { speed.setSelectedItem("4"); start(); });
            beginning.addActionListener(event -> seek(0));
            end.addActionListener(event -> seekTime(durationNanos));
            speed.setSelectedItem("1");
            timer = new Timer(10, event -> tick());
            play.addActionListener(event -> {
                if (timer.isRunning()) { tick(); pause(); }
                else start();
            });
            // Accrue elapsed time at the previous speed before changing it.
            speed.addActionListener(event -> {
                if (timer.isRunning()) tick();
                playbackSpeed = multiplier();
            });
        }

        int metadataInt(String key, int fallback) {
            Object value = log.metadata.get(key);
            return value instanceof Number && ((Number) value).longValue() != 0
                    ? Math.toIntExact(((Number) value).longValue()) : fallback;
        }
        long offset(int index) { return Duration.between(log.events.get(0).time, log.events.get(index).time).toNanos(); }
        long currentTime() { return Math.min(durationNanos, offset(position) + (long) elapsedNanos); }
        void start() {
            if (currentTime() >= durationNanos) return;
            lastTick = System.nanoTime(); timer.start(); play.setText("Pause");
        }
        void seekTime(long nanos) {
            pause();
            nanos = Math.max(0, Math.min(durationNanos, nanos));
            int low = 0, high = log.events.size();
            while (low + 1 < high) {
                int middle = (low + high) >>> 1;
                if (offset(middle) <= nanos) low = middle; else high = middle;
            }
            position = low;
            elapsedNanos = nanos - offset(position);
            render();
        }
        void setupTimeline() {
            timeline.setMinimum(0);
            timeline.setMaximum((int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.ceil(durationNanos / 1e6))));
            timeline.setValue(0);
            double seconds = durationNanos / 1e9;
            double rough = Math.max(0.001, seconds / 8);
            double magnitude = Math.pow(10, Math.floor(Math.log10(rough)));
            double step = magnitude * (rough / magnitude <= 1 ? 1 : rough / magnitude <= 2 ? 2 : rough / magnitude <= 5 ? 5 : 10);
            java.util.Hashtable<Integer, JLabel> labels = new java.util.Hashtable<>();
            for (double second = 0; second <= seconds + 1e-9; second += step) {
                int value = durationNanos == 0 ? 0 : (int) Math.round(second * 1e9 / durationNanos * timeline.getMaximum());
                labels.put(value, new JLabel(String.format(java.util.Locale.ROOT, "%s (s)",
                        String.format(java.util.Locale.ROOT, "%." + Math.max(0, (int) Math.ceil(-Math.log10(step))) + "f", second).trim())));
            }
            timeline.setMajorTickSpacing(Math.max(1, durationNanos == 0 ? 1
                    : (int) Math.round(step * 1e9 / durationNanos * timeline.getMaximum())));
            timeline.setLabelTable(labels); timeline.setPaintTicks(true); timeline.setPaintLabels(true);
            timeline.addChangeListener(event -> {
                if (!updatingTimeline) seekTime((long) ((double) timeline.getValue() / timeline.getMaximum() * durationNanos));
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
                double gap = Duration.between(log.events.get(position).time,
                        log.events.get(position + 1).time).toNanos();
                if (elapsedNanos < gap) break;
                elapsedNanos -= gap;
                position++;
            }
            render();
            if (currentTime() >= durationNanos) { elapsedNanos = durationNanos - offset(position); pause(); }
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
            cursor.seek(position);
            ReplayState state = cursor.state();
            if (!text.getText().equals(state.text)) text.setText(state.text);
            text.setCaretPosition(state.mark);
            text.moveCaretPosition(state.dot);
            text.getCaret().setVisible(true);
            text.getCaret().setSelectionVisible(true);
            updatingTimeline = true;
            timeline.setValue(durationNanos == 0 ? 0 : (int) Math.round((double) currentTime() / durationNanos * timeline.getMaximum()));
            updatingTimeline = false;
            applyScroll(scroll, state.scrollX, state.scrollY);
            status.setText("Event " + position + "/" + (log.states.size() - 1)
                    + " — " + state.description);
            status.setToolTipText(state.time + " " + state.description);
        }

        JPanel panel() {
            JButton forward = new JButton("Next edit");
            forward.addActionListener(event -> seek(log.nextEdit(position)));
            JButton backward = new JButton("Previous edit");
            backward.addActionListener(event -> seek(log.previousEdit(position)));

            JPanel controls = new JPanel();
            controls.add(backward);
            controls.add(forward);
            controls.add(play);
            controls.add(new JLabel("Speed ×"));
            controls.add(speed);
            controls.add(stop);
            controls.add(fastForward);
            controls.add(beginning);
            controls.add(end);
            JPanel frame = new JPanel(new java.awt.BorderLayout(0, 8));
            status.setBorder(BorderFactory.createEmptyBorder(8, 10, 0, 10));
            frame.add(status, java.awt.BorderLayout.NORTH);
            JPanel canvas = new JPanel(null);
            int x = 0, y = 0; // Recorded screen coordinates are retained but not applied yet.
            int scrollbarWidth = scroll.getVerticalScrollBar().getPreferredSize().width;
            java.awt.Insets border = scroll.getInsets();
            scroll.setBounds(x - border.left, y - border.top, recordedWidth + scrollbarWidth + border.left + border.right,
                    recordedHeight + border.top + border.bottom);
            canvas.add(scroll);
            canvas.setPreferredSize(new Dimension(x + scroll.getWidth(), y + scroll.getHeight()));
            JScrollPane stage = new JScrollPane(canvas, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            stage.setBorder(BorderFactory.createEmptyBorder());
            frame.add(stage, java.awt.BorderLayout.CENTER);
            JPanel navigation = new JPanel(new java.awt.BorderLayout());
            JPanel timelinePadding = new JPanel(new java.awt.BorderLayout());
            timelinePadding.setBorder(BorderFactory.createEmptyBorder(12, 28, 10, 28));
            timelinePadding.add(timeline, java.awt.BorderLayout.CENTER);
            navigation.add(timelinePadding, java.awt.BorderLayout.NORTH);
            navigation.add(controls, java.awt.BorderLayout.SOUTH);
            frame.add(navigation, java.awt.BorderLayout.SOUTH);
            render();
            return frame;
        }
    }

    static void testReplay() throws Exception {
        Path original = workingRoot;
        Path temporary = Files.createTempDirectory("scriptloglite-working-directory-test");
        workingRoot = temporary;
        try { testReplayChecks(); }
        finally {
            workingRoot = original;
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }

    static void testReplayChecks() throws Exception {
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
        testScroll();
        testJson();
        testTabs();
        testReversibleHistory();
        testBackgroundSaving();
        testDirectoryHistory();
        testRecordingPaths();
        testReplayControls();
        testThemes();
        System.out.println("Replay self-test passed");
    }

    static void testRecordingPaths() throws Exception {
        Path previous = workingRoot;
        workingRoot = previous.resolve("allocation");
        java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            check(!Files.exists(workingRoot), "new working directory starts absent");
            check(Files.isDirectory(workingDirectory()), "working directory is created");
            RecordingVariables defaults = new RecordingVariables("expr", "_", "subj");
            java.time.LocalDate day = java.time.LocalDate.of(2026, 10, 8);
            Path first = allocateRecording(defaults, day);
            check(first.equals(workingRoot.resolve("expr_subj/2026-10-08_1/expr_subj_sll_1.json")),
                    "default automatic log hierarchy");
            Files.writeString(first, "existing recording");
            Path second = allocateRecording(defaults, day);
            check(second.getFileName().toString().equals("expr_subj_sll_1.json"), "new folders start with file index one");
            Path collision = nextLogFile(first.getParent(), defaults.prefix());
            check(collision.getFileName().toString().equals("expr_subj_sll_2.json"), "existing file increments file index");
            Files.writeString(collision, "second file");
            check(nextLogFile(first.getParent(), defaults.prefix()).getFileName().toString().equals("expr_subj_sll_3.json"),
                    "file index skips occupied names in the same directory");
            Path third = allocateRecording(new RecordingVariables("expr", "_", "subj"), day.plusDays(1));
            check(third.getParent().getFileName().toString().equals("2026-10-09_3"),
                    "numbering persists across instances and dates");
            List<java.util.concurrent.Future<Path>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(threads.submit(() -> allocateRecording(defaults, day.plusDays(1))));
            java.util.Set<Path> reserved = new java.util.HashSet<>();
            for (java.util.concurrent.Future<Path> future : futures) reserved.add(future.get());
            check(reserved.size() == 4, "concurrent recordings reserve distinct directories");
            check(Files.readString(first).equals("existing recording"), "existing recordings are preserved");
            Path custom = allocateRecording(new RecordingVariables("study", "-control-", "p01"), day);
            check(custom.getFileName().toString().equals("study-control-p01_sll_1.json"),
                    "experiment/condition/subject control naming and separate numbering");
        } finally {
            threads.shutdown();
            workingRoot = previous;
        }
    }

    static void testReplayControls() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Instant start = Instant.EPOCH;
            ReplayLog log = new ReplayLog(List.of(new SessionEvent(start, ""),
                    new EditEvent(start.plusSeconds(1), EventType.INSERT, 0, "", "Hello world"),
                    new CaretLogEvent(start.plusSeconds(2), 8, 2),
                    new EditEvent(start.plusSeconds(3), EventType.REMOVE, 5, " world", null)));
            log.metadata.putAll(Map.of("fontFamily", "Monospaced", "fontSize", 24, "lineSpacing", 1.5,
                    "TextAreaWidth", 320, "TextAreaHeight", 180, "TextAreaX", 30, "TextAreaY", 40,
                    "startTime", 0L, "endTime", 5_000_000_000L));
            ReplayWindow window = new ReplayWindow(log);
            window.panel();
            window.scroll.doLayout();
            check(window.scroll.getViewport().getExtentSize().equals(new Dimension(320, 180)),
                    "recorded editor dimensions");
            check(window.scroll.getX() + window.scroll.getViewport().getX() == 0
                    && window.scroll.getY() + window.scroll.getViewport().getY() == 0, "replay editor starts in upper-left corner");
            check(window.text.getFont().getSize() == 24 && window.text.getFont().getName().equals("Monospaced")
                    && window.text.spacing == 1.5, "recorded font and line spacing");
            check(window.scroll.getVerticalScrollBarPolicy() == JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
                    && window.scroll.getHorizontalScrollBarPolicy() == JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                    "replay scrollbar policy");
            window.seekTime(2_500_000_000L);
            check(window.position == 2 && window.text.getCaret().getDot() == 8
                    && window.text.getCaret().getMark() == 2, "seek restores selection");
            ((ReplayCaret) window.text.getCaret()).focusLost(new java.awt.event.FocusEvent(
                    window.text, java.awt.event.FocusEvent.FOCUS_LOST));
            check(window.text.getCaret().isVisible() && window.text.getCaret().isSelectionVisible(),
                    "caret and selection remain visible without focus");
            window.timeline.setValue(1500);
            check(window.position == 1 && !window.timer.isRunning()
                    && window.currentTime() == 1_500_000_000L, "slider seeks immediately between events");
            window.fastForward.doClick();
            check(window.playbackSpeed == 4 && window.timer.isRunning(), "fast forward starts at 4x");
            window.stop.doClick();
            check(!window.timer.isRunning(), "stop halts playback");
            window.end.doClick();
            check(window.position == 3 && window.currentTime() == 5_000_000_000L
                    && window.timeline.getValue() == window.timeline.getMaximum(), "end includes recorded idle tail");
            window.beginning.doClick();
            check(window.position == 0 && window.currentTime() == 0 && window.text.getText().isEmpty(),
                    "beginning restores initial state");
            check(window.timeline.getPaintTicks() && window.timeline.getPaintLabels()
                    && window.timeline.getLabelTable().size() <= 12, "readable time tick labels");
        });
    }

    static void testDirectoryHistory() throws Exception {
        Path root = Files.createTempDirectory("scriptloglite-directory-test");
        Path open = Files.createDirectory(root.resolve("open"));
        Path save = Files.createDirectory(root.resolve("save"));
        Path settings = root.resolve("directories.properties");
        try {
            DirectoryHistory history = new DirectoryHistory(settings);
            history.remember("open", open.resolve("opened.json"));
            history.remember("save", save.resolve("saved.json"));
            DirectoryHistory reopened = new DirectoryHistory(settings);
            check(reopened.directory("open").equals(open) && reopened.directory("save").equals(save),
                    "independent open/save directories survive restart");
            SwingUtilities.invokeAndWait(() -> {
                TabbedApplication app = new TabbedApplication(reopened);
                check(app.openChooser.getCurrentDirectory().toPath().equals(open)
                        && app.saveChooser.getCurrentDirectory().toPath().equals(save),
                        "choosers use their own remembered directories");
            });
            Files.delete(open);
            check(new DirectoryHistory(settings).directory("open").equals(workingDirectory()),
                    "missing remembered directory falls back safely");
        } finally {
            Files.deleteIfExists(settings);
            Files.deleteIfExists(open);
            Files.deleteIfExists(save);
            Files.deleteIfExists(root);
        }
    }

    static void testReversibleHistory() {
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        List<LogEvent> events = new ArrayList<>();
        events.add(new SessionEvent(time, ""));
        for (int i = 0; i < 2048; i++) {
            events.add(new EditEvent(time.plusNanos(i * 2L + 1), EventType.INSERT, i, "", "x"));
            events.add(new CaretLogEvent(time.plusNanos(i * 2L + 2), i + 1, i + 1));
        }
        ReplayLog replay = new ReplayLog(events);
        check(replay.checkpoints.size() == 9, "sparse checkpoints rather than per-edit text snapshots");
        ReplayCursor cursor = new ReplayCursor(replay);
        for (int i = 1; i < events.size(); i++) {
            cursor.seek(i);
            check(cursor.text.length() == (i + 1) / 2, "incremental forward edits");
        }
        for (int i = events.size() - 2; i >= 0; i--) {
            cursor.seek(i);
            check(cursor.text.length() == (i + 1) / 2, "incremental reverse edits");
        }
        cursor.seek(events.size() - 1);
        check(cursor.state().text.equals("x".repeat(2048)), "seek across checkpoints");
        StringBuilder text = new StringBuilder("A😀BC");
        EditEvent replacement = new EditEvent(time, EventType.REPLACE, 1, "😀B", "\n\"\\");
        replacement.apply(text);
        check(text.toString().equals("A\n\"\\C"), "reversible Unicode replacement");
        replacement.undo(text);
        check(text.toString().equals("A😀BC"), "replacement inverse");
        EditEvent remove = new EditEvent(time, EventType.REMOVE, 1, "😀", null);
        remove.apply(text); remove.undo(text);
        check(text.toString().equals("A😀BC"), "remove inverse");
    }

    static void testBackgroundSaving() throws Exception {
        Path directory = Files.createTempDirectory("background-log-test");
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch busy = new java.util.concurrent.CountDownLatch(1);
        TabbedApplication[] application = new TabbedApplication[1];
        DocumentTab[] document = new DocumentTab[1];
        int[] capturedEntries = new int[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                TabbedApplication app = new TabbedApplication(new DirectoryHistory(directory.resolve("directories.properties")));
                application[0] = app;
                DocumentTab tab = app.addDocument(null, null);
                document[0] = tab;
                tab.filter.automaticPath = directory.resolve("automatic.json");
                tab.savedPath = directory.resolve("named.json");
                app.saver.writer.execute(() -> {
                    check(!SwingUtilities.isEventDispatchThread(), "save worker is off the EDT");
                    busy.countDown();
                    try { gate.await(); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                });
            });
            check(busy.await(5, java.util.concurrent.TimeUnit.SECONDS), "worker started");
            SwingUtilities.invokeAndWait(() -> {
                TabbedApplication app = application[0];
                DocumentTab tab = document[0];
                tab.text.setText("snapshot");
                check(tab.filter.entries.stream().allMatch(event -> event instanceof LogEvent), "typed recording");
                capturedEntries[0] = tab.filter.entries.size();
                check(app.save(tab, false), "named save queued without blocking EDT");
                tab.text.append(" then edited");
                app.saver.automatic(tab.filter);
                for (int i = 0; i < 20; i++) {
                    tab.text.append("!");
                    app.saver.automatic(tab.filter);
                }
                synchronized (app.saver) {
                    check(app.saver.pending.size() == 1, "autosaves coalesce per document");
                }
                check(tab.saving, "manual save pending while EDT keeps editing");
            });
            gate.countDown();
            application[0].saver.flush().get(10, java.util.concurrent.TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {
                DocumentTab tab = document[0];
                check(!tab.saving && tab.savedEntries == capturedEntries[0], "saved revision matches snapshot");
                check(tab.title.endsWith(" *"), "later edits remain unsaved");
                check(!tab.filter.dirty, "latest automatic revision persisted");
            });
            check(ReplayLog.load(directory.resolve("named.json"), -1).finalText.equals("snapshot"),
                    "named save uses immutable snapshot");
            String latest = "snapshot then edited" + "!".repeat(20);
            check(ReplayLog.load(directory.resolve("automatic.json"), -1).finalText.equals(latest),
                    "coalesced automatic save contains latest edits");

            String previousFile = Files.readString(directory.resolve("named.json"));
            SaveSnapshot invalid = new SaveSnapshot(List.of(new SessionEvent(Instant.EPOCH, ""),
                    new EditEvent(Instant.EPOCH, EventType.REMOVE, 0, "missing", null)), Map.of(), 0);
            java.util.concurrent.CompletableFuture<Exception> failed = new java.util.concurrent.CompletableFuture<>();
            application[0].saver.named(invalid, directory.resolve("named.json"), exception -> {
                check(SwingUtilities.isEventDispatchThread(), "save completion runs on EDT");
                failed.complete(exception);
            });
            check(failed.get(10, java.util.concurrent.TimeUnit.SECONDS) != null, "background save failure reported");
            check(Files.readString(directory.resolve("named.json")).equals(previousFile),
                    "failed background save preserves existing JSON");
            try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                check(files.noneMatch(path -> path.getFileName().toString().startsWith(".saved-log-")),
                        "failed save removes temporary files");
            }
            SwingUtilities.invokeAndWait(() -> {
                document[0].savedEntries = document[0].filter.entries.size();
                application[0].closeDocument(document[0]);
            });
        } finally {
            gate.countDown();
            if (application[0] != null) application[0].saver.close();
            try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                for (Path file : (Iterable<Path>) files::iterator) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        }
        System.out.println("Typed events, reversible history, and background saving checks passed");
    }

    static void testThemes() throws Exception {
        List<Theme> themes = new ArrayList<>();
        themes.add(Theme.NIMBUS);
        try {
            Class.forName(Theme.LIGHT.className);
            Class.forName(Theme.DARK.className);
            themes.add(Theme.LIGHT);
            themes.add(Theme.DARK);
        } catch (ClassNotFoundException exception) {
            System.out.println("Optional FlatLaf checks skipped: dependency not on classpath.");
        }
        themes.add(Theme.NIMBUS);
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.LookAndFeel original = javax.swing.UIManager.getLookAndFeel();
            try {
                TabbedApplication app = new TabbedApplication();
                DocumentTab document = app.addDocument(null, null);
                document.text.setText("Theme switching preserves the document");
                document.text.setCaretPosition(2);
                document.text.moveCaretPosition(12);
                List<LogEvent> before = new ArrayList<>(document.filter.entries);
                ReplayWindow replay = new ReplayWindow(ReplayLog.parse(before));
                JPanel replayPanel = replay.panel();
                app.replays.put(replayPanel, replay);
                app.addTab(replayPanel, "Replay");
                replay.seek(replay.log.states.size() - 1);
                int position = replay.position;
                for (Theme theme : themes) {
                    app.changeTheme(theme);
                    check(javax.swing.UIManager.getLookAndFeel().getClass().getName().equals(theme.className),
                            "Look and feel installed: " + theme.label);
                    check(app.themeChoices.get(theme).isSelected(), "theme menu selection");
                    check(document.text.getText().equals("Theme switching preserves the document")
                            && document.text.getCaret().getDot() == 12
                            && document.text.getCaret().getMark() == 2, "theme preserves text and selection");
                    check(document.filter.entries.equals(before), "theme creates no log events");
                    check(replay.position == position, "theme preserves replay position");
                }
                app.closeTab(replayPanel);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            } finally {
                try { javax.swing.UIManager.setLookAndFeel(original); }
                catch (Exception exception) { throw new RuntimeException(exception); }
            }
        });
        System.out.println("Theme switching checks passed (Nimbus included)");
    }

    static void testTabs() throws Exception {
        Path directory = Files.createTempDirectory("mdi-document-test");
        BackgroundSaver[] background = new BackgroundSaver[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    TabbedApplication app = new TabbedApplication();
                    background[0] = app.saver;
                    app.tabs.setSize(1000, 700);
                    DocumentTab first = app.addDocument(null, null);
                    DocumentTab second = app.addDocument(null, null);
                    first.filter.automaticPath = directory.resolve("first-auto.json");
                    second.filter.automaticPath = directory.resolve("second-auto.json");
                    first.text.setText("First document");
                    second.text.setText("Second document");
                    app.activate(first);
                    check(app.activeDocument() == first, "active document selection");
                    first.insert();
                    check(first.text.getText().endsWith("Hello")
                            && second.text.getText().equals("Second document"), "document editing isolation");
                    first.filter.saveAutomatic();
                    second.filter.saveAutomatic();
                    check(ReplayLog.load(first.filter.automaticPath, -1).states.get(
                            first.filter.entries.size() - 1).text.equals(first.text.getText()),
                            "first automatic log");
                    check(ReplayLog.load(second.filter.automaticPath, -1).states.get(
                            second.filter.entries.size() - 1).text.equals(second.text.getText()),
                            "second automatic log");
                    Path saved = directory.resolve("saved.json");
                    first.filter.save(saved);
                    DocumentTab reopened = app.addDocument(ReplayLog.load(saved, -1), saved);
                    reopened.filter.automaticPath = directory.resolve("reopened-auto.json");
                    check(reopened.text.getText().equals(first.text.getText()) && app.documents.size() == 3,
                            "open adds a document without replacing other documents");
                    reopened.text.append(" continued");
                    check(!first.text.getText().endsWith("continued"), "reopened log is independent");
                    ReplayWindow replay = new ReplayWindow(ReplayLog.parse(first.filter.entries));
                    JPanel replayPanel = replay.panel();
                    app.replays.put(replayPanel, replay);
                    app.addTab(replayPanel, "Replay — First document");
                    check(app.activeDocument() == null, "replay does not target an editor");
                    check(app.documentActions.stream().noneMatch(Action::isEnabled),
                            "editor menu actions disabled for replay");
                    replay.timer.start();
                    app.closeTab(replayPanel);
                    check(!replay.timer.isRunning(), "closing replay stops playback");
                    app.activate(first);
                    app.cycleTab(1);
                    check(app.activeDocument() == second, "next tab navigation");
                    app.cycleTab(-1);
                    check(app.activeDocument() == first, "previous tab navigation");
                    check(app.tabs.getTitleAt(app.tabs.indexOfComponent(first)).endsWith(" *"),
                            "unsaved tab title");
                    app.rebuildTabsMenu();
                    check(app.tabsMenu.getItemCount() == 7, "dynamic tab list");
                    app.activate(second);
                    check(app.documentActions.stream().allMatch(Action::isEnabled),
                            "editor menu actions enabled for document");
                    second.savedEntries = second.filter.entries.size();
                    app.activate(first);
                    JPanel secondHeader = (JPanel) app.tabs.getTabComponentAt(app.tabs.indexOfComponent(second));
                    ((JButton) secondHeader.getComponent(1)).doClick();
                    check(app.tabs.indexOfComponent(second) == -1 && app.activeDocument() == first,
                            "close button closes its own tab while preserving the selected document");
                    for (DocumentTab document : new ArrayList<>(app.documents)) {
                        document.savedEntries = document.filter.entries.size();
                        check(app.closeDocument(document), "close document tab");
                    }
                    check(app.tabs.getTabCount() == 0 && app.documents.isEmpty(),
                            "all tabs closed");
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            background[0].flush().get();
            background[0].close();
            SwingUtilities.invokeAndWait(() -> { });
        } finally {
            try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                for (Path path : (Iterable<Path>) files::iterator) Files.deleteIfExists(path);
            }
            Files.deleteIfExists(directory);
        }
    }

    static void testJson() throws Exception {
        Path sample = Path.of("exp_subj_json_1.json");
        if (Files.exists(sample)) {
            ReplayLog imported = ReplayLog.load(sample, -1);
            check(imported.states.size() == 1260, "sample event count");
            check(imported.states.get(1259).text.length() == 206, "sample final text length");
            ReplayLog roundtrip = JsonLog.load(JsonLog.export(imported.events, imported.metadata));
            checkHistory(imported, roundtrip, "sample JSON round trip");
            List<?> originalJson = (List<?>) new Json(Files.readString(sample)).parse();
            List<?> exportedJson = (List<?>) new Json(JsonLog.export(imported.events, imported.metadata)).parse();
            check(originalJson.get(1).equals(exportedJson.get(1)), "sample event JSON fields preserved exactly");
            check(roundtrip.metadata.get("fontFamily").equals("Calibri"), "sample metadata preserved");
            check(JsonLog.number(roundtrip.metadata, "startTime") == 179610304410659L,
                    "nanosecond precision preserved");
            check(JsonLog.number(roundtrip.metadata, "endTime") == 179743787869526L,
                    "sample endTime preserved");
            SwingUtilities.invokeAndWait(() -> {
                LoggingFilter filter = new LoggingFilter();
                JTextArea text = createTextArea(filter);
                restoreLog(text, filter, imported);
                filter.append(new KeyLogEvent(filter.timestamp(), EventType.KEY_PRESSED, 65, null, null, null, null, null));
                ReplayLog continued = ReplayLog.parse(filter.entries);
                Instant previous = continued.states.get(continued.states.size() - 2).time;
                Instant next = continued.states.get(continued.states.size() - 1).time;
                check(Duration.between(previous, next).toSeconds() < 5,
                        "import continuation clock is rebased");
            });
            System.out.println("Sample JSON: all 1259 events loaded and round-tripped");
        }
        String controls = "\b\f\u0000\n\t\r\"\\😀";
        check(new Json(Json.quote(controls)).parse().equals(controls), "JSON control characters");
        for (String invalid : List.of("[1,]", "{\"a\":1,\"a\":2}", "01", "[", "\"\\q\"")) {
            try {
                new Json(invalid).parse();
                throw new AssertionError("Accepted invalid JSON " + invalid);
            } catch (IllegalArgumentException expected) { }
        }
    }

    static void testScroll() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LoggingFilter filter = new LoggingFilter();
            filter.startSession("");
            JTextArea text = createTextArea(filter);
            JScrollPane scroll = createScrollPane(text, filter);
            scroll.setSize(200, 100);
            scroll.doLayout();
            text.setText(("Long line " + "x".repeat(100) + "\n").repeat(50));
            check(text.getLineWrap() && text.getWrapStyleWord(), "recording wraps at right edge");
            check(scroll.getVerticalScrollBarPolicy() == JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
                    && scroll.getHorizontalScrollBarPolicy() == JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                    "recording scrollbar policy");
            applyScroll(scroll, 80, 120);
            ReplayLog replay = ReplayLog.parse(new ArrayList<>(filter.entries));
            ReplayState state = replay.states.get(replay.states.size() - 1);
            check(state.scrollX == 0 && state.scrollY == 120, "wrapped recording logs vertical scroll");
            restoreLog(text, filter, replay);
            check(scroll.getViewport().getViewPosition().equals(new Point(0, 120)),
                    "open restores scroll position");
            checkHistory(replay, ReplayLog.parse(filter.entries), "scroll restoration is not logged");
            ReplayWindow window = new ReplayWindow(replay);
            window.scroll.setSize(200, 100);
            window.scroll.doLayout();
            window.seek(replay.states.size() - 1);
            check(window.scroll.getViewport().getViewPosition().equals(new Point(0, 120)),
                    "replay restores scroll position");
            window.seek(0);
            check(window.scroll.getViewport().getViewPosition().equals(new Point()),
                    "reverse restores initial scroll position");
        });
    }

    static void testSaveAndContinue(List<String> original, String special) throws Exception {
        Path directory = Files.createTempDirectory("document-log-roundtrip");
        Path saved = directory.resolve("saved.log");
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    LoggingFilter filter = new LoggingFilter();
                    filter.startSession("");
                    JTextArea text = createTextArea(filter);
                    restoreLog(text, filter, ReplayLog.parse(original));
                    check(text.getText().equals(special), "open restores Unicode and escaped text");
                    check(text.getCaret().getDot() == special.length()
                            && text.getCaret().getMark() == 0, "open restores selection direction");
                    checkHistory(ReplayLog.parse(original), ReplayLog.parse(filter.entries), "restore creates no synthetic events");
                    filter.save(saved);
                    checkHistory(ReplayLog.parse(original), ReplayLog.load(saved, -1), "save preserves history");

                    LoggingFilter continued = new LoggingFilter();
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
                    checkHistory(ReplayLog.parse(original), roundtrip, "continued save retains original events");
                    int previous = roundtrip.previousEdit(roundtrip.states.size() - 1);
                    check(roundtrip.states.get(previous).text.equals(special),
                            "reverse replay crosses the save/open boundary");
                    restoreLog(next, continued, roundtrip);
                    check(next.getText().equals("Continued") && continued.entries.size() == roundtrip.events.size(),
                            "repeated reopen");
                    try {
                        continued.save(directory.resolve("missing").resolve("failed.log"));
                        throw new AssertionError("Save to missing directory succeeded");
                    } catch (IOException expected) { }
                    checkHistory(roundtrip, ReplayLog.load(saved, -1), "failed save preserves existing log");
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
        } finally {
            Files.deleteIfExists(saved);
            Files.deleteIfExists(directory);
        }
    }

    static void checkHistory(ReplayLog expected, ReplayLog actual, String message) {
        check(actual.states.size() >= expected.states.size(), message);
        for (int i = 0; i < expected.states.size(); i++) {
            ReplayState a = expected.states.get(i), b = actual.states.get(i);
            check(a.time.equals(b.time) && a.text.equals(b.text) && a.dot == b.dot
                    && a.mark == b.mark && a.scrollX == b.scrollX && a.scrollY == b.scrollY
                    && a.edit == b.edit, message + " event " + i);
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Adapter for the experiment's [[metadata], [events]] JSON format. */
    static class JsonLog {
        static String export(List<?> input, Map<String, Object> originalMetadata) {
            java.io.StringWriter writer = new java.io.StringWriter();
            try { write(ReplayLog.parse(input).events, originalMetadata, writer); }
            catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
            return writer.toString();
        }

        /** Stream one JSON event at a time; no whole-output string or event-map list. */
        static void write(List<LogEvent> events, Map<String, Object> originalMetadata, java.io.Writer writer) throws IOException {
            if (events.isEmpty() || !(events.get(0) instanceof SessionEvent)) throw new IllegalArgumentException("Missing session");
            SessionEvent initial = (SessionEvent) events.get(0);
            LogEvent last = events.get(events.size() - 1);
            // Validate one forward pass using a single text buffer, without replay checkpoints.
            StringBuilder text = new StringBuilder(initial.initialText);
            Instant previous = initial.time;
            for (int i = 1; i < events.size(); i++) {
                LogEvent event = events.get(i);
                if (event.time.isBefore(previous)) throw new IllegalArgumentException("timestamps run backwards");
                previous = event.time;
                if (event instanceof EditEvent) ((EditEvent) event).apply(text);
                else if (event instanceof CaretLogEvent) {
                    CaretLogEvent caret = (CaretLogEvent) event;
                    if (caret.dot < 0 || caret.mark < 0 || caret.dot > text.length() || caret.mark > text.length()) {
                        throw new IllegalArgumentException("caret outside document");
                    }
                } else if (event instanceof ScrollLogEvent) {
                    ScrollLogEvent scroll = (ScrollLogEvent) event;
                    if (scroll.x < 0 || scroll.y < 0) throw new IllegalArgumentException("negative scroll position");
                } else if (event instanceof SessionEvent) throw new IllegalArgumentException("Unexpected session marker");
            }
            Map<String, Object> metadata = new LinkedHashMap<>(originalMetadata);
            for (String key : List.of("id_age", "id_language", "id_task", "id_condition", "id_comment", "id_code",
                    "id_family_name", "id_project", "textLanguage", "id_first_name", "id_gender")) metadata.putIfAbsent(key, "");
            metadata.putIfAbsent("osName", System.getProperty("os.name"));
            metadata.putIfAbsent("Version", "ScriptLogLite-2");
            metadata.putIfAbsent("fontFamily", "Monospaced"); metadata.putIfAbsent("fontSize", 12);
            metadata.putIfAbsent("lineSpacing", 1.0);
            for (String key : List.of("TextAreaWidth", "TextAreaHeight", "TextAreaX", "TextAreaY")) metadata.putIfAbsent(key, 0);
            long start = metadata.containsKey("startTime") ? number(metadata, "startTime") : 0;
            metadata.put("startTime", start);
            long end = Math.addExact(start, Duration.between(initial.time, last.time).toNanos());
            if (metadata.containsKey("endTime")) end = Math.max(end, number(metadata, "endTime"));
            metadata.put("endTime", end);
            metadata.put("initialText", initial.initialText);
            metadata.put("recordingStartTime", initial.time.toString());
            metadata.put("tokensInFinalText", text.length());
            writer.write("[\n  [\n    ");
            writer.write(Json.stringify(metadata, 2));
            writer.write("\n  ],\n  [");
            for (int i = 1; i < events.size(); i++) {
                LogEvent item = events.get(i);
                long elapsed = Duration.between(initial.time, item.time).toNanos();
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("when", Math.addExact(start, elapsed));
                event.put("relativeTime", String.format(java.util.Locale.ROOT, "%.3f", elapsed / 1e9));
                event.put("event", "<" + item.type.name + ">"); event.put("eventID", item.type.id);
                if (item instanceof EditEvent) {
                    EditEvent edit = (EditEvent) item;
                    event.put("offset", edit.offset); event.put("length", edit.removed.length());
                    if (edit.type != EventType.REMOVE) event.put("str", edit.inserted);
                } else if (item instanceof CaretLogEvent) {
                    CaretLogEvent caret = (CaretLogEvent) item;
                    event.put("dot", caret.dot); event.put("mark", caret.mark);
                } else if (item instanceof ScrollLogEvent) {
                    ScrollLogEvent scroll = (ScrollLogEvent) item;
                    event.put("viewX", scroll.x); event.put("viewY", scroll.y);
                } else if (item instanceof KeyLogEvent) {
                    KeyLogEvent key = (KeyLogEvent) item;
                    event.put("keyCode", key.keyCode);
                    if (key.keyText != null) event.put("keyText", key.keyText);
                    if (key.keyChar != null) event.put("keyChar", key.keyChar);
                    if (key.modifiers != null) event.put("modifiers", key.modifiers);
                    if (key.modifiersText != null) event.put("modifiersText", key.modifiersText);
                    if (key.keyLocation != null) event.put("keyLocation", key.keyLocation);
                }
                writer.write(i == 1 ? "\n    " : ",\n    ");
                writer.write(Json.stringify(event, 2));
            }
            writer.write(events.size() == 1 ? "]\n]" : "\n  ]\n]");
        }

        @SuppressWarnings("unchecked")
        static ReplayLog load(String json) {
            Object parsed = new Json(json).parse();
            if (!(parsed instanceof List) || ((List<?>) parsed).size() != 2) throw new IllegalArgumentException("Expected [[metadata], [events]]");
            List<?> root = (List<?>) parsed;
            if (!(root.get(0) instanceof List) || ((List<?>) root.get(0)).size() != 1
                    || !(((List<?>) root.get(0)).get(0) instanceof Map) || !(root.get(1) instanceof List)) {
                throw new IllegalArgumentException("Expected one metadata object and an event array");
            }
            Map<String, Object> metadata = (Map<String, Object>) ((List<?>) root.get(0)).get(0);
            long start = number(metadata, "startTime");
            Instant anchor = metadata.containsKey("recordingStartTime") ? Instant.parse(string(metadata, "recordingStartTime")) : Instant.EPOCH;
            String initial = metadata.containsKey("initialText") ? string(metadata, "initialText") : "";
            StringBuilder text = new StringBuilder(initial);
            List<LogEvent> events = new ArrayList<>(); events.add(new SessionEvent(anchor, initial));
            int index = 0;
            for (Object item : (List<?>) root.get(1)) {
                index++;
                try {
                    if (!(item instanceof Map)) throw new IllegalArgumentException("event must be an object");
                    Map<String, Object> fields = (Map<String, Object>) item;
                    String tag = string(fields, "event");
                    if (!tag.startsWith("<") || !tag.endsWith(">")) throw new IllegalArgumentException("invalid event name");
                    EventType type = EventType.named(tag.substring(1, tag.length() - 1));
                    if (type == EventType.SESSION || number(fields, "eventID") != type.id) throw new IllegalArgumentException("eventID mismatch");
                    long elapsed = Math.subtractExact(number(fields, "when"), start);
                    if (elapsed < 0) throw new IllegalArgumentException("event precedes startTime");
                    Instant time = anchor.plusNanos(elapsed);
                    LogEvent event;
                    switch (type) {
                        case INSERT: case REMOVE: case REPLACE:
                            int offset = integer(fields, "offset"), length = integer(fields, "length");
                            if (offset < 0 || length < 0 || offset > text.length() - length) throw new IllegalArgumentException("edit outside document");
                            if (type == EventType.INSERT && length != 0) throw new IllegalArgumentException("insertString length must be zero");
                            if (type != EventType.REMOVE && !fields.containsKey("str")) throw new IllegalArgumentException("missing str");
                            String inserted = type == EventType.REMOVE || fields.get("str") == null ? null : string(fields, "str");
                            EditEvent edit = new EditEvent(time, type, offset, text.substring(offset, offset + length), inserted);
                            edit.apply(text); event = edit; break;
                        case CARET: event = new CaretLogEvent(time, integer(fields, "dot"), integer(fields, "mark")); break;
                        case SCROLL: event = new ScrollLogEvent(time, integer(fields, "viewX"), integer(fields, "viewY")); break;
                        default: event = new KeyLogEvent(time, type, integer(fields, "keyCode"), optionalString(fields, "keyText"),
                                optionalString(fields, "keyChar"), optionalInt(fields, "modifiers"), optionalString(fields, "modifiersText"),
                                optionalInt(fields, "keyLocation"));
                    }
                    events.add(event);
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("Invalid JSON event " + index + ": " + exception.getMessage(), exception);
                }
            }
            ReplayLog replay = new ReplayLog(events); replay.metadata.putAll(metadata); return replay;
        }
        static String optionalString(Map<String, Object> fields, String key) { return fields.containsKey(key) ? string(fields, key) : null; }
        static Integer optionalInt(Map<String, Object> fields, String key) { return fields.containsKey(key) ? integer(fields, key) : null; }

        static long number(Map<String, Object> object, String key) {
            Object value = object.get(key);
            if (!(value instanceof Long) && !(value instanceof Integer)) {
                throw new IllegalArgumentException("Expected integer " + key);
            }
            return ((Number) value).longValue();
        }

        static int integer(Map<String, Object> object, String key) {
            return Math.toIntExact(number(object, key));
        }

        static String string(Map<String, Object> object, String key) {
            Object value = object.get(key);
            if (!(value instanceof String)) throw new IllegalArgumentException("Expected string " + key);
            return (String) value;
        }
    }

    /** Small strict JSON reader/writer, so source launching needs no external library. */
    static class Json {
        final String input;
        int position;
        Json(String input) { this.input = input; }

        Object parse() {
            Object value = value();
            whitespace();
            if (position != input.length()) throw error("trailing input");
            return value;
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("JSON at character " + position + ": " + message);
        }

        void whitespace() {
            while (position < input.length() && " \n\r\t".indexOf(input.charAt(position)) >= 0) position++;
        }

        boolean take(char c) {
            whitespace();
            if (position < input.length() && input.charAt(position) == c) { position++; return true; }
            return false;
        }

        void expect(char c) { if (!take(c)) throw error("expected " + c); }

        Object value() {
            whitespace();
            if (position == input.length()) throw error("missing value");
            char c = input.charAt(position);
            if (c == '"') return string();
            if (take('[')) {
                List<Object> values = new ArrayList<>();
                if (take(']')) return values;
                do { values.add(value()); } while (take(','));
                expect(']');
                return values;
            }
            if (take('{')) {
                Map<String, Object> values = new LinkedHashMap<>();
                if (take('}')) return values;
                do {
                    whitespace();
                    String key = string();
                    expect(':');
                    if (values.containsKey(key)) throw error("duplicate key " + key);
                    values.put(key, value());
                } while (take(','));
                expect('}');
                return values;
            }
            for (String literal : List.of("null", "true", "false")) {
                if (input.startsWith(literal, position)) {
                    position += literal.length();
                    return literal.equals("null") ? null : Boolean.valueOf(literal);
                }
            }
            Matcher matcher = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
                    .matcher(input);
            matcher.region(position, input.length());
            if (!matcher.lookingAt()) throw error("invalid value");
            String number = matcher.group();
            position = matcher.end();
            // Avoid a numeric ternary: Java would promote long values to double and lose nanosecond precision.
            if (number.contains(".") || number.contains("e") || number.contains("E")) {
                double result = Double.parseDouble(number);
                if (!Double.isFinite(result)) throw error("non-finite number");
                return result;
            }
            return Long.parseLong(number);
        }

        String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (position < input.length()) {
                char c = input.charAt(position++);
                if (c == '"') return result.toString();
                if (c < 32) throw error("unescaped control character");
                if (c == '\\') {
                    if (position == input.length()) throw error("unfinished escape");
                    c = input.charAt(position++);
                    switch (c) {
                        case '"': case '\\': case '/': break;
                        case 'n': c = '\n'; break;
                        case 'r': c = '\r'; break;
                        case 't': c = '\t'; break;
                        case 'b': c = '\b'; break;
                        case 'f': c = '\f'; break;
                        case 'u':
                            if (position + 4 > input.length()) throw error("unfinished unicode escape");
                            c = (char) Integer.parseInt(input.substring(position, position + 4), 16);
                            position += 4;
                            break;
                        default: throw error("unknown escape");
                    }
                }
                result.append(c);
            }
            throw error("unterminated string");
        }

        static String quote(String value) {
            StringBuilder result = new StringBuilder("\"");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"': result.append("\\\""); break;
                    case '\\': result.append("\\\\"); break;
                    case '\n': result.append("\\n"); break;
                    case '\r': result.append("\\r"); break;
                    case '\t': result.append("\\t"); break;
                    default:
                        if (c < 32 || Character.isSurrogate(c)) {
                            result.append(String.format("\\u%04x", (int) c));
                        } else result.append(c);
                }
            }
            return result.append('"').toString();
        }

        static String stringify(Object value, int depth) {
            if (value == null) return "null";
            if (value instanceof String) return quote((String) value);
            if (value instanceof Number || value instanceof Boolean) return value.toString();
            List<String> children = new ArrayList<>();
            boolean object = value instanceof Map;
            if (object) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    children.add(quote((String) entry.getKey()) + ": " + stringify(entry.getValue(), depth + 1));
                }
            } else {
                for (Object item : (List<?>) value) children.add(stringify(item, depth + 1));
            }
            String open = object ? "{" : "[", close = object ? "}" : "]";
            if (children.isEmpty()) return open + close;
            String indent = "  ".repeat(depth + 1);
            return open + "\n" + indent + String.join(",\n" + indent, children)
                    + "\n" + "  ".repeat(depth) + close;
        }
    }
}
