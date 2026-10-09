package se.lu.scriptloglite;

import java.awt.event.KeyEvent;
import java.time.Instant;
import javax.swing.event.CaretEvent;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

/** Captures Swing edit, caret, and key events for a recording session. */
public class LoggingFilter extends DocumentFilter {
    private final String strokePrefix = "sll:" + java.util.UUID.randomUUID() + ":";
    private long strokeSequence;
    private final java.util.Map<String, String> activeStrokes = new java.util.HashMap<>();
    final RecordingSession session;
    boolean restoring;

    public LoggingFilter() { this(new RecordingSession()); }
    LoggingFilter(RecordingSession session) { this.session = session; }
    Instant timestamp() { return session.timestamp(); }
    void append(LogEvent event) { if (!restoring) session.append(event); }
    void startSession(String text) { append(new SessionEvent(timestamp(), text)); }
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
        String identity = event.getKeyCode() + ":" + event.getKeyLocation();
        String stroke = method.equals("keyPressed") ? strokePrefix + (++strokeSequence) : activeStrokes.remove(identity);
        if (method.equals("keyPressed")) activeStrokes.put(identity, stroke);
        append(new KeyLogEvent(timestamp(), EventType.named(method), event.getKeyCode(),
                KeyEvent.getKeyText(event.getKeyCode()), event.getKeyChar() == KeyEvent.CHAR_UNDEFINED
                ? "undefined" : String.valueOf(event.getKeyChar()), event.getModifiersEx(),
                KeyEvent.getModifiersExText(event.getModifiersEx()), event.getKeyLocation(), stroke));
    }
    static String quote(String text) { return text == null ? "null" : Json.quote(text); }
}
