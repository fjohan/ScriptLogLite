package se.lu.scriptloglite;

/** Test-only bridge keeps codec/parser internals out of the public application API. */
public final class AnalysisTestSupport {
    private AnalysisTestSupport() { }
    public static ReplayLog jsonRoundTrip(ReplayLog log) {
        return JsonLogCodec.load(JsonLogCodec.export(log.events, log.metadata));
    }
    public static ReplayLog rawRoundTrip(ReplayLog log) {
        return RawLogCodec.load(RawLogCodec.export(log));
    }
    public static Object copyJson(Object value) { return new Json(Json.stringify(value, 0)).parse(); }
}
