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

/** Parameterized pause statistics in a read-only analysis tab. */
public final class PauseAnalysisPanel extends JPanel {
    private PauseAnalysis analysis;
    private PauseAnalysisReport.Comparison comparison;
    private final ReplayLog history;
    private final JEditorPane report = new JEditorPane("text/html", "");
    private final JComboBox<String> mode = new JComboBox<>(new String[] {"Internal events", "Retained Inputlog source events"});
    private final JButton compare = new JButton("Compare Inputlog HTML…"), save = new JButton("Save HTML report…");
    private final String title;
    private final javax.swing.JSpinner pt = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(200, 0, Integer.MAX_VALUE, 50));
    private final javax.swing.JSpinner fn = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(5, 1, 10000, 1));
    private final javax.swing.JSpinner pb = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(2000, 1, Integer.MAX_VALUE, 100));
    private final JButton update = new JButton("Update analysis");
    public PauseAnalysisPanel(PauseAnalysis analysis, String title, DirectoryHistory directories) {
        super(new BorderLayout(8, 8)); this.analysis = analysis; this.history = analysis.log; this.title = title;
        report.setEditable(false); report.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        JPanel controls = new JPanel(new BorderLayout());
        JPanel parameters = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        mode.setSelectedIndex(analysis.internal ? 0 : 1);
        pt.setValue((int) analysis.pauseThreshold); fn.setValue(analysis.intervalCount); pb.setValue((int) analysis.burstThreshold);
        parameters.add(mode); parameters.add(new javax.swing.JLabel("PT (ms):")); parameters.add(pt);
        parameters.add(new javax.swing.JLabel("FN (intervals):")); parameters.add(fn); parameters.add(new javax.swing.JLabel("P-burst (ms):")); parameters.add(pb); parameters.add(update);
        actions.add(compare); actions.add(save); controls.add(parameters, BorderLayout.NORTH); controls.add(actions, BorderLayout.CENTER);
        add(controls, BorderLayout.NORTH); add(new JScrollPane(report), BorderLayout.CENTER); refresh();
        update.addActionListener(event -> {
            if (!mode.isEnabled()) return;
            int choice = mode.getSelectedIndex(); long threshold = ((Number) pt.getValue()).longValue(); int length = ((Number) fn.getValue()).intValue(); long burst = ((Number) pb.getValue()).longValue(); busy(true);
            new SwingWorker<PauseAnalysis, Void>() {
                protected PauseAnalysis doInBackground() { return choice == 0 ? PauseAnalysis.analyze(history, threshold, length, burst) : PauseAnalysis.analyzeSource(history, threshold, length, burst); }
                protected void done() {
                    try { PauseAnalysisPanel.this.analysis = get(); comparison = null; refresh(); }
                    catch (Exception exception) {
                        mode.setSelectedIndex(PauseAnalysisPanel.this.analysis.internal ? 0 : 1);
                        EditorSupport.showError(PauseAnalysisPanel.this, exception);
                    } finally { busy(false); }
                }
            }.execute();
        });
        mode.addActionListener(event -> { if (mode.isEnabled()) update.doClick(); });
        compare.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(directories.directory("open").toFile());
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Inputlog Pause Analysis HTML", "html", "htm"));
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path path = chooser.getSelectedFile().toPath(); PauseAnalysis captured = this.analysis; busy(true);
            new SwingWorker<PauseAnalysisReport.Comparison, Void>() {
                protected PauseAnalysisReport.Comparison doInBackground() throws Exception { return PauseAnalysisReport.compare(captured, Files.readString(path)); }
                protected void done() {
                    try { comparison = get(); directories.remember("open", path); refresh(); }
                    catch (Exception exception) { EditorSupport.showError(PauseAnalysisPanel.this, exception); }
                    finally { busy(false); }
                }
            }.execute();
        });
        save.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(directories.directory("save").toFile());
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Pause Analysis HTML", "html"));
            chooser.setSelectedFile(Path.of("pause-PT" + this.analysis.pauseThreshold + "-FN" + this.analysis.intervalCount + ".html").toFile());
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path selected = chooser.getSelectedFile().toPath();
            Path path = selected.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".html") ? selected : selected.resolveSibling(selected.getFileName() + ".html");
            if (Files.exists(path) && JOptionPane.showConfirmDialog(this, "Replace " + path.getFileName() + "?", "Save report", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            PauseAnalysis captured = this.analysis; PauseAnalysisReport.Comparison capturedComparison = comparison; busy(true);
            new SwingWorker<Void, Void>() {
                protected Void doInBackground() throws Exception { Files.writeString(path, PauseAnalysisReport.html(captured, title, capturedComparison)); return null; }
                protected void done() {
                    try { get(); directories.remember("save", path); }
                    catch (Exception exception) { EditorSupport.showError(PauseAnalysisPanel.this, exception); }
                    finally { busy(false); }
                }
            }.execute();
        });
    }
    private void busy(boolean value) { mode.setEnabled(!value); pt.setEnabled(!value); fn.setEnabled(!value); pb.setEnabled(!value); update.setEnabled(!value); compare.setEnabled(!value); save.setEnabled(!value); }
    private void refresh() { report.setText(PauseAnalysisReport.html(analysis, title, comparison)); report.setCaretPosition(0); }
}
