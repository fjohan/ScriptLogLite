package se.lu.scriptloglite;

import java.awt.Point;
import java.awt.Dimension;
import java.time.Duration;
import javax.swing.JComboBox;
import javax.swing.Timer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import static se.lu.scriptloglite.EditorSupport.applyScroll;

/** Read-only replay display, recorded layout, playback controls, and timeline. */
class ReplayPanel {
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

    ReplayPanel(ReplayLog log) {
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
}
