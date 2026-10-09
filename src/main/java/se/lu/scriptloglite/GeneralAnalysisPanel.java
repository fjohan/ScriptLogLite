package se.lu.scriptloglite;

import java.awt.BorderLayout;
import java.nio.file.Path;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

/** Analysis results stay separate from the recording/replay tabs. */
final class GeneralAnalysisPanel extends JPanel {
    GeneralAnalysis analysis;
    final ReplayLog history;
    GeneralAnalysisReport.Comparison comparison;
    final JTextArea summary = new JTextArea();
    final String title;
    GeneralAnalysisPanel(GeneralAnalysis analysis, String title, DirectoryHistory directories) {
        super(new BorderLayout(8, 8)); this.analysis = analysis; this.history = analysis.log; this.title = title;
        summary.setEditable(false); summary.setLineWrap(true); summary.setWrapStyleWord(true);
        summary.setMargin(new java.awt.Insets(12, 12, 12, 12));
        JTable table = new JTable(new AbstractTableModel() {
            public int getRowCount() { return GeneralAnalysisPanel.this.analysis.rows.size(); }
            public int getColumnCount() { return GeneralAnalysis.COLUMNS.size(); }
            public String getColumnName(int column) { return GeneralAnalysis.COLUMNS.get(column); }
            public Object getValueAt(int row, int column) { return GeneralAnalysisPanel.this.analysis.rows.get(row).cells().get(column); }
        });
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        for (int i = 0; i < table.getColumnCount(); i++) table.getColumnModel().getColumn(i).setPreferredWidth(i == 2 ? 250 : 120);
        JTabbedPane views = new JTabbedPane();
        views.addTab("Summary / comparison", new JScrollPane(summary)); views.addTab("Events", new JScrollPane(table));
        JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        JButton compare = new JButton("Compare Inputlog HTML…"), save = new JButton("Save HTML report…");
        javax.swing.JComboBox<String> mode = new javax.swing.JComboBox<>(new String[] {"Internal events / reconstructed text", "Retained Inputlog source events"});
        mode.setSelectedIndex(analysis.internal ? 0 : 1);
        controls.add(mode); controls.add(compare); controls.add(save); add(controls, BorderLayout.NORTH); add(views, BorderLayout.CENTER);
        refresh();
        mode.addActionListener(event -> {
            if (!mode.isEnabled()) return;
            int choice = mode.getSelectedIndex(); mode.setEnabled(false); compare.setEnabled(false); save.setEnabled(false);
            new SwingWorker<GeneralAnalysis, Void>() {
                protected GeneralAnalysis doInBackground() throws Exception {
                    return choice == 0 ? GeneralAnalysis.analyze(history) : GeneralAnalysis.analyzeSource(history);
                }
                protected void done() {
                    mode.setEnabled(true); compare.setEnabled(true); save.setEnabled(true);
                    try { GeneralAnalysisPanel.this.analysis = get(); comparison = null; ((AbstractTableModel) table.getModel()).fireTableDataChanged(); refresh(); }
                    catch (Exception exception) {
                        mode.setEnabled(false);
                        mode.setSelectedIndex(GeneralAnalysisPanel.this.analysis.internal ? 0 : 1);
                        mode.setEnabled(true);
                        EditorSupport.showError(GeneralAnalysisPanel.this, exception);
                    }
                }
            }.execute();
        });
        compare.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(directories.directory("open").toFile());
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Inputlog General Analysis HTML", "html", "htm"));
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path path = chooser.getSelectedFile().toPath(); GeneralAnalysis capturedAnalysis = GeneralAnalysisPanel.this.analysis; compare.setEnabled(false); mode.setEnabled(false);
            new SwingWorker<GeneralAnalysisReport.Comparison, Void>() {
                protected GeneralAnalysisReport.Comparison doInBackground() throws Exception {
                    return GeneralAnalysisReport.compare(capturedAnalysis, java.nio.file.Files.readString(path));
                }
                protected void done() {
                    compare.setEnabled(true); mode.setEnabled(true);
                    try { comparison = get(); directories.remember("open", path); refresh(); }
                    catch (Exception exception) { EditorSupport.showError(GeneralAnalysisPanel.this, exception); }
                }
            }.execute();
        });
        save.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(directories.directory("save").toFile());
            chooser.setSelectedFile(Path.of("general-analysis.html").toFile());
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path selected = chooser.getSelectedFile().toPath();
            Path path = selected.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".html") ? selected : selected.resolveSibling(selected.getFileName() + ".html");
            if (java.nio.file.Files.exists(path) && javax.swing.JOptionPane.showConfirmDialog(this,
                    "Replace " + path.getFileName() + "?", "Save report", javax.swing.JOptionPane.YES_NO_OPTION) != javax.swing.JOptionPane.YES_OPTION) return;
            GeneralAnalysisReport.Comparison captured = comparison; GeneralAnalysis capturedAnalysis = GeneralAnalysisPanel.this.analysis; save.setEnabled(false);
            new SwingWorker<Void, Void>() {
                protected Void doInBackground() throws Exception {
                    java.nio.file.Files.writeString(path, GeneralAnalysisReport.html(capturedAnalysis, title, captured)); return null;
                }
                protected void done() {
                    save.setEnabled(true);
                    try { get(); directories.remember("save", path); }
                    catch (Exception exception) { EditorSupport.showError(GeneralAnalysisPanel.this, exception); }
                }
            }.execute();
        });
    }
    void refresh() {
        StringBuilder text = new StringBuilder("General Analysis — ").append(title).append("\n\n")
                .append(analysis.rows.size()).append(" report rows; ").append(analysis.log.finalText.length()).append(" final UTF-16 units.\n")
                .append("Conversion checks: ").append(analysis.checkedKeys).append(" keyboard values and pre-edit Word document lengths.\n");
        if (!analysis.audit.isEmpty()) for (String item : analysis.audit) text.append(item).append('\n');
        else if (analysis.checkedKeys == 0) text.append("No original source records for independent conversion checks.\n");
        else text.append("Conversion checks passed.\n");
        for (String note : analysis.notes) text.append(note).append("\n");
        text.append("\nInputlog compatibility conventions include grouped modifier keys and approximate pause-location rules. Source mode additionally reproduces legacy position backfill.\n");
        if (comparison != null) {
            text.append("\nReference comparison: ").append(comparison.matched).append(" matched, ").append(comparison.missing)
                    .append(" missing, ").append(comparison.extra).append(" extra rows.\n");
            for (int i = 0; i < 20; i++) text.append(GeneralAnalysis.COLUMNS.get(i)).append(": ").append(comparison.equal[i])
                    .append(" equal / ").append(comparison.different[i]).append(" different\n");
            if (comparison.differences.isEmpty()) text.append("\nAll compared cells match.\n");
            else for (String item : comparison.differences) text.append(item).append('\n');
        }
        text.append("\nMatching this report is evidence for these fields in this session; it does not establish universal importer/exporter correctness.\n");
        summary.setText(text.toString()); summary.setCaretPosition(0);
    }
}
