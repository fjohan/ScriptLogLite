package se.lu.scriptloglite;

enum Theme {
    NIMBUS("Nimbus", "javax.swing.plaf.nimbus.NimbusLookAndFeel"),
    LIGHT("Light", "com.formdev.flatlaf.FlatLightLaf"),
    DARK("Dark", "com.formdev.flatlaf.FlatDarkLaf");

    final String label, className;
    Theme(String label, String className) { this.label = label; this.className = className; }
}
