package se.lu.scriptloglite.inputlog;

import se.lu.scriptloglite.ReplayLog;
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

/** PT0 summary and optional reference comparison in a separate, read-only application tab. */
public final class SummaryAnalysisPanel extends JPanel {
    private SummaryAnalysis analysis;
    private SummaryAnalysisReport.Comparison comparison;
    private final ReplayLog history;
    private final JEditorPane report = new JEditorPane("text/html", "");
    private final JComboBox<String> mode = new JComboBox<>(new String[] {"Internal events / reconstructed text", "Retained Inputlog source events"});
    private final JButton compare = new JButton("Compare Inputlog HTML…"), save = new JButton("Save HTML report…");
    private final String title;
    public SummaryAnalysisPanel(SummaryAnalysis analysis, String title, DirectoryHistory directories) {
        super(new BorderLayout(8, 8)); this.analysis = analysis; this.history = analysis.log; this.title = title;
        report.setEditable(false); report.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        mode.setSelectedIndex(analysis.internal ? 0 : 1);
        controls.add(mode); controls.add(compare); controls.add(save);
        add(controls, BorderLayout.NORTH); add(new JScrollPane(report), BorderLayout.CENTER); refresh();
        mode.addActionListener(event -> {
            if (!mode.isEnabled()) return;
            int choice = mode.getSelectedIndex(); busy(true);
            new SwingWorker<SummaryAnalysis, Void>() {
                protected SummaryAnalysis doInBackground() { return choice == 0 ? SummaryAnalysis.analyze(history) : SummaryAnalysis.analyzeSource(history); }
                protected void done() {
                    try { SummaryAnalysisPanel.this.analysis = get(); comparison = null; refresh(); }
                    catch (Exception exception) {
                        mode.setSelectedIndex(SummaryAnalysisPanel.this.analysis.internal ? 0 : 1);
                        EditorSupport.showError(SummaryAnalysisPanel.this, exception);
                    } finally { busy(false); }
                }
            }.execute();
        });
        compare.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(directories.directory("open").toFile());
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Inputlog Summary Analysis HTML (PT0)", "html", "htm"));
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path path = chooser.getSelectedFile().toPath(); SummaryAnalysis captured = this.analysis; busy(true);
            new SwingWorker<SummaryAnalysisReport.Comparison, Void>() {
                protected SummaryAnalysisReport.Comparison doInBackground() throws Exception { return SummaryAnalysisReport.compare(captured, Files.readString(path)); }
                protected void done() {
                    try { comparison = get(); directories.remember("open", path); refresh(); }
                    catch (Exception exception) { EditorSupport.showError(SummaryAnalysisPanel.this, exception); }
                    finally { busy(false); }
                }
            }.execute();
        });
        save.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(directories.directory("save").toFile());
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Summary Analysis HTML", "html"));
            chooser.setSelectedFile(Path.of("summary-analysis.html").toFile());
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path selected = chooser.getSelectedFile().toPath();
            Path path = selected.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".html") ? selected : selected.resolveSibling(selected.getFileName() + ".html");
            if (Files.exists(path) && JOptionPane.showConfirmDialog(this, "Replace " + path.getFileName() + "?", "Save report", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            SummaryAnalysis captured = this.analysis; SummaryAnalysisReport.Comparison capturedComparison = comparison; busy(true);
            new SwingWorker<Void, Void>() {
                protected Void doInBackground() throws Exception { Files.writeString(path, SummaryAnalysisReport.html(captured, title, capturedComparison)); return null; }
                protected void done() {
                    try { get(); directories.remember("save", path); }
                    catch (Exception exception) { EditorSupport.showError(SummaryAnalysisPanel.this, exception); }
                    finally { busy(false); }
                }
            }.execute();
        });
    }
    private void busy(boolean value) { mode.setEnabled(!value); compare.setEnabled(!value); save.setEnabled(!value); }
    private void refresh() { report.setText(SummaryAnalysisReport.html(analysis, title, comparison)); report.setCaretPosition(0); }
}
