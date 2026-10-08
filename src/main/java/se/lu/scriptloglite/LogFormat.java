package se.lu.scriptloglite;

import java.nio.file.Path;

enum LogFormat {
    JSON("JSON", ".json"), RAW("Raw", ".txt"), IDFX("Inputlog IDFX", ".idfx");
    final String label, extension;
    LogFormat(String label, String extension) { this.label = label; this.extension = extension; }
    Path path(Path selected) {
        String name = selected.getFileName().toString();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) name = name.substring(0, name.length() - 5);
        else if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) name = name.substring(0, name.length() - 4);
        else if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".idfx")) name = name.substring(0, name.length() - 5);
        return selected.resolveSibling(name + extension);
    }
}
