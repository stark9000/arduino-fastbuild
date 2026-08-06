package fastbuilduix;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Dependency Viewer tab: shows the sketch's actual #include tree - which
 * headers each file pulls in, transitively - not just a flat count.
 * Faithfully replicates fastbuild's own dependency-resolution algorithm from
 * deps.go (same include regex, same quoted-vs-angle-bracket resolution
 * order, same platform/library root directories) rather than approximating
 * it, so what's shown here should match what fastbuild's own dependency-
 * aware hashing actually walks. This is a read-only diagnostic view built
 * fresh each time Refresh is clicked - it doesn't read or write fastbuild's
 * own header-index cache file, just the same source directories.
 */
final class DependencyViewerPanel extends JPanel {

    /** Supplies the current sketch/FQBN/config-file values live at refresh time - SettingsFrame implements this reading its own fields. */
    interface ContextProvider {
        String getSketchPath();

        String getFqbn();

        String getConfigFilePath();
    }

    // Same regex fastbuild.go's includePattern uses: matches #include "foo.h" and #include <foo.h>,
    // capturing the delimiter (group 1) and the included name (group 2).
    private static final Pattern INCLUDE_PATTERN = Pattern.compile("(?m)^\\s*#\\s*include\\s*([<\"])([^>\"]+)[>\"]");
    private static final Pattern SKETCH_FILE_PATTERN = Pattern.compile("(?i)\\.(ino|cpp|h|c|hpp)$");
    private static final Pattern DATA_DIR_PATTERN = Pattern.compile("^\\s*data:\\s*\"?([^\"]+?)\"?\\s*$");
    private static final Pattern USER_DIR_PATTERN = Pattern.compile("^\\s*user:\\s*\"?([^\"]+?)\"?\\s*$");
    private static final int MAX_NODES = 4000; // defensive cap - real Arduino dependency trees are nowhere near this

    private final ContextProvider contextProvider;
    private final JTree tree = new JTree(new DefaultMutableTreeNode("(not scanned yet)"));
    private final JButton refreshButton = new JButton("Refresh");
    private final JButton expandAllButton = new JButton("Expand All");
    private final JButton collapseAllButton = new JButton("Collapse All");
    private final JLabel summaryLabel = new JLabel(" ");

    DependencyViewerPanel(ContextProvider contextProvider) {
        super(new BorderLayout());
        this.contextProvider = contextProvider;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel topBar = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(refreshButton);
        buttons.add(expandAllButton);
        buttons.add(collapseAllButton);
        topBar.add(buttons, BorderLayout.WEST);
        topBar.add(summaryLabel, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(buildRenderer());
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree);
        add(new JScrollPane(tree), BorderLayout.CENTER);

        refreshButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                refresh();
            }
        });
        expandAllButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                setAllExpanded(true);
            }
        });
        collapseAllButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                setAllExpanded(false);
            }
        });
    }

    private void setAllExpanded(boolean expand) {
        if (expand) {
            for (int i = 0; i < tree.getRowCount(); i++) {
                tree.expandRow(i);
            }
            // Newly-revealed rows push the count further out each pass - a few passes settles it.
            int previousCount;
            int guard = 0;
            do {
                previousCount = tree.getRowCount();
                for (int i = 0; i < tree.getRowCount(); i++) {
                    tree.expandRow(i);
                }
                guard++;
            } while (tree.getRowCount() != previousCount && guard < 25);
        } else {
            for (int i = tree.getRowCount() - 1; i >= 1; i--) {
                tree.collapseRow(i);
            }
        }
    }

    private DefaultTreeCellRenderer buildRenderer() {
        return new DefaultTreeCellRenderer() {
            public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected, boolean expanded,
                    boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, hasFocus);
                setToolTipText(null);
                if (value instanceof DefaultMutableTreeNode) {
                    Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
                    if (userObject instanceof DepNode) {
                        DepNode depNode = (DepNode) userObject;
                        if (!depNode.resolved) {
                            setForeground(selected ? getForeground() : Color.RED.darker());
                        }
                        if (depNode.fullPath != null) {
                            setToolTipText(depNode.fullPath);
                        }
                    }
                }
                return this;
            }
        };
    }

    /** One node in the tree: a display label, whether it was actually resolved to a real file, and its full path (for a hover tooltip, since several files can legitimately share a basename). */
    private static final class DepNode {
        final String label;
        final boolean resolved;
        final String fullPath;

        DepNode(String label, boolean resolved) {
            this(label, resolved, null);
        }

        DepNode(String label, boolean resolved, String fullPath) {
            this.label = label;
            this.resolved = resolved;
            this.fullPath = fullPath;
        }

        public String toString() {
            return label;
        }
    }

    /** Called from outside (SettingsFrame) whenever the sketch path actually changes, so this doesn't keep showing a different sketch's stale tree. */
    void clearForSketchChange() {
        tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("(sketch changed - click Refresh to reload)")));
        summaryLabel.setText(" ");
    }

    private void refresh() {
        final String sketchPath = contextProvider.getSketchPath();
        final String fqbn = contextProvider.getFqbn();
        final String configFilePath = contextProvider.getConfigFilePath();

        if (sketchPath.trim().isEmpty()) {
            summaryLabel.setText("Set a sketch in the Project tab first.");
            return;
        }
        if (fqbn.trim().isEmpty()) {
            summaryLabel.setText("Set an FQBN in the Project tab first.");
            return;
        }
        if (configFilePath.trim().isEmpty()) {
            summaryLabel.setText("Set arduino-cli.yaml in App Settings first - dependency resolution needs it.");
            return;
        }

        refreshButton.setEnabled(false);
        summaryLabel.setText("Scanning\u2026");
        tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("Scanning\u2026")));

        final Set<String> globalTouched = new LinkedHashSet<String>();
        SwingWorker<DefaultMutableTreeNode, Void> worker = new SwingWorker<DefaultMutableTreeNode, Void>() {
            private String error;

            protected DefaultMutableTreeNode doInBackground() {
                try {
                    return buildTree(sketchPath.trim(), fqbn.trim(), configFilePath.trim(), globalTouched);
                } catch (Exception ex) {
                    error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                    return null;
                }
            }

            protected void done() {
                refreshButton.setEnabled(true);
                DefaultMutableTreeNode root;
                try {
                    root = get();
                } catch (Exception ex) {
                    root = null;
                    error = rootErrorMessage(ex);
                }
                if (root == null) {
                    summaryLabel.setText("Error: " + error);
                    tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("(scan failed)")));
                    return;
                }
                tree.setModel(new DefaultTreeModel(root));
                tree.expandRow(0);
                int fileCount = globalTouched.size();
                summaryLabel.setText(fileCount + " file(s) touched" + (fileCount >= MAX_NODES ? " (truncated)" : ""));
            }

            private String rootErrorMessage(Throwable t) {
                Throwable cause = t.getCause();
                String message = (cause != null ? cause.getMessage() : t.getMessage());
                return message == null ? t.toString() : message;
            }
        };
        worker.execute();
    }

    // ------------------------------------------------------------------
    // Tree building - mirrors deps.go's hashDependencies()/resolveInclude(),
    // just building a displayable tree instead of feeding a hash function.
    // Runs entirely on the SwingWorker's background thread.
    // ------------------------------------------------------------------

    private DefaultMutableTreeNode buildTree(String sketchPath, String fqbn, String configFilePath, Set<String> globalTouched) throws IOException {
        File sketchFile = new File(sketchPath);
        File sketchDir = sketchFile.getParentFile();
        if (sketchDir == null || !sketchDir.isDirectory()) {
            throw new IOException("Sketch folder not found: " + sketchPath);
        }

        String[] dirs = readArduinoDirs(configFilePath);
        String dataDir = dirs[0];
        String userDir = dirs[1];
        if (dataDir == null || dataDir.isEmpty()) {
            throw new IOException("Could not find 'data:' in arduino-cli.yaml");
        }

        String platformDir = resolvePlatformDir(dataDir, fqbn);
        if (platformDir == null) {
            throw new IOException("No installed platform found for " + fqbn + " under " + dataDir);
        }
        String userLibrariesDir = (userDir == null || userDir.isEmpty()) ? null : new File(userDir, "libraries").getAbsolutePath();

        Map<String, List<File>> headerIndex = new LinkedHashMap<String, List<File>>();
        buildHeaderIndex(new File(platformDir), headerIndex);
        if (userLibrariesDir != null) {
            buildHeaderIndex(new File(userLibrariesDir), headerIndex);
        }

        List<File> sketchFiles = new ArrayList<File>();
        File[] siblings = sketchDir.listFiles();
        if (siblings != null) {
            List<File> sorted = new ArrayList<File>(Arrays.asList(siblings));
            Collections.sort(sorted, new Comparator<File>() {
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File f : sorted) {
                if (f.isFile() && SKETCH_FILE_PATTERN.matcher(f.getName()).find()) {
                    sketchFiles.add(f);
                }
            }
        }

        DefaultMutableTreeNode root = new DefaultMutableTreeNode(sketchDir.getName());
        int[] nodeCount = new int[]{0};
        Set<String> globallyExpanded = new LinkedHashSet<String>();
        for (File f : sketchFiles) {
            root.add(buildNode(f, headerIndex, new LinkedHashSet<String>(), nodeCount, globalTouched, globallyExpanded));
        }
        return root;
    }

    /** Reads directories.data and directories.user from arduino-cli.yaml - same minimal line-scan fastbuild.go's readArduinoDirs does. */
    private static String[] readArduinoDirs(String configFilePath) throws IOException {
        File f = new File(configFilePath);
        String data = null;
        String user = null;
        BufferedReader reader = new BufferedReader(new FileReader(f));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher dm = DATA_DIR_PATTERN.matcher(line);
                if (dm.matches()) {
                    data = dm.group(1).trim();
                }
                Matcher um = USER_DIR_PATTERN.matcher(line);
                if (um.matches()) {
                    user = um.group(1).trim();
                }
            }
        } finally {
            reader.close();
        }
        return new String[]{data, user};
    }

    /** Same logic as resolvePlatformDir in deps.go: <dataDir>/packages/<pkg>/hardware/<arch>/*, picking the last (highest-sorting) match. */
    private static String resolvePlatformDir(String dataDir, String fqbn) {
        String[] parts = fqbn.split(":", 3);
        if (parts.length < 2) {
            return null;
        }
        File archDir = new File(new File(new File(new File(dataDir, "packages"), parts[0]), "hardware"), parts[1]);
        File[] versions = archDir.listFiles();
        if (versions == null || versions.length == 0) {
            return null;
        }
        List<File> sorted = new ArrayList<File>(Arrays.asList(versions));
        Collections.sort(sorted, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        return sorted.get(sorted.size() - 1).getAbsolutePath();
    }

    /** Same as buildHeaderIndex in deps.go: walks root, mapping each file's basename to every full path found with that name. */
    private static void buildHeaderIndex(File root, Map<String, List<File>> index) {
        if (root == null || !root.exists()) {
            return;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                buildHeaderIndex(child, index);
            } else {
                List<File> list = index.get(child.getName());
                if (list == null) {
                    list = new ArrayList<File>();
                    index.put(child.getName(), list);
                }
                list.add(child);
            }
        }
    }

    /**
     * Same resolution order as resolveInclude in deps.go: a quoted include
     * checks the includer's own directory first; only falls back to the
     * global header index if no sibling file exists. Angle-bracket includes
     * always use the header index.
     */
    private static List<File> resolveInclude(File fromFile, String includedName, boolean quoted, Map<String, List<File>> headerIndex) {
        String base = new File(includedName).getName();
        if (quoted) {
            File local = new File(fromFile.getParentFile(), includedName);
            if (local.isFile()) {
                List<File> result = new ArrayList<File>();
                result.add(local);
                return result;
            }
        }
        List<File> candidates = headerIndex.get(base);
        return candidates == null ? Collections.<File>emptyList() : candidates;
    }

    /**
     * Recursively builds one file's node. Two different safeguards are at
     * play here, for two different problems:
     *   - pathVisited (per-branch, not global) stops a real include cycle
     *     from recursing forever, while still letting the same header show
     *     up under multiple different parents, same as a real preprocessor
     *     would actually re-encounter it.
     *   - globallyExpanded (genuinely global, shared across the whole scan)
     *     stops that legitimate "same header, multiple parents" case from
     *     re-walking the SAME subtree in full every single time. Without
     *     it, a widely-shared core header (Arduino.h and everything under
     *     it, for instance) gets its entire dependency chain re-expanded
     *     from scratch at every place it's included - which is exactly
     *     what was exhausting the node cap almost instantly on anything
     *     beyond a trivial sketch. The first occurrence anywhere in the
     *     scan gets expanded for real; every later occurrence of that exact
     *     file just shows as a leaf pointing back at it.
     */
    private DefaultMutableTreeNode buildNode(File file, Map<String, List<File>> headerIndex, Set<String> pathVisited,
            int[] nodeCount, Set<String> globalTouched, Set<String> globallyExpanded) {
        String path = file.getAbsolutePath();
        boolean alreadyExpandedElsewhere = globallyExpanded.contains(path);
        DepNode label = new DepNode(file.getName() + (alreadyExpandedElsewhere ? "  (see full listing above)" : ""), true, path);
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(label);
        nodeCount[0]++;
        globalTouched.add(path);

        if (alreadyExpandedElsewhere) {
            return node;
        }
        globallyExpanded.add(path);

        if (nodeCount[0] >= MAX_NODES) {
            node.add(new DefaultMutableTreeNode(new DepNode("... truncated (too many dependencies to display) ...", false)));
            return node;
        }

        String content;
        try {
            content = readFile(file);
        } catch (IOException ex) {
            return node; // unreadable - show it as a leaf rather than failing the whole scan
        }

        Set<String> branchVisited = new LinkedHashSet<String>(pathVisited);
        branchVisited.add(path);

        Matcher m = INCLUDE_PATTERN.matcher(content);
        Set<String> seenOnThisFile = new LinkedHashSet<String>(); // avoid duplicate children for the same file included twice in one file
        while (m.find() && nodeCount[0] < MAX_NODES) {
            boolean quoted = "\"".equals(m.group(1));
            String includedName = m.group(2);
            List<File> candidates = resolveInclude(file, includedName, quoted, headerIndex);
            if (candidates.isEmpty()) {
                String key = "?:" + includedName;
                if (seenOnThisFile.add(key)) {
                    node.add(new DefaultMutableTreeNode(new DepNode(includedName + " (not found)", false)));
                    nodeCount[0]++;
                }
                continue;
            }
            for (File candidate : candidates) {
                String key = candidate.getAbsolutePath();
                if (!seenOnThisFile.add(key)) {
                    continue; // this exact file already added as a child here
                }
                if (branchVisited.contains(key)) {
                    node.add(new DefaultMutableTreeNode(new DepNode(candidate.getName() + " (circular reference)", false)));
                    nodeCount[0]++;
                    continue;
                }
                node.add(buildNode(candidate, headerIndex, branchVisited, nodeCount, globalTouched, globallyExpanded));
            }
        }
        return node;
    }

    private static String readFile(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new FileReader(f));
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
}
