package se.lu.scriptloglite;

/** JSON/raw vocabulary only; captured callbacks and IDFX exports retain their original types. */
enum EditDialect {
    RECORDED("recorded", "Recorded callbacks"),
    REPLACE("replace", "Replace insertions (length 0)");
    final String id, label;
    EditDialect(String id, String label) { this.id = id; this.label = label; }
    EventType type(LogEvent event) { return this == REPLACE && event.type == EventType.INSERT ? EventType.REPLACE : event.type; }
}
