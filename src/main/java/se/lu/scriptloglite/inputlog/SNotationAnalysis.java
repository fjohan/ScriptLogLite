package se.lu.scriptloglite.inputlog;

import se.lu.scriptloglite.ReplayLog;

/** Structured revision history shared by notation and word-pause analysis. */
public final class SNotationAnalysis {
    final RevisionHistory history;
    private SNotationAnalysis(ReplayLog log) { history = new RevisionHistory(log); }
    public static SNotationAnalysis analyze(ReplayLog log) { return new SNotationAnalysis(log); }
    public String reconstructedText() { return history.text; }
    public String notation() { return history.notation(); }
}
