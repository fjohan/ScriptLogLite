package se.lu.scriptloglite;

import se.lu.scriptloglite.inputlog.GeneralAnalysisChecks;
import se.lu.scriptloglite.inputlog.SummaryAnalysisChecks;
import se.lu.scriptloglite.inputlog.RevisionAnalysisChecks;
import se.lu.scriptloglite.inputlog.LinearAnalysisChecks;
import se.lu.scriptloglite.inputlog.PauseAnalysisChecks;

import java.awt.Point;
import java.awt.Dimension;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.Action;
import java.time.Instant;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.AbstractDocument;
import static se.lu.scriptloglite.EditorSupport.createScrollPane;
import static se.lu.scriptloglite.EditorSupport.applyScroll;
import static se.lu.scriptloglite.EditorSupport.restoreLog;
import static se.lu.scriptloglite.EditorSupport.createTextArea;
import static se.lu.scriptloglite.RecordingPaths.workingRoot;
import static se.lu.scriptloglite.RecordingPaths.workingDirectory;
import static se.lu.scriptloglite.RecordingPaths.allocateRecording;
import static se.lu.scriptloglite.RecordingPaths.nextLogFile;

public final class ScriptLogLiteChecks {
    public static void main(String[] args) throws Exception { testReplay(); }

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
            ReplayPanel window = new ReplayPanel(replay);
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
        testRawFormat();
        InputlogChecks.run();
        GeneralAnalysisChecks.run();
        SummaryAnalysisChecks.run();
        RevisionAnalysisChecks.run();
        LinearAnalysisChecks.run();
        PauseAnalysisChecks.run();
        testThemes();
        System.out.println("Replay self-test passed");
    }

    static void testRawFormat() throws Exception {
        Path sample = Path.of("exp_subj_raw_1.txt");
        if (Files.exists(sample)) {
            ReplayLog raw = ReplayLog.load(sample, -1);
            ReplayLog json = ReplayLog.load(Path.of("exp_subj_json_1.json"), -1);
            checkHistory(json, raw, "raw sample matches JSON sample");
            check(raw.metadata.equals(json.metadata), "raw and JSON sample headers match");
            String exported = RawLogCodec.export(raw);
            List<String> originalLines = Files.readAllLines(sample);
            List<String> exportedLines = java.util.Arrays.asList(exported.split("\\R"));
            check(originalLines.subList(originalLines.indexOf("#") + 1, originalLines.size())
                    .equals(exportedLines.subList(exportedLines.indexOf("#") + 1, exportedLines.size())),
                    "all sample raw event lines preserved exactly");
        }
        String payload = " A\n\r\t\b\f\u0000\\s\\n😀= #";
        check(RawLogCodec.decode(RawLogCodec.encode(payload)).equals(payload), "raw escaped payload round trip");
        Instant start = Instant.EPOCH;
        ReplayLog log = new ReplayLog(List.of(new SessionEvent(start, ""),
                new EditEvent(start.plusNanos(1), EventType.INSERT, 0, "", payload),
                new CaretLogEvent(start.plusNanos(2), payload.length(), 0),
                new KeyLogEvent(start.plusNanos(3), EventType.KEY_PRESSED, 65, "A", "\n", 2, "Ctrl Shift", 1),
                new EditEvent(start.plusNanos(4), EventType.REPLACE, 0, "", null),
                new EditEvent(start.plusNanos(5), EventType.REPLACE, 0, payload, "")));
        log.metadata.put("fontFamily", "Family With Spaces");
        log.metadata.put("extra", Map.of("note", "spaces and\nnewlines", "values", List.of(1, 2)));
        String rawText = RawLogCodec.export(log);
        ReplayLog restored = RawLogCodec.load(rawText);
        checkHistory(log, restored, "raw reversible edits round trip");
        check(JsonLogCodec.export(log.events, log.metadata).equals(JsonLogCodec.export(restored.events, restored.metadata)),
                "raw preserves null/empty text, header, and keyboard details");
        for (String invalid : List.of("startTime: 0\n", "startTime: 0\n#\n1 0.000 <replace> 0 0 \\q\n",
                "startTime: 0\n#\n1 0.000 <remove> 0 1\n")) {
            try { RawLogCodec.load(invalid); throw new AssertionError("Invalid raw accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        Path folder = Files.createTempDirectory("raw-settings-test");
        try {
            DirectoryHistory preferences = new DirectoryHistory(folder.resolve("preferences.properties"));
            check(preferences.formats().equals(java.util.EnumSet.of(LogFormat.JSON)), "JSON is default save format");
            preferences.rememberFormats(java.util.EnumSet.allOf(LogFormat.class));
            check(new DirectoryHistory(preferences.file).formats().size() == LogFormat.values().length, "save formats persist");
            SaveSnapshot both = new SaveSnapshot(log.events, log.metadata, 1, preferences.formats());
            Path jsonFile = folder.resolve("both.json");
            both.save(jsonFile);
            checkHistory(ReplayLog.load(jsonFile, -1), ReplayLog.load(folder.resolve("both.txt"), -1), "save both formats");
            SaveSnapshot rawOnly = new SaveSnapshot(log.events, log.metadata, 2, java.util.EnumSet.of(LogFormat.RAW));
            rawOnly.save(folder.resolve("raw-only.json"));
            check(Files.exists(folder.resolve("raw-only.txt")) && !Files.exists(folder.resolve("raw-only.json")),
                    "raw-only save writes no JSON");
            SwingUtilities.invokeAndWait(() -> {
                TabbedApplication app = new TabbedApplication(preferences);
                check(app.formatChoices.values().stream().allMatch(javax.swing.JCheckBoxMenuItem::isSelected), "settings reflect persisted formats");
                app.formatChoices.get(LogFormat.JSON).doClick();
                app.formatChoices.get(LogFormat.IDFX).doClick();
                app.formatChoices.get(LogFormat.RAW).doClick();
                check(app.formatChoices.get(LogFormat.RAW).isSelected(), "cannot deselect final format");
            });
        } finally {
            try (java.util.stream.Stream<Path> files = Files.list(folder)) {
                for (Path file : (Iterable<Path>) files::iterator) Files.deleteIfExists(file);
            }
            Files.delete(folder);
        }
        System.out.println("Raw format and save-format settings checks passed");
    }

    static void testRecordingPaths() throws Exception {
        Path previous = workingRoot;
        workingRoot = previous.resolve("allocation");
        java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            check(!Files.exists(workingRoot), "new working directory starts absent");
            check(Files.isDirectory(workingDirectory()), "working directory is created");
            RecordingVariables defaults = new RecordingVariables("exp", "_", "subj");
            java.time.LocalDate day = java.time.LocalDate.of(2026, 10, 8);
            Path first = allocateRecording(defaults, day);
            check(first.equals(workingRoot.resolve("exp_subj/2026-10-08_1/exp_subj_sll_1.json")),
                    "default automatic log hierarchy");
            Files.writeString(first, "existing recording");
            Path second = allocateRecording(defaults, day);
            check(second.getFileName().toString().equals("exp_subj_sll_1.json"), "new folders start with file index one");
            Path collision = nextLogFile(first.getParent(), defaults.prefix());
            check(collision.getFileName().toString().equals("exp_subj_sll_2.json"), "existing file increments file index");
            Files.writeString(collision, "second file");
            check(nextLogFile(first.getParent(), defaults.prefix()).getFileName().toString().equals("exp_subj_sll_3.json"),
                    "file index skips occupied names in the same directory");
            Path third = allocateRecording(new RecordingVariables("exp", "_", "subj"), day.plusDays(1));
            check(third.getParent().getFileName().toString().equals("2026-10-09_1"),
                    "new date starts numbering at one");
            Files.createDirectory(first.getParent().getParent().resolve("2026-10-08_26"));
            check(allocateRecording(defaults, day).getParent().getFileName().toString().equals("2026-10-08_27"),
                    "same-date numbering continues from the highest existing index");
            List<java.util.concurrent.Future<Path>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(threads.submit(() -> allocateRecording(defaults, day.plusDays(1))));
            java.util.Set<Path> reserved = new java.util.HashSet<>();
            for (java.util.concurrent.Future<Path> future : futures) reserved.add(future.get());
            check(reserved.size() == 4, "concurrent recordings reserve distinct directories");
            check(allocateRecording(defaults, day.plusDays(1)).getParent().getFileName().toString().equals("2026-10-09_6"),
                    "each date continues its own index across allocations");
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
            ReplayPanel window = new ReplayPanel(log);
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
            ((ReplayPanel.ReplayCaret) window.text.getCaret()).focusLost(new java.awt.event.FocusEvent(
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
                tab.filter.session.automaticPath = directory.resolve("automatic.json");
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
                check(tab.filter.session.events().stream().allMatch(event -> event instanceof LogEvent), "typed recording");
                capturedEntries[0] = tab.filter.session.eventCount();
                check(app.save(tab, false), "named save queued without blocking EDT");
                tab.text.append(" then edited");
                app.saver.automatic(tab.filter.session);
                for (int i = 0; i < 20; i++) {
                    tab.text.append("!");
                    app.saver.automatic(tab.filter.session);
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
                check(!tab.filter.session.dirty, "latest automatic revision persisted");
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
                document[0].savedEntries = document[0].filter.session.eventCount();
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
                TabbedApplication app = new TabbedApplication(new DirectoryHistory(workingDirectory().resolve("test-preferences.properties")));
                DocumentTab document = app.addDocument(null, null);
                document.text.setText("Theme switching preserves the document");
                document.text.setCaretPosition(2);
                document.text.moveCaretPosition(12);
                List<LogEvent> before = new ArrayList<>(document.filter.session.events());
                ReplayPanel replay = new ReplayPanel(ReplayLog.parse(before));
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
                    check(document.filter.session.events().equals(before), "theme creates no log events");
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
                    TabbedApplication app = new TabbedApplication(new DirectoryHistory(workingDirectory().resolve("test-preferences.properties")));
                    background[0] = app.saver;
                    app.tabs.setSize(1000, 700);
                    DocumentTab first = app.addDocument(null, null);
                    DocumentTab second = app.addDocument(null, null);
                    first.filter.session.automaticPath = directory.resolve("first-auto.json");
                    second.filter.session.automaticPath = directory.resolve("second-auto.json");
                    first.text.setText("First document");
                    second.text.setText("Second document");
                    app.activate(first);
                    check(app.activeDocument() == first, "active document selection");
                    first.insert();
                    check(first.text.getText().endsWith("Hello")
                            && second.text.getText().equals("Second document"), "document editing isolation");
                    first.filter.session.saveAutomatic();
                    second.filter.session.saveAutomatic();
                    check(ReplayLog.load(first.filter.session.automaticPath, -1).states.get(
                            first.filter.session.eventCount() - 1).text.equals(first.text.getText()),
                            "first automatic log");
                    check(ReplayLog.load(second.filter.session.automaticPath, -1).states.get(
                            second.filter.session.eventCount() - 1).text.equals(second.text.getText()),
                            "second automatic log");
                    Path saved = directory.resolve("saved.json");
                    first.filter.session.save(saved);
                    DocumentTab reopened = app.addDocument(ReplayLog.load(saved, -1), saved);
                    reopened.filter.session.automaticPath = directory.resolve("reopened-auto.json");
                    check(reopened.text.getText().equals(first.text.getText()) && app.documents.size() == 3,
                            "open adds a document without replacing other documents");
                    reopened.text.append(" continued");
                    check(!first.text.getText().endsWith("continued"), "reopened log is independent");
                    ReplayPanel replay = new ReplayPanel(ReplayLog.parse(first.filter.session.events()));
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
                    second.savedEntries = second.filter.session.eventCount();
                    app.activate(first);
                    JPanel secondHeader = (JPanel) app.tabs.getTabComponentAt(app.tabs.indexOfComponent(second));
                    ((JButton) secondHeader.getComponent(1)).doClick();
                    check(app.tabs.indexOfComponent(second) == -1 && app.activeDocument() == first,
                            "close button closes its own tab while preserving the selected document");
                    for (DocumentTab document : new ArrayList<>(app.documents)) {
                        document.savedEntries = document.filter.session.eventCount();
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
            ReplayLog roundtrip = JsonLogCodec.load(JsonLogCodec.export(imported.events, imported.metadata));
            checkHistory(imported, roundtrip, "sample JSON round trip");
            List<?> originalJson = (List<?>) new Json(Files.readString(sample)).parse();
            List<?> exportedJson = (List<?>) new Json(JsonLogCodec.export(imported.events, imported.metadata)).parse();
            check(originalJson.get(1).equals(exportedJson.get(1)), "sample event JSON fields preserved exactly");
            check(roundtrip.metadata.get("fontFamily").equals("Calibri"), "sample metadata preserved");
            check(JsonLogCodec.number(roundtrip.metadata, "startTime") == 179610304410659L,
                    "nanosecond precision preserved");
            check(JsonLogCodec.number(roundtrip.metadata, "endTime") == 179743787869526L,
                    "sample endTime preserved");
            SwingUtilities.invokeAndWait(() -> {
                LoggingFilter filter = new LoggingFilter();
                JTextArea text = createTextArea(filter);
                restoreLog(text, filter, imported);
                filter.append(new KeyLogEvent(filter.timestamp(), EventType.KEY_PRESSED, 65, null, null, null, null, null));
                ReplayLog continued = ReplayLog.parse(filter.session.events());
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
            ReplayLog replay = ReplayLog.parse(new ArrayList<>(filter.session.events()));
            ReplayState state = replay.states.get(replay.states.size() - 1);
            check(state.scrollX == 0 && state.scrollY == 120, "wrapped recording logs vertical scroll");
            restoreLog(text, filter, replay);
            check(scroll.getViewport().getViewPosition().equals(new Point(0, 120)),
                    "open restores scroll position");
            checkHistory(replay, ReplayLog.parse(filter.session.events()), "scroll restoration is not logged");
            ReplayPanel window = new ReplayPanel(replay);
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
        Path saved = directory.resolve("saved.json");
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
                    checkHistory(ReplayLog.parse(original), ReplayLog.parse(filter.session.events()), "restore creates no synthetic events");
                    filter.session.save(saved);
                    checkHistory(ReplayLog.parse(original), ReplayLog.load(saved, -1), "save preserves history");

                    LoggingFilter continued = new LoggingFilter();
                    JTextArea next = createTextArea(continued);
                    restoreLog(next, continued, ReplayLog.load(saved, -1));
                    ((AbstractDocument) next.getDocument()).replace(0, special.length(), "Continued", null);
                    next.setCaretPosition(2);
                    next.moveCaretPosition(6);
                    continued.session.save(saved);
                    ReplayLog roundtrip = ReplayLog.load(saved, -1);
                    ReplayState finalState = roundtrip.states.get(roundtrip.states.size() - 1);
                    check(finalState.text.equals("Continued") && finalState.dot == 6
                            && finalState.mark == 2, "save after continued editing");
                    checkHistory(ReplayLog.parse(original), roundtrip, "continued save retains original events");
                    int previous = roundtrip.previousEdit(roundtrip.states.size() - 1);
                    check(roundtrip.states.get(previous).text.equals(special),
                            "reverse replay crosses the save/open boundary");
                    restoreLog(next, continued, roundtrip);
                    check(next.getText().equals("Continued") && continued.session.eventCount() == roundtrip.events.size(),
                            "repeated reopen");
                    try {
                        continued.session.save(directory.resolve("missing").resolve("failed.log"));
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
}
