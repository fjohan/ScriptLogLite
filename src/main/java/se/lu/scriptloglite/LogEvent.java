package se.lu.scriptloglite;

import java.time.Instant;

abstract class LogEvent {
    final Instant time;
    final EventType type;
    LogEvent(Instant time, EventType type) { this.time = time; this.type = type; }
    abstract LogEvent at(Instant time);
    String description() { return type.name; }
}
