package se.lu.scriptloglite.conversions;

import se.lu.scriptloglite.DirectoryHistory;
import java.awt.BorderLayout;
import java.awt.GridBagLayout;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.nio.file.Path;
import java.nio.file.Files;
import javax.swing.*;

/** Standalone file/directory conversion UI, using exactly the CLI engine. */
public final class ConversionPanel extends JPanel {
    private final JTextField input = new JTextField(45), output = new JTextField(45);
    private final JComboBox<ConversionFormat> from = new JComboBox<>(ConversionFormat.values());
    private final JComboBox<ConversionFormat> to = new JComboBox<>(new ConversionFormat[] {ConversionFormat.JSON, ConversionFormat.RAW, ConversionFormat.IDFX, ConversionFormat.WEBSCRIPTLOG});
    private final JCheckBox recursive = new JCheckBox("Include subdirectories"), overwrite = new JCheckBox("Overwrite existing outputs");
    private final JCheckBox replace = new JCheckBox("Replace dialect (JSON/raw)", true), labels = new JCheckBox("Include Lite labels (IDFX)", true);
    private final JButton browseInput = new JButton("Browse…"), browseOutput = new JButton("Browse…"), preview = new JButton("Preview files"), convert = new JButton("Convert");
    private final JTextArea report = new JTextArea();
    public ConversionPanel(DirectoryHistory directories) {
        super(new BorderLayout(8, 8)); setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel fields = new JPanel(new GridBagLayout()); GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4); c.fill = GridBagConstraints.HORIZONTAL;
        row(fields, c, 0, "Input file/directory", input, browseInput);
        row(fields, c, 1, "Output file/directory", output, browseOutput);
        JPanel formats = new JPanel(); formats.add(new JLabel("From:")); formats.add(from); formats.add(new JLabel("To:")); formats.add(to);
        row(fields, c, 2, "Formats", formats, null);
        JPanel options = new JPanel(); options.add(recursive); options.add(overwrite); options.add(replace); options.add(labels);
        row(fields, c, 3, "Options", options, null);
        JPanel actions = new JPanel(); actions.add(preview); actions.add(convert);
        row(fields, c, 4, "", actions, null);
        add(fields, BorderLayout.NORTH); report.setEditable(false); report.setLineWrap(true); report.setWrapStyleWord(true); add(new JScrollPane(report), BorderLayout.CENTER);
        report.setText("Convert between ScriptLogLite JSON/raw, IDFX and WebScriptLog via internal events.\nFor batch conversion, select an input directory and an output directory. Existing outputs are skipped by default. Preview lists paths without writing files.");
        browseInput.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(directories.directory("open").toFile()); chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) input.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        browseOutput.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(directories.directory("save").toFile());
            chooser.setFileSelectionMode(!input.getText().isBlank() && Files.isDirectory(Path.of(input.getText())) ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_AND_DIRECTORIES);
            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) output.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        preview.addActionListener(e -> run(true)); convert.addActionListener(e -> run(false));
    }
    private static void row(JPanel panel, GridBagConstraints c, int y, String label, java.awt.Component value, java.awt.Component button) {
        c.gridy = y; c.gridx = 0; c.weightx = 0; panel.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; panel.add(value, c);
        if (button != null) { c.gridx = 2; c.weightx = 0; panel.add(button, c); }
    }
    private void run(boolean dry) {
        if (input.getText().isBlank() || output.getText().isBlank()) { report.setText("Choose input and output paths first."); return; }
        final Conversions.Request request;
        try { request = new Conversions.Request(Path.of(input.getText()), Path.of(output.getText()), (ConversionFormat) from.getSelectedItem(), (ConversionFormat) to.getSelectedItem(), recursive.isSelected(), overwrite.isSelected(), replace.isSelected(), labels.isSelected()); }
        catch (Exception exception) { report.setText(exception.getMessage()); return; }
        busy(true); report.setText(dry ? "Planning…" : "Converting…");
        new SwingWorker<String, Void>() {
            protected String doInBackground() throws Exception {
                if (dry) { StringBuilder text = new StringBuilder(); var plan = Conversions.plan(request); for (var entry : plan) text.append(entry).append('\n'); return text.append(plan.size()).append(" candidate files; no output written.").toString(); }
                var result = Conversions.run(request); return result.summary() + "\n\n" + String.join("\n", result.lines);
            }
            protected void done() {
                try { report.setText(get()); report.setCaretPosition(0); }
                catch (Exception exception) { Throwable cause = exception.getCause() == null ? exception : exception.getCause(); report.setText("Conversion failed: " + cause.getMessage()); }
                finally { busy(false); }
            }
        }.execute();
    }
    private void busy(boolean enabled) {
        for (java.awt.Component c : new java.awt.Component[] {input, output, from, to, recursive, overwrite, replace, labels, browseInput, browseOutput, preview, convert}) c.setEnabled(!enabled);
    }
}
