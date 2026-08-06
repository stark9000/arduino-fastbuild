package fastbuilduix;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * A standalone, read-only hex/ASCII dump viewer - pick any file, see its
 * bytes. Deliberately independent of the Upload tab's hex/bin file field;
 * its own file picker, its own state. No editing, and a soft size cap so an
 * accidentally huge file selection can't hang the UI - this is a diagnostic
 * tool, not a binary editor.
 */
final class HexViewerPanel extends JPanel {

    private static final int MAX_BYTES = 2 * 1024 * 1024; // 2 MB soft cap

    private final JTextField pathField = new JTextField();
    private final JButton browseButton = new JButton("Browse\u2026");
    private final JTextArea dumpArea = new JTextArea();
    private final JLabel statusLabel = new JLabel(" ");
    private File defaultDirectory;

    /** Called by SettingsFrame whenever the current sketch changes, so the file chooser has a sensible starting point - only used while nothing's been picked yet (see onBrowseClicked). */
    void setDefaultDirectory(File dir) {
        this.defaultDirectory = dir;
    }

    HexViewerPanel() {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel topBar = new JPanel(new BorderLayout(6, 0));
        topBar.add(pathField, BorderLayout.CENTER);
        topBar.add(browseButton, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        dumpArea.setEditable(false);
        dumpArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        dumpArea.setLineWrap(false);
        add(new JScrollPane(dumpArea), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        browseButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onBrowseClicked();
            }
        });
    }

    private void onBrowseClicked() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select File to View as Hex");
        String existing = pathField.getText().trim();
        if (!existing.isEmpty()) {
            File existingFile = new File(existing);
            if (existingFile.getParentFile() != null && existingFile.getParentFile().isDirectory()) {
                chooser.setCurrentDirectory(existingFile.getParentFile());
            }
        } else if (defaultDirectory != null && defaultDirectory.isDirectory()) {
            chooser.setCurrentDirectory(defaultDirectory);
        }
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        pathField.setText(file.getAbsolutePath());
        loadFile(file);
    }

    private void loadFile(File file) {
        try {
            long length = file.length();
            int toRead = (int) Math.min(length, MAX_BYTES);
            byte[] data = new byte[toRead];
            FileInputStream in = new FileInputStream(file);
            try {
                int offset = 0;
                int n;
                while (offset < toRead && (n = in.read(data, offset, toRead - offset)) != -1) {
                    offset += n;
                }
            } finally {
                in.close();
            }
            dumpArea.setText(formatHexDump(data));
            dumpArea.setCaretPosition(0);
            if (length > MAX_BYTES) {
                statusLabel.setText("Showing first " + (MAX_BYTES / 1024) + " KB of " + (length / 1024) + " KB (truncated for display).");
            } else {
                statusLabel.setText(file.getName() + " - " + length + " bytes");
            }
        } catch (IOException ex) {
            statusLabel.setText("Could not read file: " + ex.getMessage());
            dumpArea.setText("");
        }
    }

    private static String formatHexDump(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int bytesPerLine = 16;
        for (int offset = 0; offset < data.length; offset += bytesPerLine) {
            sb.append(String.format("%08x  ", offset));
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < bytesPerLine; i++) {
                if (offset + i < data.length) {
                    int b = data[offset + i] & 0xFF;
                    sb.append(String.format("%02x ", b));
                    ascii.append(b >= 32 && b < 127 ? (char) b : '.');
                } else {
                    sb.append("   ");
                }
                if (i == 7) {
                    sb.append(' ');
                }
            }
            sb.append(" ").append(ascii).append("\n");
        }
        return sb.toString();
    }
}
