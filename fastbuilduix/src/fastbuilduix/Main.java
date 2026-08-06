package fastbuilduix;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

public class Main {

    public static void main(String[] args) {
        // Quietly absorbs a known benign Windows IME race that otherwise
        // prints an alarming (but harmless) IllegalComponentStateException
        // stack trace - see EdtExceptionHandler for details.
        System.setProperty("sun.awt.exception.handler", EdtExceptionHandler.class.getName());

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | UnsupportedLookAndFeelException ignored) {
            // Fall back to the default cross-platform look and feel.
        }
        SwingUtilities.invokeLater(() -> {
            SettingsFrame frame = new SettingsFrame();
            frame.setVisible(true);
        });
    }
}
