package se.lu.scriptloglite;

import java.util.Map;

/** Applies and reverses edits using one mutable text buffer. */
public final class ReplayCursor {
    final ReplayLog log;
    final StringBuilder text;
    String cachedText;
    int position;
    public ReplayCursor(ReplayLog log) {
        this.log = log; text = new StringBuilder(log.initialText); cachedText = log.initialText;
    }
    public void seek(int target) {
        if (target < 0 || target >= log.events.size()) throw new IndexOutOfBoundsException("Replay position " + target);
        Map.Entry<Integer, String> checkpoint = log.checkpoints.floorEntry(target);
        if (Math.abs(target - position) > target - checkpoint.getKey()) {
            text.setLength(0); text.append(checkpoint.getValue());
            cachedText = checkpoint.getValue(); position = checkpoint.getKey();
        }
        while (position < target) {
            LogEvent event = log.events.get(++position);
            if (event instanceof EditEvent) { ((EditEvent) event).apply(text); cachedText = null; }
        }
        while (position > target) {
            LogEvent event = log.events.get(position--);
            if (event instanceof EditEvent) { ((EditEvent) event).undo(text); cachedText = null; }
        }
    }
    public int textLength() { return text.length(); }
    public ReplayState state() {
        if (cachedText == null) cachedText = text.toString();
        return new ReplayState(log.events.get(position), cachedText, log.views.get(position));
    }
}
