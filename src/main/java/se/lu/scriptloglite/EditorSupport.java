package se.lu.scriptloglite;

import java.awt.Point;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import javax.swing.JOptionPane;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.time.Instant;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;

/** Builds recording editors and restores their text, selection, and scrolling. */
final class EditorSupport {
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

    static void showError(java.awt.Component frame, Exception exception) {
        JOptionPane.showMessageDialog(frame, exception.getMessage(), "Log error",
                JOptionPane.ERROR_MESSAGE);
    }

    static void restoreLog(JTextArea text, LoggingFilter filter, ReplayLog loaded) {
        if (!loaded.metadata.isEmpty() && !loaded.metadata.containsKey("recordingStartTime")) {
            // Rebase foreign clocks for continuation. IDFX retains its trailing idle
            // interval; existing JSON/raw imports retain their last-event anchor.
            Instant originalStart = loaded.states.get(0).time;
            Instant originalEnd = loaded.states.get(loaded.states.size() - 1).time;
            Duration duration = Duration.between(originalStart, originalEnd);
            if ("Inputlog IDFX".equals(loaded.metadata.get("sourceFormat"))
                    && loaded.metadata.containsKey("endTime") && loaded.metadata.containsKey("startTime")) {
                Duration recorded = Duration.ofNanos(Math.subtractExact(JsonLogCodec.number(loaded.metadata, "endTime"),
                        JsonLogCodec.number(loaded.metadata, "startTime")));
                if (recorded.compareTo(duration) > 0) duration = recorded;
            }
            Instant newStart = Instant.now().minus(duration);
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
        filter.session.restore(loaded, state.time);
    }

    static JTextArea createTextArea(LoggingFilter filter) {
        JTextArea text = new JTextArea(12, 50);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 16));
        text.setMargin(new java.awt.Insets(12, 12, 12, 12));
        filter.session.putMetadataIfAbsent("fontFamily", text.getFont().getFamily());
        filter.session.putMetadataIfAbsent("fontSize", text.getFont().getSize());
        text.addComponentListener(new java.awt.event.ComponentAdapter() {
            private void capture() {
                synchronized (filter.session) {
                    JScrollPane pane = (JScrollPane) text.getClientProperty("logScrollPane");
                    javax.swing.JViewport viewport = pane == null ? null : pane.getViewport();
                    Dimension size = viewport == null ? text.getSize() : viewport.getExtentSize();
                    java.awt.Component area = viewport == null ? text : viewport;
                    Point location = area.isShowing() ? area.getLocationOnScreen() : area.getLocation();
                    filter.session.updateMetadata(java.util.Map.of("TextAreaWidth", size.width,
                            "TextAreaHeight", size.height, "TextAreaX", location.x, "TextAreaY", location.y));
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

    static void edit(DocumentEdit action) {
        try {
            action.run();
        } catch (BadLocationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    interface DocumentEdit {
        void run() throws BadLocationException;
    }
}
