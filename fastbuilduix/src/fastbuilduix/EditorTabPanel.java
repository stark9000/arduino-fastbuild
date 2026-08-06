package fastbuilduix;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Editor tab: a file tree rooted at the current sketch's own folder
 * (refreshed automatically whenever the sketch path changes elsewhere in the
 * app), and a tabbed area of open files using RSyntaxTextArea for C/C++
 * syntax highlighting, line numbers, code folding, and bracket matching.
 *
 * Deliberately lightweight, on purpose - no autocomplete, no error
 * checking/squiggles, no refactoring, no project model beyond "what files
 * are sitting in this folder". Just enough to edit a sketch's files without
 * leaving the app. Only existing files can be opened; creating new
 * files/folders isn't supported here.
 */
final class EditorTabPanel extends JPanel {

    interface StatusListener {
        void onStatus(String message);
    }

    /** One open file: its editor, the scroll pane it lives in (what actually sits in editorTabs), and dirty tracking. */
    private static final class OpenFile {
        final File file;
        final RSyntaxTextArea editor;
        final RTextScrollPane scrollPane;
        JLabel titleLabel;
        boolean dirty = false;

        OpenFile(File file, RSyntaxTextArea editor, RTextScrollPane scrollPane) {
            this.file = file;
            this.editor = editor;
            this.scrollPane = scrollPane;
        }
    }

    private final JTree fileTree = new JTree(new DefaultMutableTreeNode("(no sketch selected)"));
    private final JTabbedPane editorTabs = new JTabbedPane();
    private final JTextField findField = new JTextField();
    private final JCheckBox hideUserPathCheck = new JCheckBox("Hide user folder path");
    private final JCheckBox hideRepeatingPathsCheck = new JCheckBox("Hide repeating paths (show names only)");
    private final Map<String, OpenFile> openFiles = new LinkedHashMap<String, OpenFile>();
    private final StatusListener statusListener;
    private File sketchDir;

    EditorTabPanel(StatusListener statusListener) {
        super(new BorderLayout());
        this.statusListener = statusListener;

        hideUserPathCheck.setSelected(true);
        hideRepeatingPathsCheck.setSelected(true);

        fileTree.setRootVisible(true);
        fileTree.setShowsRootHandles(true);
        fileTree.setCellRenderer(buildTreeCellRenderer());
        fileTree.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    onTreeDoubleClicked();
                }
            }
        });
        JScrollPane treeScroll = new JScrollPane(fileTree);
        treeScroll.setPreferredSize(new Dimension(220, 400));

        JPanel treeSide = new JPanel(new BorderLayout());
        treeSide.add(buildTreeFilterBar(), BorderLayout.NORTH);
        treeSide.add(treeScroll, BorderLayout.CENTER);

        JPanel editorSide = new JPanel(new BorderLayout());
        editorSide.add(buildFindBar(), BorderLayout.NORTH);
        editorSide.add(editorTabs, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeSide, editorSide);
        split.setResizeWeight(0.0);
        split.setDividerLocation(240);
        add(split, BorderLayout.CENTER);
    }

    private JPanel buildTreeFilterBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new javax.swing.BoxLayout(bar, javax.swing.BoxLayout.Y_AXIS));
        hideRepeatingPathsCheck.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        hideUserPathCheck.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        bar.add(hideRepeatingPathsCheck);
        bar.add(hideUserPathCheck);
        ActionListener refreshTreeDisplay = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                fileTree.treeDidChange();
                fileTree.repaint();
            }
        };
        hideRepeatingPathsCheck.addActionListener(refreshTreeDisplay);
        hideUserPathCheck.addActionListener(refreshTreeDisplay);
        return bar;
    }

    private javax.swing.tree.DefaultTreeCellRenderer buildTreeCellRenderer() {
        return new javax.swing.tree.DefaultTreeCellRenderer() {
            public java.awt.Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                    boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
                if (value instanceof DefaultMutableTreeNode) {
                    Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
                    if (userObject instanceof File) {
                        setText(displayTextFor((File) userObject));
                    }
                }
                return this;
            }
        };
    }

    /** Applies the two path-shortening checkboxes to how a tree node's file is displayed. */
    private String displayTextFor(File f) {
        if (hideRepeatingPathsCheck.isSelected()) {
            return f.getName().isEmpty() ? f.getAbsolutePath() : f.getName(); // root can have an empty getName()
        }
        String path = f.getAbsolutePath();
        if (hideUserPathCheck.isSelected()) {
            String home = System.getProperty("user.home", "");
            if (!home.isEmpty() && path.regionMatches(true, 0, home, 0, home.length())) {
                path = "<home>" + path.substring(home.length());
            }
        }
        return path;
    }

    private JPanel buildFindBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(new JLabel("Find:"));
        findField.setColumns(24);
        bar.add(findField);

        JButton findNextButton = new JButton("Find Next");
        findNextButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                findNext(false);
            }
        });
        JButton findPrevButton = new JButton("Find Previous");
        findPrevButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                findNext(true);
            }
        });
        bar.add(findNextButton);
        bar.add(findPrevButton);
        findField.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                findNext(false);
            }
        });
        return bar;
    }

    private void findNext(boolean backwards) {
        RSyntaxTextArea current = currentEditor();
        String needle = findField.getText();
        if (current == null || needle.isEmpty()) {
            return;
        }
        SearchContext context = new SearchContext();
        context.setSearchFor(needle);
        context.setMatchCase(false);
        context.setSearchForward(!backwards);
        context.setWholeWord(false);
        boolean found = SearchEngine.find(current, context).wasFound();
        if (statusListener != null && !found) {
            statusListener.onStatus("\"" + needle + "\" not found.");
        }
    }

    private RSyntaxTextArea currentEditor() {
        for (OpenFile of : openFiles.values()) {
            if (of.scrollPane == editorTabs.getSelectedComponent()) {
                return of.editor;
            }
        }
        return null;
    }

    private void onTreeDoubleClicked() {
        TreePath path = fileTree.getSelectionPath();
        if (path == null) {
            return;
        }
        Object last = path.getLastPathComponent();
        if (!(last instanceof DefaultMutableTreeNode)) {
            return;
        }
        Object userObject = ((DefaultMutableTreeNode) last).getUserObject();
        if (userObject instanceof File) {
            File f = (File) userObject;
            if (f.isFile()) {
                openFile(f);
            }
        }
    }

    private static boolean isBinaryFile(File f) {
        String lower = f.getName().toLowerCase();
        return lower.endsWith(".hex") || lower.endsWith(".bin");
    }

    /** Rebuilds the file tree rooted at the given sketch's own folder. Safe to call repeatedly as the sketch path changes elsewhere. */
    void refreshForSketch(String sketchPath) {
        if (sketchPath == null || sketchPath.trim().isEmpty()) {
            sketchDir = null;
            fileTree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("(no sketch selected)")));
            return;
        }
        File dir = new File(sketchPath.trim()).getParentFile();
        if (dir == null || !dir.isDirectory()) {
            sketchDir = null;
            fileTree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("(sketch folder not found)")));
            return;
        }
        if (dir.equals(sketchDir)) {
            return; // already showing this folder - avoid rebuilding every time this is called
        }
        sketchDir = dir;
        DefaultMutableTreeNode root = buildTreeNode(dir);
        fileTree.setModel(new DefaultTreeModel(root));
        for (int i = 0; i < fileTree.getRowCount(); i++) {
            fileTree.expandRow(i);
        }
    }

    private DefaultMutableTreeNode buildTreeNode(File dir) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(dir);
        File[] children = dir.listFiles();
        if (children != null) {
            java.util.List<File> sorted = new ArrayList<File>(Arrays.asList(children));
            java.util.Collections.sort(sorted, new Comparator<File>() {
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File child : sorted) {
                if (child.isDirectory()) {
                    node.add(buildTreeNode(child));
                } else {
                    node.add(new DefaultMutableTreeNode(child));
                }
            }
        }
        return node;
    }

    /** One compiler diagnostic location, parsed from build output elsewhere and handed in here to highlight. */
    static final class ErrorLocation {
        final File file;
        final int lineNumber; // 1-based, matching how compilers report it
        final boolean isError; // false means it's a warning
        final String message;

        ErrorLocation(File file, int lineNumber, boolean isError, String message) {
            this.file = file;
            this.lineNumber = lineNumber;
            this.isError = isError;
            this.message = message;
        }
    }

    private static final java.awt.Color ERROR_HIGHLIGHT_COLOR = new java.awt.Color(255, 200, 200);
    private static final java.awt.Color WARNING_HIGHLIGHT_COLOR = new java.awt.Color(255, 210, 120);

    private final java.util.List<OpenFile> filesWithErrorHighlights = new ArrayList<OpenFile>();

    /** Clears every error/warning line highlight currently showing, across all open files. Call this at the start of a new build. */
    void clearErrorHighlights() {
        for (OpenFile of : filesWithErrorHighlights) {
            of.editor.removeAllLineHighlights();
        }
        filesWithErrorHighlights.clear();
    }

    /**
     * Highlights each error/warning's line (opening the file first if it isn't already
     * open), and jumps to the first error. Locations for files that don't exist on disk,
     * or line numbers out of range for what's actually in the file, are silently skipped -
     * defensive, since these come from parsing free-form compiler text.
     */
    boolean highlightErrorLines(java.util.List<ErrorLocation> errors) {
        clearErrorHighlights();
        OpenFile firstErrorFile = null;
        int firstErrorLineZeroBased = -1;
        boolean highlightedAnything = false;
        for (ErrorLocation loc : errors) {
            if (loc.file == null || !loc.file.isFile()) {
                continue;
            }
            openFile(loc.file); // opens it if needed, or just leaves it as-is if already open
            OpenFile of = openFiles.get(loc.file.getAbsolutePath());
            if (of == null) {
                continue;
            }
            int zeroBasedLine = loc.lineNumber - 1;
            if (zeroBasedLine < 0 || zeroBasedLine >= of.editor.getLineCount()) {
                continue;
            }
            try {
                of.editor.addLineHighlight(zeroBasedLine, loc.isError ? ERROR_HIGHLIGHT_COLOR : WARNING_HIGHLIGHT_COLOR);
            } catch (javax.swing.text.BadLocationException ex) {
                continue; // bounds check above should prevent this, but stay defensive
            }
            highlightedAnything = true;
            if (!filesWithErrorHighlights.contains(of)) {
                filesWithErrorHighlights.add(of);
            }
            if (firstErrorFile == null && loc.isError) {
                firstErrorFile = of;
                firstErrorLineZeroBased = zeroBasedLine;
            }
        }
        if (firstErrorFile != null) {
            editorTabs.setSelectedComponent(firstErrorFile.scrollPane);
            try {
                firstErrorFile.editor.setCaretPosition(firstErrorFile.editor.getLineStartOffset(firstErrorLineZeroBased));
            } catch (javax.swing.text.BadLocationException ex) {
                // ignore - the highlight itself already succeeded, jumping to it is a bonus
            }
        }
        return highlightedAnything;
    }

    void openFile(File file) {
        if (isBinaryFile(file)) {
            report("Binary files (.hex/.bin) aren't opened in the editor - see the Hex Viewer tab instead.");
            return;
        }
        String key = file.getAbsolutePath();
        OpenFile existing = openFiles.get(key);
        if (existing != null) {
            editorTabs.setSelectedComponent(existing.scrollPane);
            return;
        }

        String content;
        try {
            content = readFile(file);
        } catch (IOException ex) {
            report("Could not open " + file.getName() + ": " + ex.getMessage());
            return;
        }

        final RSyntaxTextArea editor = new RSyntaxTextArea();
        editor.setSyntaxEditingStyle(syntaxStyleFor(file.getName()));
        editor.setCodeFoldingEnabled(true);
        editor.setAntiAliasingEnabled(true);
        editor.setTabSize(2);
        editor.setHighlightCurrentLine(false); // was overlapping/hiding error-warning line highlights when the cursor sat on the same line - error visibility matters more than this cosmetic default
        editor.setText(content);
        editor.discardAllEdits();
        editor.setCaretPosition(0);

        RTextScrollPane scrollPane = new RTextScrollPane(editor);
        scrollPane.setLineNumbersEnabled(true);

        final OpenFile openFile = new OpenFile(file, editor, scrollPane);
        openFiles.put(key, openFile);
        editorTabs.addTab(file.getName(), scrollPane);
        editorTabs.setTabComponentAt(editorTabs.indexOfComponent(scrollPane), buildTabComponent(openFile));
        editorTabs.setSelectedComponent(scrollPane);

        editor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                markDirty(openFile);
            }

            public void removeUpdate(DocumentEvent e) {
                markDirty(openFile);
            }

            public void changedUpdate(DocumentEvent e) {
                markDirty(openFile);
            }
        });

        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "save-file");
        editor.getActionMap().put("save-file", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                saveFile(openFile);
            }
        });
    }

    /** Builds the tab's own header: filename label + a small close button, with a right-click Close/Close Others/Close All menu. */
    private JPanel buildTabComponent(final OpenFile openFile) {
        JPanel tabComponent = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tabComponent.setOpaque(false);

        JLabel titleLabel = new JLabel(openFile.file.getName());
        openFile.titleLabel = titleLabel;
        tabComponent.add(titleLabel);

        JButton closeButton = new JButton("\u00d7");
        closeButton.setMargin(new Insets(0, 4, 0, 4));
        closeButton.setBorderPainted(false);
        closeButton.setContentAreaFilled(false);
        closeButton.setFocusable(false);
        closeButton.setFont(closeButton.getFont().deriveFont(Font.BOLD));
        closeButton.setToolTipText("Close");
        closeButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                closeTab(openFile);
            }
        });
        tabComponent.add(closeButton);

        JPopupMenu menu = new JPopupMenu();
        JMenuItem closeItem = new JMenuItem("Close");
        closeItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                closeTab(openFile);
            }
        });
        JMenuItem closeOthersItem = new JMenuItem("Close Other Tabs");
        closeOthersItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                closeOtherTabs(openFile);
            }
        });
        JMenuItem closeAllItem = new JMenuItem("Close All Tabs");
        closeAllItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                closeAllTabs();
            }
        });
        menu.add(closeItem);
        menu.add(closeOthersItem);
        menu.add(closeAllItem);
        tabComponent.setComponentPopupMenu(menu);
        titleLabel.setComponentPopupMenu(menu);

        // Select this tab on any click (left or right) before the popup (if any) shows,
        // since a custom tab component can otherwise intercept clicks before the
        // JTabbedPane's own tab-selection logic sees them.
        tabComponent.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                editorTabs.setSelectedComponent(openFile.scrollPane);
            }
        });

        return tabComponent;
    }

    /** Closes one tab, prompting to save first if it has unsaved changes. */
    private void closeTab(OpenFile openFile) {
        if (openFile.dirty) {
            int choice = JOptionPane.showConfirmDialog(this,
                    openFile.file.getName() + " has unsaved changes. Save before closing?",
                    "Unsaved Changes", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.CANCEL_OPTION) {
                return;
            }
            if (choice == JOptionPane.YES_OPTION) {
                saveFile(openFile);
            }
        }
        editorTabs.remove(openFile.scrollPane);
        openFiles.remove(openFile.file.getAbsolutePath());
    }

    private void closeOtherTabs(OpenFile keep) {
        java.util.List<OpenFile> toClose = new ArrayList<OpenFile>();
        for (OpenFile of : openFiles.values()) {
            if (of != keep) {
                toClose.add(of);
            }
        }
        for (OpenFile of : toClose) {
            closeTab(of);
        }
    }

    private void closeAllTabs() {
        java.util.List<OpenFile> all = new ArrayList<OpenFile>(openFiles.values());
        for (OpenFile of : all) {
            closeTab(of);
        }
    }

    private void markDirty(OpenFile openFile) {
        if (openFile.dirty) {
            return;
        }
        openFile.dirty = true;
        if (openFile.titleLabel != null) {
            openFile.titleLabel.setText(openFile.file.getName() + "*");
        }
    }

    private static String syntaxStyleFor(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".ino") || lower.endsWith(".cpp") || lower.endsWith(".cc")
                || lower.endsWith(".h") || lower.endsWith(".hpp") || lower.endsWith(".c")) {
            return SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS;
        }
        return SyntaxConstants.SYNTAX_STYLE_NONE;
    }

    private void saveFile(OpenFile openFile) {
        try {
            writeFile(openFile.file, openFile.editor.getText());
            openFile.dirty = false;
            if (openFile.titleLabel != null) {
                openFile.titleLabel.setText(openFile.file.getName());
            }
            report("Saved " + openFile.file.getName());
        } catch (IOException ex) {
            report("Could not save " + openFile.file.getName() + ": " + ex.getMessage());
        }
    }

    /** Saves every open file that has unsaved changes. Called automatically before a Ctrl+B build. */
    void saveAllOpenFiles() {
        for (OpenFile openFile : openFiles.values()) {
            if (openFile.dirty) {
                saveFile(openFile);
            }
        }
    }

    private void report(String message) {
        if (statusListener != null) {
            statusListener.onStatus(message);
        }
    }

    private static String readFile(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
        try {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    private static void writeFile(File file, String content) throws IOException {
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }
}
