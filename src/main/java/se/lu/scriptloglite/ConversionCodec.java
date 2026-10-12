package se.lu.scriptloglite;

import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import se.lu.scriptloglite.conversions.ConversionFormat;

/** Narrow bridge lets conversion workflows reuse the native codecs without exposing internals. */
public final class ConversionCodec {
    private ConversionCodec() { }
    public static List<String> write(ReplayLog log, ConversionFormat format, Writer writer, boolean replace, boolean labels) throws Exception {
        List<String> notes = new ArrayList<>();
        switch (format) {
            case JSON: JsonLogCodec.write(log.events, log.metadata, writer, replace ? EditDialect.REPLACE : EditDialect.RECORDED); break;
            case RAW: RawLogCodec.write(log.events, log.metadata, writer, replace ? EditDialect.REPLACE : EditDialect.RECORDED); break;
            case IDFX: InputlogExporter.write(log.events, log.metadata, writer, labels); break;
            case WEBSCRIPTLOG: notes.add(WebScriptLogExporter.write(log, writer).description()); break;
            default: throw new IllegalArgumentException("Choose a concrete output format");
        }
        for (String field : List.of("inputlogImportReport", "webScriptLogImportReport")) {
            Object report = log.metadata.get(field);
            if (report instanceof Map) {
                Object warnings = ((Map<?, ?>) report).get("warnings");
                if (warnings instanceof List) for (Object warning : (List<?>) warnings) notes.add(warning.toString());
            }
        }
        return notes;
    }
}
