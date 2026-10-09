package se.lu.scriptloglite.inputlog;

import se.lu.scriptloglite.DirectoryHistory;
import se.lu.scriptloglite.EditorSupport;

import java.awt.BorderLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;

/** Corrected notation and word timing reports in a read-only application tab. */
public final class RevisionAnalysisPanel extends JPanel {
    private final WordPausesAnalysis analysis;
    private RevisionAnalysisReport.Comparison comparison;
    private boolean wordPauses;
    private final JEditorPane report = new JEditorPane("text/html", "");
    private final JComboBox<String> mode = new JComboBox<>(new String[] {"Word Pauses", "S-Notation"});
    private final JButton compare = new JButton("Compare Inputlog SN/WP HTML…"), save = new JButton("Save HTML report…");
    private final String title;
    public RevisionAnalysisPanel(WordPausesAnalysis analysis, String title, boolean wordPauses, DirectoryHistory directories) {
        super(new BorderLayout(8, 8)); this.analysis = analysis; this.wordPauses = wordPauses; this.title = title;
        report.setEditable(false); report.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        mode.setSelectedIndex(wordPauses ? 0 : 1);
        controls.add(mode); controls.add(compare); controls.add(save);
        add(controls, BorderLayout.NORTH); add(new JScrollPane(report), BorderLayout.CENTER); refresh();
        mode.addActionListener(event -> {
            if (!mode.isEnabled()) return;
            this.wordPauses = mode.getSelectedIndex() == 0; refresh();
        });
        compare.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(directories.directory("open").toFile());
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Inputlog S-Notation / Word Pauses HTML", "html", "htm"));
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path path = chooser.getSelectedFile().toPath(); WordPausesAnalysis captured = this.analysis; busy(true);
            new SwingWorker<RevisionAnalysisReport.Comparison, Void>() {
                protected RevisionAnalysisReport.Comparison doInBackground() throws Exception { return RevisionAnalysisReport.compare(captured, Files.readString(path)); }
                protected void done() {
                    try { comparison = get(); directories.remember("open", path); refresh(); }
                    catch (Exception exception) { EditorSupport.showError(RevisionAnalysisPanel.this, exception); }
                    finally { busy(false); }
                }
            }.execute();
        });
        save.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(directories.directory("save").toFile());
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Revision Analysis HTML", "html"));
            chooser.setSelectedFile(Path.of(this.wordPauses ? "word-pauses.html" : "s-notation.html").toFile());
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path selected = chooser.getSelectedFile().toPath();
            Path path = selected.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".html") ? selected : selected.resolveSibling(selected.getFileName() + ".html");
            if (Files.exists(path) && JOptionPane.showConfirmDialog(this, "Replace " + path.getFileName() + "?", "Save report", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            WordPausesAnalysis captured = this.analysis; RevisionAnalysisReport.Comparison capturedComparison = comparison; boolean capturedMode = this.wordPauses; busy(true);
            new SwingWorker<Void, Void>() {
                protected Void doInBackground() throws Exception { Files.writeString(path, RevisionAnalysisReport.html(captured, title, capturedMode, capturedComparison)); return null; }
                protected void done() {
                    try { get(); directories.remember("save", path); }
                    catch (Exception exception) { EditorSupport.showError(RevisionAnalysisPanel.this, exception); }
                    finally { busy(false); }
                }
            }.execute();
        });
    }
    private void busy(boolean value) { mode.setEnabled(!value); compare.setEnabled(!value); save.setEnabled(!value); }
    private void refresh() { report.setText(RevisionAnalysisReport.html(analysis, title, wordPauses, comparison)); report.setCaretPosition(0); }
}
