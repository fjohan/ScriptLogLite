package se.lu.scriptloglite;

import java.nio.file.Path;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.text.AbstractDocument;
import static se.lu.scriptloglite.EditorSupport.createScrollPane;
import static se.lu.scriptloglite.EditorSupport.createTextArea;
import static se.lu.scriptloglite.EditorSupport.edit;

/** The recording editor for one independent document session. */
class DocumentTab extends JPanel {
    final TabbedApplication owner;
    final LoggingFilter filter;
    final JTextArea text;
    final String untitled;
    Path savedPath;
    int savedEntries;
    boolean saving;
    String title;

    DocumentTab(TabbedApplication owner, LoggingFilter filter, String untitled) {
        super(new java.awt.BorderLayout());
        title = untitled;
        this.owner = owner;
        this.filter = filter;
        this.untitled = untitled;
        filter.session.onRecord = this::updateTitle;
        text = createTextArea(filter);
        add(createScrollPane(text, filter), java.awt.BorderLayout.CENTER);

    }

    void updateTitle() {
        String name = savedPath == null ? untitled : savedPath.getFileName().toString();
        title = name + (filter.session.eventCount() != savedEntries ? " *" : "");
        owner.renameTab(this, title);
    }
    AbstractDocument document() { return (AbstractDocument) text.getDocument(); }
    void insert() { edit(() -> document().insertString(text.getCaretPosition(), "Hello", null)); }
    void replace() { edit(() -> document().replace(text.getSelectionStart(),
            text.getSelectionEnd() - text.getSelectionStart(), "World", null)); }
    void remove() {
        edit(() -> {
            int start = text.getSelectionStart(), length = text.getSelectionEnd() - start;
            if (length == 0 && start < document().getLength()) length = 1;
            document().remove(start, length);
        });
    }
}
