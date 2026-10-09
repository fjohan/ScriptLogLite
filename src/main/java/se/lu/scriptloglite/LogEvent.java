package se.lu.scriptloglite;

import java.time.Instant;

/** Immutable typed event shared by recording, replay and analysis packages. */
public abstract class LogEvent {
    public final Instant time;
    public final EventType type;
    LogEvent(Instant time, EventType type) { this.time = time; this.type = type; }
    abstract LogEvent at(Instant time);
    String description() { return type.name; }
}
