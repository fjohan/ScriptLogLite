package se.lu.scriptloglite.conversions;

import java.nio.file.Path;
import java.util.Arrays;

public final class ConversionCli {
    private ConversionCli() { }
    public static void run(String[] args) throws Exception {
        if (args.length < 4) throw new IllegalArgumentException(usage());
        Path input = Path.of(args[1]), output = Path.of(args[2]);
        ConversionFormat from = ConversionFormat.AUTO, to = null;
        boolean recursive = false, overwrite = false, replace = true, labels = true, preview = false;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--to": if (++i >= args.length) throw new IllegalArgumentException(usage()); to = ConversionFormat.parse(args[i]); break;
                case "--from": if (++i >= args.length) throw new IllegalArgumentException(usage()); from = ConversionFormat.parse(args[i]); break;
                case "--dialect":
                    if (++i >= args.length || !Arrays.asList("replace", "recorded").contains(args[i])) throw new IllegalArgumentException("Dialect must be replace or recorded");
                    replace = args[i].equals("replace"); break;
                case "--recursive": recursive = true; break;
                case "--overwrite": overwrite = true; break;
                case "--no-lite-labels": labels = false; break;
                case "--dry-run": preview = true; break;
                default: throw new IllegalArgumentException("Unknown conversion option: " + args[i]);
            }
        }
        if (to == null) throw new IllegalArgumentException(usage());
        Conversions.Request request = new Conversions.Request(input, output, from, to, recursive, overwrite, replace, labels);
        if (preview) { var plan = Conversions.plan(request); for (Conversions.Entry entry : plan) System.out.println(entry); System.out.println(plan.size() + " candidate files (dry run; no output written)"); return; }
        Conversions.Result result = Conversions.run(request);
        for (String line : result.lines) System.out.println(line);
        System.out.println(result.summary());
        if (result.failed > 0) throw new java.io.IOException("Conversion finished with " + result.failed + " failed file(s); see report above");
    }
    public static String usage() {
        return "Usage: --convert INPUT_FILE_OR_DIR OUTPUT_FILE_OR_DIR --to json|raw|idfx|webscriptlog "
                + "[--from auto|scriptloglite|json|raw|idfx|webscriptlog] [--recursive] [--overwrite] "
                + "[--dialect replace|recorded] [--no-lite-labels] [--dry-run]";
    }
}
