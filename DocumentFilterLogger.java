import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.PlainDocument;

/** Logs every call to the three DocumentFilter editing methods. */
public class DocumentFilterLogger {
    private static final Path LOG_PATH = Path.of("document-filter.log");

    public static void main(String[] args) throws Exception {
        PrintWriter log = new PrintWriter(Files.newBufferedWriter(LOG_PATH,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND), true);
        Runtime.getRuntime().addShutdownHook(new Thread(log::close));
        LoggingFilter filter = new LoggingFilter(log);
        System.out.println("Logging to " + LOG_PATH.toAbsolutePath());

        if (args.length == 1 && args[0].equals("--demo")) {
            PlainDocument document = new PlainDocument();
            document.setDocumentFilter(filter);
            document.insertString(0, "Hello", null);
            document.replace(0, 5, "World", null);
            document.remove(0, 5);
            log.close();
            return;
        }
        SwingUtilities.invokeLater(() -> showWindow(filter));
    }

    private static void showWindow(LoggingFilter filter) {
        JTextArea text = new JTextArea(12, 50);
        AbstractDocument document = (AbstractDocument) text.getDocument();
        document.setDocumentFilter(filter);

        JPanel buttons = new JPanel();
        JButton insert = new JButton("insertString");
        insert.addActionListener(event -> edit(() ->
                document.insertString(text.getCaretPosition(), "Hello", null)));
        JButton replace = new JButton("replace");
        replace.addActionListener(event -> edit(() -> document.replace(
                text.getSelectionStart(),
                text.getSelectionEnd() - text.getSelectionStart(), "World", null)));
        JButton remove = new JButton("remove");
        remove.addActionListener(event -> edit(() -> {
            int start = text.getSelectionStart();
            int length = text.getSelectionEnd() - start;
            if (length == 0 && start < document.getLength()) {
                length = 1;
            }
            document.remove(start, length);
        }));
        buttons.add(insert);
        buttons.add(replace);
        buttons.add(remove);

        JFrame frame = new JFrame("DocumentFilter logger");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(new JLabel("Type, paste, delete, or use the buttons. Log: "
                + LOG_PATH.toAbsolutePath()), java.awt.BorderLayout.NORTH);
        frame.add(new JScrollPane(text), java.awt.BorderLayout.CENTER);
        frame.add(buttons, java.awt.BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void edit(DocumentEdit action) {
        try {
            action.run();
        } catch (BadLocationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @FunctionalInterface
    private interface DocumentEdit {
        void run() throws BadLocationException;
    }

    public static class LoggingFilter extends DocumentFilter {
        private final PrintWriter log;

        public LoggingFilter(PrintWriter log) {
            this.log = log;
        }

        @Override
        public void insertString(FilterBypass bypass, int offset, String string,
                AttributeSet attributes) throws BadLocationException {
            record("insertString", offset, 0, string, attributes);
            super.insertString(bypass, offset, string, attributes);
        }

        @Override
        public void replace(FilterBypass bypass, int offset, int length, String text,
                AttributeSet attributes) throws BadLocationException {
            record("replace", offset, length, text, attributes);
            super.replace(bypass, offset, length, text, attributes);
        }

        @Override
        public void remove(FilterBypass bypass, int offset, int length)
                throws BadLocationException {
            record("remove", offset, length, null, null);
            super.remove(bypass, offset, length);
        }

        private synchronized void record(String method, int offset, int length,
                String text, AttributeSet attributes) {
            String entry = Instant.now() + " " + method + " offset=" + offset
                    + " length=" + length + " text=" + quote(text)
                    + " attributes=" + quote(attributes == null ? null : attributes.toString());
            log.println(entry);
            if (log.checkError()) {
                throw new IllegalStateException("Unable to write " + LOG_PATH);
            }
            System.out.println(entry);
        }

        private static String quote(String text) {
            if (text == null) {
                return "null";
            }
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r")
                    .replace("\t", "\\t") + "\"";
        }
    }
}
