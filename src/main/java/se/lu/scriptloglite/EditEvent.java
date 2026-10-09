package se.lu.scriptloglite;

import java.time.Instant;

public final class EditEvent extends LogEvent {
    public final int offset;
    public final String removed, inserted; // null inserted is preserved for JSON str:null
    public EditEvent(Instant time, EventType type, int offset, String removed, String inserted) {
        super(time, type);
        this.offset = offset; this.removed = removed; this.inserted = inserted;
    }
    public String replacement() { return inserted == null ? "" : inserted; }
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
