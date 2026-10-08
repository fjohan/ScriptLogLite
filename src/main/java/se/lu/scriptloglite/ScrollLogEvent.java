package se.lu.scriptloglite;

import java.time.Instant;

final class ScrollLogEvent extends LogEvent {
    final int x, y;
    ScrollLogEvent(Instant time, int x, int y) { super(time, EventType.SCROLL); this.x = x; this.y = y; }
    LogEvent at(Instant time) { return new ScrollLogEvent(time, x, y); }
    String description() { return type.name + " viewX=" + x + " viewY=" + y; }
}
