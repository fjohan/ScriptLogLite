package se.lu.scriptloglite;

enum EventType {
    SESSION("session", 0), INSERT("insertString", 101), REMOVE("remove", 102),
    REPLACE("replace", 103), CARET("caretUpdate", 104), SCROLL("scrollChange", 107),
    KEY_PRESSED("keyPressed", 207), KEY_RELEASED("keyReleased", 208);
    final String name;
    final int id;
    EventType(String name, int id) { this.name = name; this.id = id; }
    static EventType named(String name) {
        for (EventType type : values()) if (type.name.equals(name)) return type;
        throw new IllegalArgumentException("Unknown event " + name);
    }
}
