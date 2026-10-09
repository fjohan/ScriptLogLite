package se.lu.scriptloglite;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds analysis inputs from typed events; metadata never supplies edits or keyboard state. */
final class InternalGeneralEvents {
    private static final class Item {
        int index;
        GeneralAnalysis.Source context;
        long time;
        Item(int index, long time) { this.index = index; this.time = time; }
        Item(GeneralAnalysis.Source context, long time) { this.index = -1; this.context = context; this.time = time; }
    }
    static List<GeneralAnalysis.Source> build(ReplayLog log) {
        Map<Integer, Integer> releases = KeyPairs.pair(log.events);
        Map<Integer, Integer> owners = new HashMap<>();
        Map<Integer, EditEvent> actions = new HashMap<>();
        int candidate = -1;
        Set<Integer> associationHeld = new HashSet<>();
        Map<Integer, Integer> modifiersAtPress = new HashMap<>();
        for (int i = 1; i < log.events.size(); i++) {
            LogEvent event = log.events.get(i);
            if (event instanceof KeyLogEvent) {
                KeyLogEvent key = (KeyLogEvent) event;
                if (key.type == EventType.KEY_PRESSED) { candidate = i; associationHeld.add(key.keyCode); modifiersAtPress.put(i, key.modifiers == null ? inferredModifiers(associationHeld) : key.modifiers); }
                else associationHeld.remove(key.keyCode);
            } else if (event instanceof EditEvent && candidate >= 0) {
                KeyLogEvent key = (KeyLogEvent) log.events.get(candidate);
                EditEvent edit = (EditEvent) event;
                // Only join immediate ordinary typing/backspace; selections, paste and
                // replacements remain separate edit rows. No character times are invented.
                int modifiers = modifiersAtPress.get(candidate);
                boolean character = edit.removed.isEmpty() && edit.replacement().length() == 1
                        && key.keyCode != KeyEvent.VK_BACK_SPACE && textKey(key) && !isModifier(key.keyCode)
                        && !List.of(KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT).contains(key.keyCode);
                String observed = key.keyCode == KeyEvent.VK_ENTER ? "\n" : key.keyChar;
                if (character && observed != null && !observed.equals("undefined") && !observed.equals(edit.replacement())) character = false;
                boolean back = key.keyCode == KeyEvent.VK_BACK_SPACE && edit.removed.length() == 1 && edit.replacement().isEmpty();
                if ((character || back) && (modifiers & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK)) == 0
                        && Duration.between(key.time, edit.time).toMillis() <= 100) {
                    owners.put(i, candidate); actions.put(candidate, edit);
                }
                candidate = -1;
            }
        }
        List<Item> timeline = new ArrayList<>();
        for (int i = 0; i < log.events.size(); i++) timeline.add(new Item(i, millis(log, log.events.get(i))));
        Object meta = log.metadata.get("inputlogMeta");
        if (meta instanceof Map && ((Map<?, ?>) meta).containsKey("__LogRelativeCreationDate")) {
            long origin = Long.parseLong(((Map<?, ?>) meta).get("__LogRelativeCreationDate").toString());
            for (GeneralAnalysis.Source context : GeneralAnalysis.sourceRecords(log.metadata.get("inputlogAncillaryEvents"))) {
                if (!context.type.equals("mouse") && !context.type.equals("focus")) continue;
                long start = context.start() - origin + 1, end = context.end() == 0 ? 0 : context.end() - origin + 1;
                context.win.put("startTime", Long.toString(start)); context.win.put("endTime", Long.toString(end));
                timeline.add(new Item(context, start));
            }
        }
        timeline.sort(java.util.Comparator.comparingLong(item -> item.time));
        ReplayCursor cursor = new ReplayCursor(log);
        List<GeneralAnalysis.Source> result = new ArrayList<>();
        Set<Integer> held = new HashSet<>();
        int produced = log.initialText.length(), dot = 0, mark = 0;
        boolean anyKey = false;
        for (Item item : timeline) {
            GeneralAnalysis.Source source;
            if (item.context != null) {
                source = item.context;
                source.word.put("position", Integer.toString(Math.min(dot, mark)));
                source.word.put("documentLength", Integer.toString(cursor.text.length() + 1));
            } else {
                LogEvent event = log.events.get(item.index);
                source = new GeneralAnalysis.Source(); source.index = item.index;
                source.type = "selection"; // View/release/session markers reserve IDs but are not GA action rows.
                if (event instanceof KeyLogEvent) {
                    KeyLogEvent key = (KeyLogEvent) event;
                    if (key.type == EventType.KEY_PRESSED) {
                        anyKey = true; held.add(key.keyCode); source.type = "keyboard";
                        source.win.put("key", virtualKey(key));
                        EditEvent action = actions.get(item.index);
                        String value = key.keyChar;
                        if (value == null || value.equals("undefined")) value = action == null ? "" : action.replacement();
                        if (key.keyCode == KeyEvent.VK_BACK_SPACE) value = "\b";
                        if (key.keyCode == KeyEvent.VK_ENTER) value = "\n";
                        source.win.put("value", escaped(value));
                        source.win.put("startTime", Long.toString(item.time));
                        source.win.put("endTime", releases.containsKey(item.index) ? Long.toString(millis(log, log.events.get(releases.get(item.index)))) : "0");
                        int mods = key.modifiers == null ? inferredModifiers(held) : key.modifiers;
                        if (key.keyCode == KeyEvent.VK_SHIFT) mods &= ~InputEvent.SHIFT_DOWN_MASK;
                        if (key.keyCode == KeyEvent.VK_CONTROL) mods &= ~InputEvent.CTRL_DOWN_MASK;
                        if (key.keyCode == KeyEvent.VK_ALT) mods &= ~InputEvent.ALT_DOWN_MASK;
                        source.win.put("keyboardstate", modifierNames(mods));
                        int position = action == null ? Math.min(dot, mark) : action.offset
                                + (key.keyCode == KeyEvent.VK_BACK_SPACE ? action.removed.length() : 0);
                        source.word.put("position", Integer.toString(position));
                        source.word.put("documentLength", Integer.toString(cursor.text.length() + 1));
                    } else held.remove(key.keyCode);
                } else if (event instanceof EditEvent) {
                    EditEvent edit = (EditEvent) event;
                    cursor.seek(item.index);
                    produced += edit.replacement().length();
                    dot = Math.min(dot, cursor.text.length()); mark = Math.min(mark, cursor.text.length());
                    if (!owners.containsKey(item.index)) {
                        source.type = edit.removed.isEmpty() ? "insert" : "replacement";
                        source.word.put("position", Integer.toString(edit.offset));
                        source.word.put("documentLength", Integer.toString(cursor.text.length() + 1));
                        source.word.put("start", Integer.toString(edit.offset));
                        source.word.put("end", Integer.toString(edit.offset + edit.removed.length()));
                        source.word.put("newtext", escaped(edit.replacement()));
                        source.word.put("before", escaped(edit.replacement()));
                        source.win.put("startTime", Long.toString(item.time));
                        source.win.put("endTime", Long.toString(item.time));
                    }
                } else if (event instanceof ScrollLogEvent) {
                    ScrollLogEvent scroll = (ScrollLogEvent) event; source.type = "scrollChange";
                    source.win.put("x", Integer.toString(scroll.x)); source.win.put("y", Integer.toString(scroll.y));
                    source.win.put("startTime", Long.toString(item.time)); source.win.put("endTime", Long.toString(item.time));
                    source.word.put("position", Integer.toString(Math.min(dot, mark)));
                    source.word.put("documentLength", Integer.toString(cursor.text.length() + 1));
                } else if (event instanceof CaretLogEvent) {
                    CaretLogEvent caret = (CaretLogEvent) event; dot = caret.dot; mark = caret.mark;
                }
            }
            source.production = anyKey || produced > 0 ? produced + 1 : 0;
            source.id = Integer.toString(result.size()); result.add(source);
        }
        return result;
    }
    private static long millis(ReplayLog log, LogEvent event) { return Duration.between(log.events.get(0).time, event.time).toMillis() + 1; }
    private static boolean textKey(KeyLogEvent key) {
        if (key.keyChar != null && key.keyChar.length() == 1 && !Character.isISOControl(key.keyChar.charAt(0))) return true;
        int code = key.keyCode;
        return code == KeyEvent.VK_ENTER || code == KeyEvent.VK_TAB || code == KeyEvent.VK_SPACE
                || code >= KeyEvent.VK_0 && code <= KeyEvent.VK_Z || code >= KeyEvent.VK_NUMPAD0 && code <= KeyEvent.VK_DIVIDE
                || code >= 0x01000000 || List.of(KeyEvent.VK_COMMA, KeyEvent.VK_PERIOD, KeyEvent.VK_MINUS,
                KeyEvent.VK_EQUALS, KeyEvent.VK_SLASH, KeyEvent.VK_BACK_SLASH, KeyEvent.VK_QUOTE,
                KeyEvent.VK_BACK_QUOTE, KeyEvent.VK_SEMICOLON, KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_CLOSE_BRACKET).contains(code);
    }
    private static boolean isModifier(int code) { return List.of(KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META, KeyEvent.VK_ALT_GRAPH).contains(code); }
    private static int inferredModifiers(Set<Integer> held) {
        int result = 0;
        if (held.contains(KeyEvent.VK_SHIFT)) result |= InputEvent.SHIFT_DOWN_MASK;
        if (held.contains(KeyEvent.VK_CONTROL)) result |= InputEvent.CTRL_DOWN_MASK;
        if (held.contains(KeyEvent.VK_ALT) || held.contains(KeyEvent.VK_ALT_GRAPH)) result |= InputEvent.ALT_DOWN_MASK;
        if (held.contains(KeyEvent.VK_META)) result |= InputEvent.META_DOWN_MASK;
        return result;
    }
    private static String modifierNames(int modifiers) {
        StringBuilder names = new StringBuilder();
        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) names.append("VK_LSHIFT ");
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) names.append("VK_LCONTROL ");
        if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) names.append("VK_LMENU ");
        return names.toString();
    }
    private static String virtualKey(KeyLogEvent key) {
        if (key.keyText != null && key.keyText.startsWith("VK_")) return key.keyText;
        switch (key.keyCode) {
            case KeyEvent.VK_ENTER: return "VK_RETURN";
            case KeyEvent.VK_BACK_SPACE: return "VK_BACK";
            case KeyEvent.VK_SHIFT: return "VK_LSHIFT";
            case KeyEvent.VK_CONTROL: return "VK_LCONTROL";
            case KeyEvent.VK_ALT: return "VK_LMENU";
            default:
                if (key.keyCode >= 65 && key.keyCode <= 90 || key.keyCode >= 48 && key.keyCode <= 57) return "VK_" + (char) key.keyCode;
                return "VK_" + KeyEvent.getKeyText(key.keyCode).toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
        }
    }
    private static String escaped(String value) {
        // These analysis inputs are already decoded strings. Protect literal backslashes
        // from the compatibility formatter's Inputlog escape decoding.
        return value.replace("\\", "\\u005C");
    }
}
