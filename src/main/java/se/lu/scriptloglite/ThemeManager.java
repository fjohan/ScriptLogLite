package se.lu.scriptloglite;

final class ThemeManager {
    static void installTheme(Theme theme) throws Exception {
        try {
            javax.swing.UIManager.setLookAndFeel(theme.className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("FlatLaf is missing. Start with ./run.sh or build with Maven; "
                    + "see README.md for the classpath instructions.", exception);
        }
    }
}
