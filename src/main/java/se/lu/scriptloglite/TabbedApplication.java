package se.lu.scriptloglite;

import se.lu.scriptloglite.inputlog.GeneralAnalysis;
import se.lu.scriptloglite.inputlog.GeneralAnalysisPanel;
import se.lu.scriptloglite.inputlog.SummaryAnalysis;
import se.lu.scriptloglite.inputlog.SummaryAnalysisPanel;
import se.lu.scriptloglite.inputlog.WordPausesAnalysis;
import se.lu.scriptloglite.inputlog.RevisionAnalysisPanel;
import se.lu.scriptloglite.inputlog.LinearAnalysis;
import se.lu.scriptloglite.inputlog.LinearAnalysisPanel;

import java.awt.Point;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.io.IOException;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import javax.swing.Timer;
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
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import static se.lu.scriptloglite.EditorSupport.applyScroll;
import static se.lu.scriptloglite.EditorSupport.restoreScroll;
import static se.lu.scriptloglite.EditorSupport.showError;
import static se.lu.scriptloglite.EditorSupport.restoreLog;
import static se.lu.scriptloglite.RecordingPaths.allocateRecording;
import static se.lu.scriptloglite.ThemeManager.installTheme;

/** Coordinates the main window, document/replay tabs, menus, and save actions. */
class TabbedApplication {
    final JTabbedPane tabs = new JTabbedPane();
    final JToolBar toolbar = new JToolBar();
    final Map<java.awt.Component, ReplayPanel> replays = new HashMap<>();
    final JMenuBar menus = new JMenuBar();
    final JMenu tabsMenu = new JMenu("Tabs");
    final JLabel status = new JLabel("Ready");
    final List<DocumentTab> documents = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<RecordingSession> recordings = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<Action> documentActions = new ArrayList<>();
    final JFileChooser openChooser = new JFileChooser();
    final JFileChooser saveChooser = new JFileChooser();
    final DirectoryHistory directories;
    final Map<LogFormat, javax.swing.JCheckBoxMenuItem> formatChoices = new java.util.EnumMap<>(LogFormat.class);
    final RecordingVariables recordingVariables = new RecordingVariables("exp", "_", "subj");
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
        openChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Logs (JSON, raw, Inputlog IDFX)", "json", "txt", "log", "idfx"));
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
                addReplay(document.filter.session.replay(), document.title);
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
        JMenu settings = menu("Settings", KeyEvent.VK_S);
        JMenu saveFormats = new JMenu("Save formats");
        for (LogFormat format : LogFormat.values()) {
            javax.swing.JCheckBoxMenuItem choice = new javax.swing.JCheckBoxMenuItem(format.label,
                    directories.formats().contains(format));
            formatChoices.put(format, choice);
            saveFormats.add(choice);
            choice.addActionListener(event -> {
                java.util.Set<LogFormat> selected = java.util.EnumSet.noneOf(LogFormat.class);
                for (LogFormat candidate : LogFormat.values()) if (formatChoices.get(candidate).isSelected()) selected.add(candidate);
                if (selected.isEmpty()) { choice.setSelected(true); return; }
                directories.rememberFormats(selected);
                for (RecordingSession session : recordings) session.setFormats(selected);
                updateActions();
            });
        }
        settings.add(saveFormats);
        javax.swing.JCheckBoxMenuItem idfxExtensions = new javax.swing.JCheckBoxMenuItem(
                "Include ScriptLogLite labels in IDFX", directories.idfxExtensions());
        settings.add(idfxExtensions);
        idfxExtensions.addActionListener(event -> {
            directories.rememberIdfxExtensions(idfxExtensions.isSelected());
            for (RecordingSession session : recordings) session.setIdfxExtensions(idfxExtensions.isSelected());
        });
        tabsMenu.setMnemonic(KeyEvent.VK_T);
        menus.add(tabsMenu);
        tabsMenu.addMenuListener(new javax.swing.event.MenuListener() {
            public void menuSelected(javax.swing.event.MenuEvent event) { rebuildTabsMenu(); }
            public void menuDeselected(javax.swing.event.MenuEvent event) { }
            public void menuCanceled(javax.swing.event.MenuEvent event) { }
        });
        rebuildTabsMenu();
        JMenu analysisMenu = menu("Analysis", KeyEvent.VK_A);
        item(analysisMenu, "General Analysis…", 0, false, this::generalAnalysis);
        item(analysisMenu, "Summary Analysis (PT0)…", 0, false, this::summaryAnalysis);
        item(analysisMenu, "S-Notation…", 0, false, () -> analyze(AnalysisKind.SNOTATION));
        item(analysisMenu, "Word Pauses…", 0, false, () -> analyze(AnalysisKind.WORD_PAUSES));
        item(analysisMenu, "Linear Analysis…", 0, false, () -> analyze(AnalysisKind.LINEAR));
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
            for (DocumentTab document : documents) saver.automatic(document.filter.session);
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
            for (ReplayPanel viewer : replays.values()) viewer.render();
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
            for (RecordingSession recording : recordings) saver.automatic(recording);
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
                : active.title + " — automatic log: " + primaryFormat(directories.formats())
                        .path(active.filter.session.automaticPath).toAbsolutePath());
    }

    DocumentTab addDocument(ReplayLog loaded, Path path) {
        int id = ++sequence;
        LoggingFilter filter = new LoggingFilter();
        try { filter.session.automaticPath = allocateRecording(recordingVariables, java.time.LocalDate.now()); }
        catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
        filter.session.setFormats(directories.formats());
        filter.session.setIdfxExtensions(directories.idfxExtensions());
        filter.startSession("");
        DocumentTab document = new DocumentTab(this, filter, "Untitled " + id);
        if (loaded != null) restoreLog(document.text, filter, loaded);
        document.savedPath = path;
        document.savedEntries = loaded == null ? 1 : filter.session.eventCount();
        document.updateTitle();
        documents.add(document);
        recordings.add(filter.session);
        addTab(document, document.title);
        if (loaded != null) {
            ReplayState state = loaded.states.get(loaded.states.size() - 1);
            SwingUtilities.invokeLater(() -> restoreScroll(document.text, filter, state));
        }
        document.text.requestFocusInWindow();
        return document;
    }

    void generalAnalysis() {
        analyze(AnalysisKind.GENERAL);
    }

    void summaryAnalysis() {
        analyze(AnalysisKind.SUMMARY);
    }

    private enum AnalysisKind { GENERAL, SUMMARY, SNOTATION, WORD_PAUSES, LINEAR }

    private void analyze(AnalysisKind kind) {
        java.awt.Component selected = tabs.getSelectedComponent();
        final ReplayLog source;
        final String title;
        try {
            if (selected instanceof DocumentTab) {
                DocumentTab document = (DocumentTab) selected;
                source = document.filter.session.replay(); title = document.title;
            } else if (replays.containsKey(selected)) {
                source = replays.get(selected).log; title = tabs.getTitleAt(tabs.getSelectedIndex());
            } else { JOptionPane.showMessageDialog(frame, "Select a document or replay tab first."); return; }
        } catch (Exception exception) { showError(frame, exception); return; }
        if (kind == AnalysisKind.LINEAR) {
            new javax.swing.SwingWorker<LinearAnalysis, Void>() {
                protected LinearAnalysis doInBackground() { return LinearAnalysis.analyze(source, 200, 60); }
                protected void done() {
                    try { addTab(new LinearAnalysisPanel(get(), title, directories), "Linear Analysis — " + title); }
                    catch (Exception exception) { showError(frame, exception); }
                }
            }.execute();
            return;
        }
        if (kind == AnalysisKind.SNOTATION || kind == AnalysisKind.WORD_PAUSES) {
            boolean words = kind == AnalysisKind.WORD_PAUSES;
            new javax.swing.SwingWorker<WordPausesAnalysis, Void>() {
                protected WordPausesAnalysis doInBackground() { return WordPausesAnalysis.analyze(source); }
                protected void done() {
                    try { addTab(new RevisionAnalysisPanel(get(), title, words, directories), (words ? "Word Pauses — " : "S-Notation — ") + title); }
                    catch (Exception exception) { showError(frame, exception); }
                }
            }.execute();
            return;
        }
        if (kind == AnalysisKind.SUMMARY) {
            new javax.swing.SwingWorker<SummaryAnalysis, Void>() {
                protected SummaryAnalysis doInBackground() { return SummaryAnalysis.analyze(source); }
                protected void done() {
                    try { addTab(new SummaryAnalysisPanel(get(), title, directories), "Summary Analysis — " + title); }
                    catch (Exception exception) { showError(frame, exception); }
                }
            }.execute();
            return;
        }
        new javax.swing.SwingWorker<GeneralAnalysis, Void>() {
            protected GeneralAnalysis doInBackground() throws Exception { return GeneralAnalysis.analyze(source); }
            protected void done() {
                try { addTab(new GeneralAnalysisPanel(get(), title, directories), "General Analysis — " + title); }
                catch (Exception exception) { showError(frame, exception); }
            }
        }.execute();
    }

    void addReplay(ReplayLog replay, String title) {
        ReplayPanel viewer = new ReplayPanel(replay);
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
        ReplayPanel viewer = replays.remove(panel);
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
            showImportReport(loaded);
        } catch (Exception exception) { showError(frame, exception); }
    }

    private void showImportReport(ReplayLog log) {
        Object value = log.metadata.get("inputlogImportReport");
        if (!(value instanceof Map)) return;
        Map<?, ?> report = (Map<?, ?>) value;
        StringBuilder message = new StringBuilder("Imported Inputlog IDFX.\n\n");
        for (String section : java.util.List.of("inferredEdits", "warnings")) {
            Object items = report.get(section);
            if (items instanceof java.util.List) for (Object item : (java.util.List<?>) items) {
                message.append("• ").append(item).append("\n\n");
            }
        }
        JTextArea details = new JTextArea(message.toString(), 16, 65);
        details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true);
        details.setCaretPosition(0);
        JOptionPane.showMessageDialog(frame, new JScrollPane(details), "Inputlog import report", JOptionPane.INFORMATION_MESSAGE);
    }

    private static LogFormat primaryFormat(java.util.Set<LogFormat> formats) {
        for (LogFormat format : LogFormat.values()) if (formats.contains(format)) return format;
        throw new IllegalArgumentException("Select a save format");
    }

    boolean save(DocumentTab document, boolean saveAs) { return save(document, saveAs, () -> { }); }

    boolean save(DocumentTab document, boolean saveAs, Runnable afterSave) {
        if (document == null || document.saving) return false;
        java.util.Set<LogFormat> selectedFormats = directories.formats();
        LogFormat primary = primaryFormat(selectedFormats);
        Path path = document.savedPath == null ? null : primary.path(document.savedPath);
        if (saveAs || path == null) {
            Path directory = directories.directory("save");
            saveChooser.setCurrentDirectory(directory.toFile());
            saveChooser.setSelectedFile(primary.path(directory.resolve(path == null
                    ? document.filter.session.automaticPath.getFileName().toString() : path.getFileName().toString())).toFile());
            if (saveChooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return false;
            path = primary.path(saveChooser.getSelectedFile().toPath());
            for (LogFormat format : selectedFormats) {
                Path output = format.path(path);
                if (Files.exists(output) && JOptionPane.showConfirmDialog(frame,
                        "Replace " + output + "?", "Save Log", JOptionPane.YES_NO_OPTION)
                        != JOptionPane.YES_OPTION) return false;
            }
        }
        try {
            for (RecordingSession recording : recordings) {
                for (LogFormat format : selectedFormats) {
                    Path output = format.path(path), automatic = format.path(recording.automaticPath);
                    if (output.toAbsolutePath().normalize().equals(automatic.toAbsolutePath().normalize())
                            || (Files.exists(output) && Files.exists(automatic) && Files.isSameFile(output, automatic))) {
                        throw new IOException("Choose a filename other than an active automatic log.");
                    }
                }
            }
            final Path target = path;
            document.filter.session.setFormats(selectedFormats);
            document.filter.session.setIdfxExtensions(directories.idfxExtensions());
            SaveSnapshot snapshot = document.filter.session.snapshot();
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
        if (document.filter.session.eventCount() != document.savedEntries) {
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
        saver.automatic(document.filter.session);
        documents.remove(document);
        tabs.remove(document);
        document.filter.session.onRecord = () -> { };
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
