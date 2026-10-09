package se.lu.scriptloglite;

import java.time.Instant;

public final class ReplayState {
    final Instant time;
    public final String text;
    final int dot, mark, scrollX, scrollY;
    final String description;
    final boolean edit;
    ReplayState(LogEvent event, String text, ViewState view) {
        time = event.time; this.text = text; dot = view.dot; mark = view.mark;
        scrollX = view.x; scrollY = view.y; description = event.description(); edit = event instanceof EditEvent;
    }
}
