package se.lu.scriptloglite;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** Imports Word text coordinates, not screen coordinates, from Inputlog IDFX files. */
final class InputlogImporter {
    private static final Pattern CHARACTER_REFERENCE = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    private final List<Element> source;
    private final List<LogEvent> events = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> inferred = new ArrayList<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final List<Object> ancillary = new ArrayList<>();
    private final List<Map<String, Object>> generalEvents = new ArrayList<>();
    private final Map<Map<String, Object>, LogEvent> generalLinks = new java.util.IdentityHashMap<>();
    private final StringBuilder text = new StringBuilder();
    private final Instant epoch;
    private final Map<String, Object> exportedHeader;
    private final long clock;
    private Instant causalTime;
    private long lastClock;
    private int dot, mark, missingReleases, externalKeys;
    private final String mainDocument;
    private String lastKey = "";
    private boolean lastControl;

    private InputlogImporter(Element root) {
        Map<String, Object> meta = entries(child(root, "meta"));
        mainDocument = meta.getOrDefault("__MainDocument", "").toString().toLowerCase(java.util.Locale.ROOT);
        exportedHeader = exportedHeader(meta);
        epoch = exportedHeader.containsKey("recordingStartTime") ? Instant.parse(exportedHeader.get("recordingStartTime").toString())
                : Instant.ofEpochMilli(Long.parseLong(required(meta, "__LogCreationTimeStamp")));
        clock = Long.parseLong(required(meta, "__LogRelativeCreationDate"));
        lastClock = clock;
        causalTime = epoch;
        source = children(root, "event");
        events.add(new SessionEvent(epoch, ""));
    }

    static ReplayLog load(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
        });
        Element root = builder.parse(new InputSource(new StringReader(sanitize(xml)))).getDocumentElement();
        if (!root.getTagName().equals("log")) throw new IllegalArgumentException("Expected an Inputlog <log> document");
        return new InputlogImporter(root).convert(root);
    }

    private ReplayLog convert(Element root) {
        for (int i = 0; i < source.size(); i++) {
            Element event = source.get(i);
            String type = event.getAttribute("type");
            counts.merge(type, 1, Integer::sum);
            Map<String, Object> general = sourceEvent(event);
            generalEvents.add(general);
            int before = events.size();
            try {
                Element win = part(event, "winlog");
                long start = optionalLong(win, "startTime", 0), end = optionalLong(win, "endTime", 0);
                if (start > 0) lastClock = Math.max(lastClock, start);
                if (end > 0) lastClock = Math.max(lastClock, end);
                // Word-only events have no timestamp; anchor them to the preceding operation.
                if (start > 0 && time(start).isAfter(causalTime)) causalTime = time(start);
                String precise = label(event, InputlogExporter.TIME);
                if (!exportedHeader.isEmpty() && precise != null) causalTime = epoch.plusNanos(Long.parseLong(precise));
                switch (type) {
                    case "keyboard": keyboard(event, win, start, end); break;
                    case "selection":
                        if (!exportedHeader.isEmpty() && label(event, "ScriptLogLite.viewX") != null) {
                            events.add(new ScrollLogEvent(causalTime, Integer.parseInt(label(event, "ScriptLogLite.viewX")),
                                    Integer.parseInt(label(event, "ScriptLogLite.viewY"))));
                        } else if (!exportedHeader.isEmpty() && label(event, "ScriptLogLite.dot") != null) {
                            caret(coordinate(Integer.parseInt(label(event, "ScriptLogLite.dot"))),
                                    coordinate(Integer.parseInt(label(event, "ScriptLogLite.mark"))));
                        } else selection(part(event, "wordlog"));
                        break;
                    case "replacement": replacement(event, i); break;
                    case "insert": insertion(event, i); break;
                    case "mouse": case "focus": case "statistics": ancillary.add(sourceEvent(event)); break;
                    default: throw new IllegalArgumentException("unsupported event type '" + type + "'");
                }
                if (events.size() > before && type.equals("keyboard")) generalLinks.put(general, events.get(before));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Inputlog event " + event.getAttribute("id") + " (" + type + "): "
                        + exception.getMessage(), exception);
            }
        }
        // Key releases overlap later presses. Sorting only the converted history preserves
        // the source-order edit/caret sequence, whose times were made causally monotonic.
        events.sort(Comparator.comparing(event -> event.time));
        ReplayLog log = new ReplayLog(events);
        Map<LogEvent, Integer> indices = new java.util.IdentityHashMap<>();
        for (int i = 0; i < events.size(); i++) indices.put(events.get(i), i);
        for (Map<String, Object> item : generalEvents) {
            LogEvent linked = generalLinks.get(item);
            if (linked != null) item.put("convertedKeyIndex", indices.get(linked));
        }
        warnings.add("Word paragraph marks are represented by LF; the mandatory final Word paragraph mark is omitted.");
        if (exportedHeader.isEmpty()) warnings.add("Untimed Word edits/selections use the preceding event time; selection direction is unavailable.");
        else warnings.add("ScriptLogLite header, precise event times, selection direction and viewport were restored from labelled extensions.");
        warnings.add("Mouse/focus/statistics are retained as source metadata, not replayed; screen coordinates cannot establish document scrolling.");
        if (exportedHeader.isEmpty()) warnings.add("This file supplies no font, line spacing or editor geometry; replay uses ScriptLogLite defaults.");
        if (missingReleases > 0) warnings.add(missingReleases + " keyboard events have no usable release time; no release was invented.");
        if (externalKeys > 0) warnings.add(externalKeys + " keyboard events without Word coordinates occurred outside the main document, confirmed by timestamped focus records; retained as ancillary source activity, not document edits.");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sourceEventCounts", counts);
        report.put("inferredEdits", inferred);
        report.put("warnings", warnings);
        log.metadata.putAll(exportedHeader);
        log.metadata.remove("recordingStartTime");
        log.metadata.put("sourceFormat", "Inputlog IDFX");
        log.metadata.put("inputlogMeta", entries(child(root, "meta")));
        Map<String, Object> session = entries(child(root, "session"));
        log.metadata.put("inputlogSession", session);
        log.metadata.put("inputlogAncillaryEvents", ancillary);
        log.metadata.put("inputlogGeneralEvents", generalEvents);
        log.metadata.put("inputlogGeneralEventCount", events.size());
        log.metadata.put("inputlogImportReport", report);
        log.metadata.put("id_code", session.getOrDefault("Participant", ""));
        log.metadata.put("textLanguage", session.getOrDefault("Text Language", ""));
        log.metadata.putIfAbsent("startTime", 0L);
        log.metadata.putIfAbsent("endTime", Math.multiplyExact(lastClock - clock, 1_000_000L));
        // No recordingStartTime: restoreLog rebases foreign logs when continuing a session.
        return log;
    }

    private void keyboard(Element event, Element win, long start, long end) {
        Element word = part(event, "wordlog");
        if (word == null) {
            if (start <= 0 || !outsideDocument(start)) throw new IllegalArgumentException("keyboard event lacks Word coordinates without confirmed external focus");
            ancillary.add(sourceEvent(event)); externalKeys++; lastKey = ""; lastControl = false;
            return;
        }
        int position = integer(word, "position"), length = integer(word, "documentLength");
        if (length != text.length() + 1) throw new IllegalArgumentException("documentLength " + length
                + " disagrees with reconstructed Word length " + (text.length() + 1)
                + "; pre-existing text or an unsupported edit may be missing");
        position = coordinate(position);
        if (start <= 0) throw new IllegalArgumentException("missing key press time");
        String key = field(win, "key"), value = decode(field(win, "value"));
        if (value.equals("\\w")) value = " ";
        if (key.equals("VK_RETURN") || value.equals("NEWLINE")) value = "\n";
        if (key.equals("VK_BACK") || value.equals("BACKSPACE")) value = "\b";
        int modifiers = modifiers(win);
        Integer location = key.startsWith("VK_L") && isModifier(key) ? KeyEvent.KEY_LOCATION_LEFT
                : key.startsWith("VK_R") && isModifier(key) ? KeyEvent.KEY_LOCATION_RIGHT : KeyEvent.KEY_LOCATION_STANDARD;
        int code = keyCode(key);
        // Keep the Windows VK name even when Swing has no matching key code.
        events.add(new KeyLogEvent(exportedHeader.isEmpty() ? time(start) : causalTime, EventType.KEY_PRESSED, code, key, value,
                modifiers, InputEvent.getModifiersExText(modifiers), location, "idfx:" + event.getAttribute("id")));
        Instant releaseTime = !exportedHeader.isEmpty() && label(event, "ScriptLogLite.releaseElapsedNanos") != null
                ? epoch.plusNanos(Long.parseLong(label(event, "ScriptLogLite.releaseElapsedNanos"))) : time(Math.max(start, end));
        if (end >= start) events.add(new KeyLogEvent(releaseTime, EventType.KEY_RELEASED, code, key, value,
                modifiers, InputEvent.getModifiersExText(modifiers), location, "idfx:" + event.getAttribute("id")));
        else missingReleases++;
        if (dot != position && mark != position) caret(position, position);
        String replay = field(word, "replay");
        if (!replay.equalsIgnoreCase("true") && !replay.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("missing/invalid replay flag");
        }
        if (Boolean.parseBoolean(replay)) {
            if (!exportedHeader.isEmpty() && label(event, "ScriptLogLite.editElapsedNanos") != null) {
                causalTime = epoch.plusNanos(Long.parseLong(label(event, "ScriptLogLite.editElapsedNanos")));
            }
            if (key.equals("VK_BACK")) {
                if (position == 0) throw new IllegalArgumentException("backspace at document start");
                edit(position - 1, position, ""); caret(position - 1, position - 1);
            } else if (key.equals("VK_DELETE")) {
                if (position >= text.length()) throw new IllegalArgumentException("delete outside document");
                edit(position, position + 1, ""); caret(position, position);
            } else {
                if (value.isEmpty() || value.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\t')) {
                    throw new IllegalArgumentException("unsupported replay key " + key);
                }
                edit(position, position, value); caret(position + value.length(), position + value.length());
            }
        }
        lastKey = key;
        lastControl = (modifiers & InputEvent.CTRL_DOWN_MASK) != 0;
    }

    private boolean outsideDocument(long start) {
        String title = null; long latest = Long.MIN_VALUE;
        // Focus notifications can appear after the keys they describe in source order.
        for (Element event : source) if (event.getAttribute("type").equals("focus")) {
            Element win = part(event, "winlog"); long time = optionalLong(win, "startTime", 0);
            if (time > 0 && time <= start && time >= latest) { latest = time; title = field(win, "title").toLowerCase(java.util.Locale.ROOT); }
        }
        return title != null && !title.isEmpty() && !title.equals(mainDocument) && !title.contains("wordlog") && !title.contains("maindoc");
    }

    private void selection(Element word) {
        int start = coordinate(integer(word, "start")), end = coordinate(integer(word, "end"));
        caret(end, start);
    }

    private void replacement(Element event, int index) {
        Element word = part(event, "wordlog");
        int start = integer(word, "start"), end = integer(word, "end");
        String replacement = decode(field(word, "newtext"));
        int expected = nextWordLength(index);
        int normalLength = text.length() - (end - start) + replacement.length();
        // Word may report the surviving final paragraph rather than the range cut.
        // Accept a selected-range cut only when Ctrl+X and the next Word length agree.
        if (lastControl && lastKey.equals("VK_X") && dot != mark && expected >= 0
                && normalLength != expected && text.length() - Math.abs(dot - mark) == expected
                && start == Math.min(dot, mark)) {
            inferred.add("Event " + event.getAttribute("id") + ": Ctrl+X removed selection ["
                    + Math.min(dot, mark) + ", " + Math.max(dot, mark)
                    + "); confirmed by following documentLength " + (expected + 1) + ".");
            edit(Math.min(dot, mark), Math.max(dot, mark), ""); caret(start, start);
            return;
        }
        start = coordinate(start); end = coordinate(end);
        if (start > end) throw new IllegalArgumentException("inverted replacement range");
        if (!text.substring(start, end).equals(replacement)) {
            edit(start, end, replacement); caret(start + replacement.length(), start + replacement.length());
        }
    }

    private void insertion(Element event, int index) {
        Element word = part(event, "wordlog");
        int position = integer(word, "position");
        String before = decode(field(word, "before")), after = decode(field(word, "after"));
        int size = Math.max(before.length(), after.length()), expected = nextWordLength(index);
        if (expected >= 0 && text.length() + size != expected) throw new IllegalArgumentException("insert length contradicts following documentLength");
        boolean useBefore = before.length() == size && position - size == dot;
        boolean useAfter = after.length() == size && position == dot;
        if (useBefore == useAfter) throw new IllegalArgumentException("ambiguous before/after insertion; cannot safely choose pasted text");
        String inserted = useBefore ? before : after;
        int offset = useBefore ? position - size : position;
        coordinate(offset);
        edit(offset, offset, inserted); caret(offset + inserted.length(), offset + inserted.length());
        inferred.add("Event " + event.getAttribute("id") + ": selected '" + (useBefore ? "before" : "after")
                + "' insertion using the preceding caret and resulting position.");
    }

    // Look ahead only through non-edit operations; never use a length from a later edit.
    private int nextWordLength(int index) {
        for (int i = index + 1; i < source.size(); i++) {
            Element event = source.get(i);
            String type = event.getAttribute("type");
            if (type.equals("keyboard") && part(event, "wordlog") != null) return integer(part(event, "wordlog"), "documentLength") - 1;
            if (type.equals("insert") || type.equals("replacement")) break;
        }
        return -1;
    }

    private void edit(int start, int end, String inserted) {
        if (start < 0 || end < start || end > text.length()) throw new IllegalArgumentException("edit range outside document");
        String removed = text.substring(start, end);
        EventType type = removed.isEmpty() ? EventType.INSERT : inserted.isEmpty() ? EventType.REMOVE : EventType.REPLACE;
        events.add(new EditEvent(causalTime, type, start, removed, type == EventType.REMOVE ? null : inserted));
        text.replace(start, end, inserted);
    }
    private void caret(int newDot, int newMark) {
        dot = newDot; mark = newMark;
        events.add(new CaretLogEvent(causalTime, dot, mark));
    }
    private int coordinate(int value) {
        if (value < 0 || value > text.length() + 1) throw new IllegalArgumentException("Word coordinate " + value + " outside document");
        return Math.min(value, text.length());
    }
    private Instant time(long value) {
        if (value < clock) throw new IllegalArgumentException("event precedes log creation");
        return epoch.plusMillis(value - clock);
    }
    private static int modifiers(Element win) {
        int mask = 0;
        for (Element key : children(child(win, "keyboardstate"), "key")) {
            String name = key.getTextContent();
            if (name.contains("SHIFT")) mask |= InputEvent.SHIFT_DOWN_MASK;
            if (name.contains("CONTROL")) mask |= InputEvent.CTRL_DOWN_MASK;
            if (name.contains("MENU")) mask |= InputEvent.ALT_DOWN_MASK;
            if (name.equals("VK_LWIN") || name.equals("VK_RWIN")) mask |= InputEvent.META_DOWN_MASK;
        }
        return mask;
    }
    private static boolean isModifier(String key) { return key.endsWith("SHIFT") || key.endsWith("CONTROL") || key.endsWith("MENU"); }
    private static int keyCode(String name) {
        String key = name.startsWith("VK_") ? name.substring(3) : name;
        switch (key) {
            case "BACK": return KeyEvent.VK_BACK_SPACE;
            case "RETURN": return KeyEvent.VK_ENTER;
            case "LSHIFT": case "RSHIFT": return KeyEvent.VK_SHIFT;
            case "LCONTROL": case "RCONTROL": return KeyEvent.VK_CONTROL;
            case "LMENU": case "RMENU": case "MENU": return KeyEvent.VK_ALT;
            case "OEM_1": return KeyEvent.VK_SEMICOLON;
            case "OEM_2": return KeyEvent.VK_SLASH;
            case "OEM_3": return KeyEvent.VK_BACK_QUOTE;
            case "OEM_4": return KeyEvent.VK_OPEN_BRACKET;
            case "OEM_5": return KeyEvent.VK_BACK_SLASH;
            case "OEM_6": return KeyEvent.VK_CLOSE_BRACKET;
            case "OEM_7": return KeyEvent.VK_QUOTE;
            default:
                try { return KeyEvent.class.getField("VK_" + key).getInt(null); }
                catch (ReflectiveOperationException e) { return KeyEvent.VK_UNDEFINED; }
        }
    }
    private static String decode(String value) {
        Matcher matcher = UNICODE_ESCAPE.matcher(value);
        StringBuffer decoded = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(decoded, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(matcher.group(1), 16))));
        matcher.appendTail(decoded);
        return decoded.toString().replace("\r\n", "\n").replace('\r', '\n');
    }
    // Inputlog writes XML 1.0-illegal backspace references. Preserve their meaning
    // as its own Unicode escaping rather than silently discarding controls.
    private static String sanitize(String xml) {
        if (xml.startsWith("\uFEFF")) xml = xml.substring(1);
        Matcher matcher = CHARACTER_REFERENCE.matcher(xml);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String digits = matcher.group(1);
            int value = Integer.parseInt(digits.startsWith("x") ? digits.substring(1) : digits, digits.startsWith("x") ? 16 : 10);
            if (value < 32 && value != 9 && value != 10 && value != 13) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(String.format("\\u%04X", value)));
            }
        }
        matcher.appendTail(result);
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (c < 32 && c != 9 && c != 10 && c != 13) safe.append(String.format("\\u%04X", (int) c));
            else safe.append(c);
        }
        return safe.toString();
    }
    private static Element child(Element element, String name) {
        for (Element item : children(element, name)) return item;
        return null;
    }
    private static List<Element> children(Element element, String name) {
        List<Element> result = new ArrayList<>();
        if (element != null) for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && (name == null || node.getNodeName().equals(name))) result.add((Element) node);
        }
        return result;
    }
    private static Element part(Element event, String type) {
        for (Element part : children(event, "part")) if (part.getAttribute("type").equals(type)) return part;
        return null;
    }
    private static String field(Element element, String name) {
        Element item = child(element, name);
        if (item == null) throw new IllegalArgumentException("missing " + name);
        return item.getTextContent();
    }
    private static int integer(Element element, String name) { return Integer.parseInt(field(element, name)); }
    private static long optionalLong(Element element, String name, long fallback) {
        Element item = child(element, name);
        return item == null ? fallback : Long.parseLong(item.getTextContent());
    }
    private static String required(Map<String, Object> map, String key) {
        if (!map.containsKey(key)) throw new IllegalArgumentException("missing Inputlog metadata " + key);
        return map.get(key).toString();
    }
    private static Map<String, Object> entries(Element element) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Element entry : children(element, "entry")) result.put(field(entry, "key"), field(entry, "value"));
        return result;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> exportedHeader(Map<String, Object> meta) {
        if (!meta.containsKey("__ScriptLogLiteExporter") || !meta.containsKey(InputlogExporter.HEADER)) return Map.of();
        Object parsed = new Json(meta.get(InputlogExporter.HEADER).toString()).parse();
        if (!(parsed instanceof Map)) throw new IllegalArgumentException("Invalid ScriptLogLite IDFX header extension");
        return (Map<String, Object>) parsed;
    }
    private static String label(Element event, String key) {
        for (Element item : children(event, "label")) {
            if (item.getAttribute("key").equals(key)) return item.getTextContent();
            if (item.getAttribute("key").equals("ScriptLogLite")) {
                Object fields = new Json(item.getTextContent()).parse();
                if (!(fields instanceof Map)) throw new IllegalArgumentException("Invalid ScriptLogLite event label");
                Object value = ((Map<?, ?>) fields).get(key);
                if (value != null) return value.toString();
            }
        }
        return null;
    }
    private static Map<String, Object> sourceEvent(Element event) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", event.getAttribute("id")); result.put("type", event.getAttribute("type"));
        List<Object> parts = new ArrayList<>();
        for (Element part : children(event, "part")) {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("type", part.getAttribute("type"));
            // A list retains duplicate statistics fields as present in the source.
            List<Object> fields = new ArrayList<>();
            for (Element field : children(part, null)) fields.add(Map.of("name", field.getTagName(), "value", field.getTextContent()));
            item.put("fields", fields); parts.add(item);
        }
        result.put("parts", parts); return result;
    }
}
