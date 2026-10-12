package se.lu.scriptloglite.conversions;

import java.util.Locale;

public enum ConversionFormat {
    AUTO("Auto detect", ""), SCRIPTLOGLITE("ScriptLogLite JSON/raw", ""),
    JSON("ScriptLogLite JSON", ".json"), RAW("ScriptLogLite raw", "_raw.txt"),
    IDFX("Inputlog IDFX", ".idfx"), WEBSCRIPTLOG("WebScriptLog", "_webscriptlog.txt");
    public final String label, suffix;
    ConversionFormat(String label, String suffix) { this.label = label; this.suffix = suffix; }
    public boolean accepts(ConversionFormat actual) { return this == AUTO || this == actual || this == SCRIPTLOGLITE && (actual == JSON || actual == RAW); }
    public boolean target() { return this != AUTO && this != SCRIPTLOGLITE; }
    public static ConversionFormat parse(String value) {
        String name = value.toUpperCase(Locale.ROOT);
        if (name.equals("WEB")) return WEBSCRIPTLOG;
        if (name.equals("SLL")) return SCRIPTLOGLITE;
        return valueOf(name);
    }
    public String toString() { return label; }
}
