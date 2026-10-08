package se.lu.scriptloglite;

import java.time.Instant;

final class CaretLogEvent extends LogEvent {
    final int dot, mark;
    CaretLogEvent(Instant time, int dot, int mark) { super(time, EventType.CARET); this.dot = dot; this.mark = mark; }
    LogEvent at(Instant time) { return new CaretLogEvent(time, dot, mark); }
    String description() { return type.name + " dot=" + dot + " mark=" + mark; }
}
