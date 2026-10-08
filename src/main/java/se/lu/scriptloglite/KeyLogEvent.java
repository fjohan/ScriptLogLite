package se.lu.scriptloglite;

import java.time.Instant;

final class KeyLogEvent extends LogEvent {
    final int keyCode;
    final String keyText, keyChar, modifiersText;
    final Integer modifiers, keyLocation;
    KeyLogEvent(Instant time, EventType type, int keyCode, String keyText, String keyChar,
            Integer modifiers, String modifiersText, Integer keyLocation) {
        super(time, type); this.keyCode = keyCode; this.keyText = keyText; this.keyChar = keyChar;
        this.modifiers = modifiers; this.modifiersText = modifiersText; this.keyLocation = keyLocation;
    }
    LogEvent at(Instant time) {
        return new KeyLogEvent(time, type, keyCode, keyText, keyChar, modifiers, modifiersText, keyLocation);
    }
    String description() { return type.name + " keyCode=" + keyCode; }
}
