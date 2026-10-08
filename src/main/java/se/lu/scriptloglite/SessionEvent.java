package se.lu.scriptloglite;

import java.time.Instant;

final class SessionEvent extends LogEvent {
    final String initialText;
    SessionEvent(Instant time, String initialText) { super(time, EventType.SESSION); this.initialText = initialText; }
    LogEvent at(Instant time) { return new SessionEvent(time, initialText); }
}
