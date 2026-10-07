package se.lu.scriptloglite;

import java.awt.Point;
import java.awt.Dimension;
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
    private static final Path LOG_PATH = Path.of("document-filter.json");

    public static void main(String[] args) throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            try {
                javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
            } catch (Exception exception) {
                System.err.println("Using default appearance: " + exception.getMessage());
            }
        }
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
            LoggingFilter filter = new LoggingFilter(new PrintWriter(java.io.OutputStream.nullOutputStream()));
            filter.write("session initialText=\"\"");
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
        final JFileChooser chooser = new JFileChooser();
        final Timer autosave;
        JFrame frame;
        int sequence;

        TabbedApplication() {
            tabs.setPreferredSize(new Dimension(1100, 700));
            tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            tabs.addChangeListener(event -> updateActions());
            toolbar.setFloatable(false);
            toolbar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            chooser.setSelectedFile(new java.io.File("saved-document.json"));
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
                    addReplay(ReplayLog.parse(new ArrayList<>(document.filter.entries)), document.title);
                } catch (Exception exception) { showError(frame, exception); }
            }));
            item(view, "Open Log for Replay…", 0, false, () -> open(true));
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
            autosave = new Timer(500, event -> {
                for (DocumentTab document : documents) {
                    try { document.filter.saveAutomatic(); }
                    catch (Exception exception) {
                        ((Timer) event.getSource()).stop();
                        showError(frame, exception);
                        return;
                    }
                }
            });
        }

        JMenu menu(String name, int mnemonic) {
            JMenu menu = new JMenu(name);
            menu.setMnemonic(mnemonic);
            menus.add(menu);
            return menu;
        }

        Action item(JMenu menu, String name, int key, boolean requiresDocument, Runnable command) {
            Action action = new AbstractAction(name) {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    if (!requiresDocument || activeDocument() != null) command.run();
                    updateActions();
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
                for (LoggingFilter recording : recordings) {
                    try { recording.saveAutomatic(); }
                    catch (Exception exception) { System.err.println(exception.getMessage()); }
                }
            }));
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
            LoggingFilter filter = new LoggingFilter(new PrintWriter(java.io.OutputStream.nullOutputStream()));
            filter.automaticPath = id == 1 ? LOG_PATH
                    : Path.of("document-filter-" + java.util.UUID.randomUUID() + ".json");
            filter.write("session initialText=\"\"");
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
            if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
            Path path = chooser.getSelectedFile().toPath();
            try {
                ReplayLog loaded = ReplayLog.load(path, -1);
                if (replay) addReplay(loaded, path.getFileName().toString());
                else addDocument(loaded, path);
            } catch (Exception exception) { showError(frame, exception); }
        }

        boolean save(DocumentTab document, boolean saveAs) {
            if (document == null) return false;
            Path path = document.savedPath;
            if (saveAs || path == null) {
                chooser.setSelectedFile(path == null ? new java.io.File("saved-document.json") : path.toFile());
                if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return false;
                path = chooser.getSelectedFile().toPath();
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
                document.filter.save(path);
                document.savedPath = path;
                document.savedEntries = document.filter.entries.size();
                document.updateTitle();
                status.setText("Saved " + path.toAbsolutePath());
                return true;
            } catch (Exception exception) { showError(frame, exception); return false; }
        }

        boolean closeDocument(DocumentTab document) {
            if (document.filter.entries.size() != document.savedEntries) {
                int answer = JOptionPane.showConfirmDialog(frame,
                        "Save changes to " + document.title + "?", "Close Document",
                        JOptionPane.YES_NO_CANCEL_OPTION);
                if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) return false;
                if (answer == JOptionPane.YES_OPTION && !save(document, false)) return false;
            }
            try { document.filter.saveAutomatic(); }
            catch (Exception exception) { showError(frame, exception); return false; }
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
            while (tabs.getTabCount() > 0) {
                if (!closeTab(tabs.getComponentAt(0))) return false;
            }
            return true;
        }

        void exit() {
            if (!closeAll()) return;
            autosave.stop();
            if (frame != null) frame.dispose();
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
        JScrollPane scroll = new JScrollPane(text);
        text.putClientProperty("logScrollPane", scroll);
        Point[] previous = {new Point()};
        scroll.getViewport().addChangeListener(event -> {
            Point position = scroll.getViewport().getViewPosition();
            if (!position.equals(previous[0])) {
                previous[0] = new Point(position);
                filter.write("scrollChange x=" + position.x + " y=" + position.y);
            }
        });
        return scroll;
    }

    static void applyScroll(JScrollPane scroll, int x, int y) {
        Dimension preferred = scroll.getViewport().getView().getPreferredSize();
        Dimension extent = scroll.getViewport().getExtentSize();
        scroll.getViewport().setViewSize(new Dimension(
                Math.max(preferred.width, extent.width), Math.max(preferred.height, extent.height)));
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
            List<String> rebased = new ArrayList<>();
            for (String line : loaded.lines) {
                int separator = line.indexOf(' ');
                Instant time = Instant.parse(line.substring(0, separator));
                rebased.add(newStart.plus(Duration.between(originalStart, time)) + line.substring(separator));
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
        filter.entries.clear();
        filter.entries.addAll(loaded.lines);
        filter.metadata.clear();
        filter.metadata.putAll(loaded.metadata);
        filter.dirty = true;
        filter.lastTime = state.time;
        // The automatic audit file starts a checkpoint; saved logs retain the entire history.
        filter.audit(Instant.now() + " session initialText=" + LoggingFilter.quote(state.text));
        filter.audit(Instant.now() + " caretUpdate dot=" + state.dot + " mark=" + state.mark);
        filter.audit(Instant.now() + " scrollChange x=" + state.scrollX + " y=" + state.scrollY);
    }

    private static JTextArea createTextArea(LoggingFilter filter) {
        JTextArea text = new JTextArea(12, 50);
        text.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 16));
        text.setMargin(new java.awt.Insets(12, 12, 12, 12));
        filter.metadata.putIfAbsent("fontFamily", text.getFont().getFamily());
        filter.metadata.putIfAbsent("fontSize", text.getFont().getSize());
        text.addComponentListener(new java.awt.event.ComponentAdapter() {
            private void capture() {
                synchronized (filter) {
                    filter.metadata.put("TextAreaWidth", text.getWidth());
                    filter.metadata.put("TextAreaHeight", text.getHeight());
                    Point location = text.isShowing() ? text.getLocationOnScreen() : text.getLocation();
                    filter.metadata.put("TextAreaX", location.x);
                    filter.metadata.put("TextAreaY", location.y);
                    filter.dirty = true;
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

    public static class LoggingFilter extends DocumentFilter {
        private final PrintWriter log;
        final List<String> entries = new ArrayList<>();
        final Map<String, Object> metadata = new LinkedHashMap<>();
        boolean dirty = true;
        Path automaticPath = LOG_PATH;
        Runnable onRecord = () -> { };
        boolean restoring;
        Instant lastTime = Instant.MIN;

        synchronized void save(Path path) throws IOException {
            if (path.toAbsolutePath().normalize().equals(automaticPath.toAbsolutePath().normalize())
                    || (Files.exists(path) && Files.exists(automaticPath) && Files.isSameFile(path, automaticPath))) {
                throw new IOException("Choose a different filename from the active automatic log: " + automaticPath);
            }
            saveJson(path);
        }

        synchronized void saveAutomatic() throws IOException {
            if (!dirty || entries.isEmpty()) return;
            saveJson(automaticPath);
            dirty = false;
        }

        private void saveJson(Path path) throws IOException {
            String json = JsonLog.export(new ArrayList<>(entries), metadata);
            Path target = path.toAbsolutePath();
            Path temporary = Files.createTempFile(target.getParent(), ".saved-log-", ".tmp");
            try {
                Files.writeString(temporary, json + "\n", StandardCharsets.UTF_8);
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
            metadata.put("startTime", System.nanoTime());
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
            dirty = true;
            onRecord.run();
        }

        private void audit(String entry) {
            log.println(entry);
            if (log.checkError()) {
                throw new IllegalStateException("Unable to write " + automaticPath);
            }
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
        final int dot, mark, scrollX, scrollY;
        final String description;
        final boolean edit;

        ReplayState(Instant time, String text, int dot, int mark, int scrollX, int scrollY,
                String description, boolean edit) {
            this.time = time;
            this.text = text;
            this.dot = dot;
            this.mark = mark;
            this.scrollX = scrollX;
            this.scrollY = scrollY;
            this.description = description;
            this.edit = edit;
        }
    }

    static class ReplayLog {
        final List<ReplayState> states = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        final Map<String, Object> metadata = new LinkedHashMap<>();
        // Each boundary includes an edit and the caret/key events following it.
        final List<Integer> boundaries = new ArrayList<>();
        static final Pattern FIELD = Pattern.compile(
                "(\\w+)=(\"(?:\\\\.|[^\"\\\\])*+\"|\\S+)");

        static ReplayLog load(Path path, int session) throws Exception {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.stripLeading().startsWith("[")) {
                if (session != -1 && session != 1) {
                    throw new IllegalArgumentException("A JSON log contains one session; choose session 1.");
                }
                return JsonLog.load(content);
            }
            List<List<String>> sessions = new ArrayList<>();
            for (String line : content.split("\\R")) {
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
            int dot = 0, mark = 0, scrollX = 0, scrollY = 0;
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
                    } else if (method.equals("scrollChange")) {
                        scrollX = Integer.parseInt(required(fields, "x"));
                        scrollY = Integer.parseInt(required(fields, "y"));
                        if (scrollX < 0 || scrollY < 0) {
                            throw new IllegalArgumentException("negative scroll position");
                        }
                    } else if (!method.equals("keyPressed") && !method.equals("keyReleased")) {
                        throw new IllegalArgumentException("unknown event " + method);
                    }
                    result.states.add(new ReplayState(time, text, dot, mark, scrollX, scrollY,
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
        final JScrollPane scroll = new JScrollPane(text);
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
            JButton reset = new JButton("Restart");
            reset.addActionListener(event -> seek(0));
            JPanel controls = new JPanel();
            controls.add(backward);
            controls.add(forward);
            controls.add(play);
            controls.add(new JLabel("Speed ×"));
            controls.add(speed);
            controls.add(reset);
            JPanel frame = new JPanel(new java.awt.BorderLayout(0, 8));
            status.setBorder(BorderFactory.createEmptyBorder(8, 10, 0, 10));
            frame.add(status, java.awt.BorderLayout.NORTH);
            frame.add(scroll, java.awt.BorderLayout.CENTER);
            frame.add(controls, java.awt.BorderLayout.SOUTH);
            render();
            return frame;
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
        testScroll();
        testJson();
        testTabs();
        System.out.println("Replay self-test passed");
    }

    static void testTabs() throws Exception {
        Path directory = Files.createTempDirectory("mdi-document-test");
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    TabbedApplication app = new TabbedApplication();
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
            // Flush deferred Swing restoration/activation notifications before cleanup.
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
            ReplayLog roundtrip = JsonLog.load(JsonLog.export(imported.lines, imported.metadata));
            checkHistory(imported, roundtrip, "sample JSON round trip");
            check(roundtrip.metadata.get("fontFamily").equals("Calibri"), "sample metadata preserved");
            check(JsonLog.number(roundtrip.metadata, "startTime") == 179610304410659L,
                    "nanosecond precision preserved");
            check(JsonLog.number(roundtrip.metadata, "endTime") == 179743787869526L,
                    "sample endTime preserved");
            SwingUtilities.invokeAndWait(() -> {
                LoggingFilter filter = new LoggingFilter(new PrintWriter(new java.io.StringWriter()));
                JTextArea text = createTextArea(filter);
                restoreLog(text, filter, imported);
                filter.write("keyPressed keyCode=65");
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
            LoggingFilter filter = new LoggingFilter(new PrintWriter(new java.io.StringWriter()));
            filter.write("session initialText=\"\"");
            JTextArea text = createTextArea(filter);
            JScrollPane scroll = createScrollPane(text, filter);
            scroll.setSize(200, 100);
            scroll.doLayout();
            text.setText(("Long line " + "x".repeat(100) + "\n").repeat(50));
            applyScroll(scroll, 80, 120);
            ReplayLog replay = ReplayLog.parse(new ArrayList<>(filter.entries));
            ReplayState state = replay.states.get(replay.states.size() - 1);
            check(state.scrollX == 80 && state.scrollY == 120, "both scroll axes logged");
            restoreLog(text, filter, replay);
            check(scroll.getViewport().getViewPosition().equals(new Point(80, 120)),
                    "open restores scroll position");
            check(filter.entries.equals(replay.lines), "scroll restoration is not logged");
            ReplayWindow window = new ReplayWindow(replay);
            window.scroll.setSize(200, 100);
            window.scroll.doLayout();
            window.seek(replay.states.size() - 1);
            check(window.scroll.getViewport().getViewPosition().equals(new Point(80, 120)),
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
                    LoggingFilter filter = new LoggingFilter(new PrintWriter(new java.io.StringWriter()));
                    filter.write("session initialText=\"\"");
                    JTextArea text = createTextArea(filter);
                    restoreLog(text, filter, ReplayLog.parse(original));
                    check(text.getText().equals(special), "open restores Unicode and escaped text");
                    check(text.getCaret().getDot() == special.length()
                            && text.getCaret().getMark() == 0, "open restores selection direction");
                    check(filter.entries.equals(original), "restore creates no synthetic events");
                    filter.save(saved);
                    checkHistory(ReplayLog.parse(original), ReplayLog.load(saved, -1), "save preserves history");

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
                    checkHistory(ReplayLog.parse(original), roundtrip, "continued save retains original events");
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
                    check(ReplayLog.load(saved, -1).lines.equals(roundtrip.lines), "failed save preserves existing log");
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
        static final Map<String, Integer> IDS = Map.of("insertString", 101, "remove", 102,
                "replace", 103, "caretUpdate", 104, "scrollChange", 107,
                "keyPressed", 207, "keyReleased", 208);

        static Map<String, String> fields(String line) {
            Map<String, String> fields = new LinkedHashMap<>();
            Matcher matcher = ReplayLog.FIELD.matcher(line);
            while (matcher.find()) fields.put(matcher.group(1), ReplayLog.decode(matcher.group(2)));
            return fields;
        }

        static String export(List<String> lines, Map<String, Object> originalMetadata) {
            ReplayLog replay = ReplayLog.parse(lines);
            ReplayState initial = replay.states.get(0);
            ReplayState last = replay.states.get(replay.states.size() - 1);
            Map<String, Object> metadata = new LinkedHashMap<>(originalMetadata);
            for (String key : List.of("id_age", "id_language", "id_task", "id_condition",
                    "id_comment", "id_code", "id_family_name", "id_project", "textLanguage",
                    "id_first_name", "id_gender")) metadata.putIfAbsent(key, "");
            metadata.putIfAbsent("osName", System.getProperty("os.name"));
            metadata.putIfAbsent("Version", "ScriptLogLite-2");
            metadata.putIfAbsent("fontFamily", "Monospaced");
            metadata.putIfAbsent("fontSize", 12);
            metadata.putIfAbsent("lineSpacing", 1.0);
            for (String key : List.of("TextAreaWidth", "TextAreaHeight", "TextAreaX", "TextAreaY")) {
                metadata.putIfAbsent(key, 0);
            }
            long start = metadata.containsKey("startTime") ? number(metadata, "startTime") : 0;
            metadata.put("startTime", start);
            long end = Math.addExact(start, Duration.between(initial.time, last.time).toNanos());
            if (metadata.containsKey("endTime")) end = Math.max(end, number(metadata, "endTime"));
            metadata.put("endTime", end);
            // Added fields allow an initially nonempty document and lossless internal timestamp round trips.
            metadata.put("initialText", initial.text);
            metadata.put("recordingStartTime", initial.time.toString());
            metadata.put("tokensInFinalText", last.text.length());
            List<Object> events = new ArrayList<>();
            for (int i = 1; i < replay.states.size(); i++) {
                ReplayState state = replay.states.get(i);
                String method = state.description.split(" ", 2)[0];
                Map<String, String> fields = fields(state.description);
                Map<String, Object> event = new LinkedHashMap<>();
                long elapsed = Duration.between(initial.time, state.time).toNanos();
                event.put("when", Math.addExact(start, elapsed));
                event.put("relativeTime", String.format(java.util.Locale.ROOT, "%.3f", elapsed / 1e9));
                event.put("event", "<" + method + ">");
                event.put("eventID", IDS.get(method));
                switch (method) {
                    case "insertString": case "replace": case "remove":
                        event.put("offset", Integer.parseInt(ReplayLog.required(fields, "offset")));
                        event.put("length", Integer.parseInt(ReplayLog.required(fields, "length")));
                        if (!method.equals("remove")) event.put("str", fields.get("text"));
                        break;
                    case "caretUpdate":
                        event.put("dot", state.dot);
                        event.put("mark", state.mark);
                        break;
                    case "scrollChange":
                        event.put("viewX", state.scrollX);
                        event.put("viewY", state.scrollY);
                        break;
                    case "keyPressed": case "keyReleased":
                        event.put("keyCode", Integer.parseInt(ReplayLog.required(fields, "keyCode")));
                        // Retain additional keyboard details when our recorder supplies them.
                        for (String key : List.of("keyText", "keyChar", "modifiersText")) {
                            if (fields.containsKey(key)) event.put(key, fields.get(key));
                        }
                        for (String key : List.of("modifiers", "keyLocation")) {
                            if (fields.containsKey(key)) event.put(key, Integer.parseInt(fields.get(key)));
                        }
                        break;
                    default: throw new IllegalArgumentException("Unknown event " + method);
                }
                events.add(event);
            }
            return Json.stringify(List.of(List.of(metadata), events), 0);
        }

        @SuppressWarnings("unchecked")
        static ReplayLog load(String json) {
            Object parsed = new Json(json).parse();
            if (!(parsed instanceof List) || ((List<?>) parsed).size() != 2) {
                throw new IllegalArgumentException("Expected [[metadata], [events]]");
            }
            List<?> root = (List<?>) parsed;
            if (!(root.get(0) instanceof List) || ((List<?>) root.get(0)).size() != 1
                    || !(((List<?>) root.get(0)).get(0) instanceof Map)
                    || !(root.get(1) instanceof List)) {
                throw new IllegalArgumentException("Expected one metadata object and an event array");
            }
            Map<String, Object> metadata = (Map<String, Object>) ((List<?>) root.get(0)).get(0);
            long start = number(metadata, "startTime");
            Instant anchor = metadata.containsKey("recordingStartTime")
                    ? Instant.parse(string(metadata, "recordingStartTime")) : Instant.EPOCH;
            String text = metadata.containsKey("initialText") ? string(metadata, "initialText") : "";
            List<String> lines = new ArrayList<>();
            lines.add(anchor + " session initialText=" + LoggingFilter.quote(text));
            int index = 0;
            for (Object item : (List<?>) root.get(1)) {
                index++;
                try {
                    if (!(item instanceof Map)) throw new IllegalArgumentException("event must be an object");
                    Map<String, Object> event = (Map<String, Object>) item;
                    String tag = string(event, "event");
                    if (!tag.startsWith("<") || !tag.endsWith(">")) {
                        throw new IllegalArgumentException("invalid event name");
                    }
                    String method = tag.substring(1, tag.length() - 1);
                    if (!IDS.containsKey(method)) throw new IllegalArgumentException("unknown event " + tag);
                    if (number(event, "eventID") != IDS.get(method)) {
                        throw new IllegalArgumentException("eventID does not match " + tag);
                    }
                    long elapsed = Math.subtractExact(number(event, "when"), start);
                    if (elapsed < 0) throw new IllegalArgumentException("event precedes startTime");
                    StringBuilder line = new StringBuilder(anchor.plusNanos(elapsed) + " " + method);
                    switch (method) {
                        case "insertString": case "replace": case "remove":
                            int offset = integer(event, "offset"), length = integer(event, "length");
                            if (offset < 0 || length < 0 || offset > text.length() - length) {
                                throw new IllegalArgumentException("edit outside document");
                            }
                            if (method.equals("insertString") && length != 0) {
                                throw new IllegalArgumentException("insertString length must be zero");
                            }
                            String old = text.substring(offset, offset + length);
                            String replacement = method.equals("remove") ? null
                                    : event.get("str") == null ? null : string(event, "str");
                            line.append(" offset=").append(offset).append(" length=").append(length)
                                    .append(" text=").append(LoggingFilter.quote(replacement))
                                    .append(" oldText=").append(LoggingFilter.quote(old));
                            text = text.substring(0, offset) + (replacement == null ? "" : replacement)
                                    + text.substring(offset + length);
                            break;
                        case "caretUpdate":
                            line.append(" dot=").append(integer(event, "dot"))
                                    .append(" mark=").append(integer(event, "mark"));
                            break;
                        case "scrollChange":
                            line.append(" x=").append(integer(event, "viewX"))
                                    .append(" y=").append(integer(event, "viewY"));
                            break;
                        case "keyPressed": case "keyReleased":
                            line.append(" keyCode=").append(integer(event, "keyCode"));
                            for (String key : List.of("keyText", "keyChar", "modifiersText")) {
                                if (event.containsKey(key)) line.append(" ").append(key).append("=")
                                        .append(LoggingFilter.quote(string(event, key)));
                            }
                            for (String key : List.of("modifiers", "keyLocation")) {
                                if (event.containsKey(key)) line.append(" ").append(key).append("=")
                                        .append(integer(event, key));
                            }
                            break;
                    }
                    lines.add(line.toString());
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("Invalid JSON event " + index + ": "
                            + exception.getMessage(), exception);
                }
            }
            ReplayLog replay = ReplayLog.parse(lines);
            replay.metadata.putAll(metadata);
            return replay;
        }

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
