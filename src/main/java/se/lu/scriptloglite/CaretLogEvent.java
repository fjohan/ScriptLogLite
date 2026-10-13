package se.lu.scriptloglite;

import java.time.Instant;

public final class CaretLogEvent extends LogEvent {
    public final int dot, mark;
    public final boolean generated;
    public CaretLogEvent(Instant time, int dot, int mark) { this(time, dot, mark, false); }
    public CaretLogEvent(Instant time, int dot, int mark, boolean generated) { super(time, EventType.CARET); this.dot = dot; this.mark = mark; this.generated = generated; }
    LogEvent at(Instant time) { return new CaretLogEvent(time, dot, mark, generated); }
    String description() { return type.name + " dot=" + dot + " mark=" + mark; }
}
