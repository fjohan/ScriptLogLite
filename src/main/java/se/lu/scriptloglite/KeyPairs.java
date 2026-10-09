package se.lu.scriptloglite;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Explicit stroke IDs take precedence; legacy auto-repeat releases close the latest press. */
final class KeyPairs {
    static Map<Integer, Integer> pair(List<LogEvent> events) {
        Map<Integer, Integer> result = new HashMap<>();
        Map<String, Integer> identified = new HashMap<>();
        Map<String, ArrayDeque<Integer>> pending = new HashMap<>();
        for (int i = 0; i < events.size(); i++) if (events.get(i) instanceof KeyLogEvent) {
            KeyLogEvent key = (KeyLogEvent) events.get(i);
            String identity = key.keyCode + ":" + key.keyLocation;
            ArrayDeque<Integer> queue = pending.computeIfAbsent(identity, ignored -> new ArrayDeque<>());
            if (key.type == EventType.KEY_PRESSED) {
                queue.add(i); if (key.strokeId != null) identified.put(key.strokeId, i);
            } else {
                Integer press = key.strokeId == null ? queue.pollLast() : identified.remove(key.strokeId);
                if (press != null && !result.containsKey(press)) {
                    result.put(press, i);
                    KeyLogEvent original = (KeyLogEvent) events.get(press);
                    pending.get(original.keyCode + ":" + original.keyLocation).remove(press);
                }
            }
        }
        return result;
    }
}
