package se.lu.scriptloglite;

import java.nio.file.*;
import java.io.StringWriter;
import java.util.*;
import java.time.Duration;

final class InputlogTestbedRoundTripChecks {
    static void run() throws Exception {
        Path fixture = Path.of("testbed-ilog.idfx"); if (!Files.exists(fixture)) return;
        ReplayLog source = ReplayLog.load(fixture, -1);
        List<EditEvent> edits = edits(source);
        check(edits.size() == 670, "review edit baseline");
        String expectedText = "vi skriver räksmörgås\n\ntestar lite olika svåra tecken\n\n½!\"#¤%&/()=?\n@£$€{[]}\\\n\n<>|\n\nJag har haft samma känsla förut\ndet är inte första gången\ndenna känsla att vara ett fallande löv i vinden\n\n1\n2\n3\n4\n5\n6\n7\n8\n9\n0\n1\n2\n";
        check(source.finalText.equals(expectedText), "reconstructed-text baseline");
        check(source.finalText.replace("\n", "").length() == 190, "final Word character statistic");
        check(source.finalText.replace("\n", "").replace(" ", "").length() == 167, "final Word nonspace statistic");
        check(keys(source).size() == 2493, "all observed key presses/releases");
        ScriptLogLiteChecks.checkHistory(source, AnalysisTestSupport.jsonRoundTrip(source), "Inputlog testbed JSON history");
        ScriptLogLiteChecks.checkHistory(source, AnalysisTestSupport.rawRoundTrip(source), "Inputlog testbed raw history");
        for (ReplayLog nativeLog : List.of(source, AnalysisTestSupport.jsonRoundTrip(source), AnalysisTestSupport.rawRoundTrip(source))) {
            StringWriter xml = new StringWriter(); InputlogExporter.write(nativeLog.events, nativeLog.metadata, xml);
            compare(source, InputlogImporter.load(xml.toString()), false);
        }
        StringWriter web = new StringWriter(); WebScriptLogExporter.Report report = WebScriptLogExporter.write(source, web);
        ReplayLog webLog = WebScriptLogImporter.load(web.toString()); compare(source, webLog, true);
        StringWriter xml = new StringWriter(); InputlogExporter.write(webLog.events, webLog.metadata, xml);
        ReplayLog back = InputlogImporter.load(xml.toString()); compare(source, back, true);
        StringWriter webAgain = new StringWriter(); WebScriptLogExporter.write(back, webAgain);
        check(new Json(web.toString()).parse().equals(new Json(webAgain.toString()).parse()), "all intermediate web streams exact through IDFX");
        check(report.unknownKeys == 4, "only four characterless OEM key observations unavailable in web schema");
        System.out.println("Inputlog testbed: 670 edit states, 2493 keys, native JSON/raw and labelled IDFX exact edit timing, web normalized selections and reverse replay checked; " + report.shiftedTimes + " web timestamp shifts, " + report.unknownKeys + " unidentified OEM keys");
    }
    private static void compare(ReplayLog source, ReplayLog result, boolean web) {
        List<EditEvent> a = edits(source), b = edits(result);
        check(a.size() == b.size(), "edit count");
        ReplayCursor ac = new ReplayCursor(source), bc = new ReplayCursor(result);
        List<Integer> ai = indices(source), bi = indices(result);
        int payloadDifferences = 0; long maxShift = 0;
        for (int i = 0; i < a.size(); i++) {
            EditEvent x = a.get(i), y = b.get(i);
            boolean same = x.offset == y.offset && x.removed.equals(y.removed) && Objects.equals(x.inserted, y.inserted);
            if (!same) payloadDifferences++;
            if (!web) check(same && x.time.equals(y.time), "exact native edit #" + i);
            maxShift = Math.max(maxShift, Math.abs(Duration.between(x.time, y.time).toMillis()));
            ac.seek(ai.get(i)); bc.seek(bi.get(i)); check(ac.state().text.equals(bc.state().text), "text after edit #" + i);
        }
        check(result.finalText.equals(source.finalText), "final text");
        List<KeyLogEvent> ak = keys(source), bk = keys(result); check(ak.size() == bk.size(), "keyboard count");
        int unavailable = 0;
        for (int i = 0; i < ak.size(); i++) {
            KeyLogEvent x = ak.get(i), y = bk.get(i); check(x.type == y.type, "keyboard action");
            if (!web) check(Objects.equals(x.keyCode, y.keyCode) && Objects.equals(x.keyChar, y.keyChar) && Objects.equals(x.modifiers, y.modifiers) && Objects.equals(x.keyLocation, y.keyLocation) && x.time.equals(y.time), "exact native keyboard #" + i);
            else if ("undefined".equals(y.keyChar) || (Objects.equals(y.keyCode, java.awt.event.KeyEvent.VK_UNDEFINED) && (y.keyChar == null || y.keyChar.isEmpty()))) unavailable++;
            else if (x.keyChar != null && x.keyChar.length() == 1 && !Character.isISOControl(x.keyChar.charAt(0))) check(x.keyChar.equals(y.keyChar), "web observed character #" + i);
            else check(Objects.equals(x.keyCode, y.keyCode), "web key identity #" + i);
            maxShift = Math.max(maxShift, Math.abs(Duration.between(x.time, y.time).toMillis()));
        }
        List<CaretLogEvent> original = carets(source), converted = carets(result);
        int matched = 0;
        for (CaretLogEvent c : converted) if (matched < original.size()) {
            CaretLogEvent e = original.get(matched);
            if (c.dot == e.dot && c.mark == e.mark && (web || c.time.equals(e.time))) matched++;
        }
        check(matched == original.size(), "all recorded/derived selection ranges retained");
        for (int i = result.events.size() - 1; i >= 0; i--) bc.seek(i);
        check(bc.state().text.equals(result.initialText), "reverse replay");
        if (web) check(maxShift <= 2 && unavailable == 4 && payloadDifferences == 0, "review web loss baseline");
        if (web) System.out.println("  Inputlog web route: " + payloadDifferences + " normalized edit ranges; maximum edit/key shift " + maxShift + " ms; " + unavailable + " unknown keys");
    }
    private static List<Integer> indices(ReplayLog log) { List<Integer> r = new ArrayList<>(); for (int i=0;i<log.events.size();i++) if(log.events.get(i) instanceof EditEvent) r.add(i); return r; }
    private static List<EditEvent> edits(ReplayLog log) { List<EditEvent> r=new ArrayList<>(); for(LogEvent e:log.events) if(e instanceof EditEvent) r.add((EditEvent)e); return r; }
    private static List<KeyLogEvent> keys(ReplayLog log) { List<KeyLogEvent> r=new ArrayList<>(); for(LogEvent e:log.events) if(e instanceof KeyLogEvent) r.add((KeyLogEvent)e); return r; }
    private static List<CaretLogEvent> carets(ReplayLog log) { List<CaretLogEvent> r=new ArrayList<>(); for(LogEvent e:log.events) if(e instanceof CaretLogEvent) r.add((CaretLogEvent)e); return r; }
    private static void check(boolean ok,String label) { if(!ok) throw new AssertionError("Inputlog testbed: " + label); }
}
