package fastbuilduix;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;
import javax.swing.text.StyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * The fastbuild settings frame. Covers every flag/config option documented in
 * the fastbuild README, organized into tabs:
 *
 * App Settings - the Arduino CLI executable and arduino-cli.yaml paths. Set
 * once here; every other tab that needs them (Project, Board Wizard) just
 * displays/reuses this single value instead of asking again. Auto-saved to
 * a small JSON file (see AppSettingsStore) and reloaded automatically next
 * time the UI starts.
 * Project - sketch/FQBN/cache root, the rest of the required/recommended fields
 * Cache & Dependencies - the persistent-cache and dependency-hashing knobs,
 * plus the one-shot CLI overrides for a single run
 * Output & Export - stats/logging/upload/export/build properties
 * Board Wizard - -configure-board and its cache settings, plus a native
 * (non-shelled-out) concurrent prefetch of every board's menu options,
 * sharing the same cache file/format the CLI's own -configure-board uses
 * Daemon - -daemon / -connect, both fully wired as background processes
 * Watch - -watch, wired as its own background process (independent of the
 * main build-runner), config piped over stdin same as a normal build
 *
 * Editing/loading/saving a fastbuild ".config" file, running builds/uploads,
 * the wizard, daemon, and watch are all fully wired to real fastbuild
 * subprocesses at this point - nothing here is a stub or placeholder.
 */
public class SettingsFrame extends JFrame {

    private File currentFile = null;

    /** Guards against the App Settings auto-save firing while we're programmatically populating fields. */
    private boolean loadingSettings = false;

    private JTabbedPane tabs;
    private JComponent appSettingsTabContent;
    private JComponent cacheTabContent;
    private JComponent outputTabContent;
    private JComponent daemonTabContent;
    private JComponent watchTabContent;
    private JComponent dependencyViewerTabContent;
    private DependencyViewerPanel dependencyViewerPanel;
    private int projectTabIndex = 0;
    private int explorerTabIndex = 0;
    private EditorTabPanel editorTabPanel;
    private HexViewerPanel hexViewerPanel;

    // --- App Settings tab (shared across Project + Board Wizard) ---
    private final JTextField arduinoCliField = new JTextField();
    private final JTextField configFileField = new JTextField();
    private final JTextField fastbuildExeField = new JTextField();
    private final JTextField defaultCacheRootField = new JTextField();
    private final JLabel appSettingsStatusLabel = new JLabel(" ");

    // Read-only mirrors shown on Project / Board Wizard so it's clear what's
    // configured without re-entering it.
    private final JLabel projectArduinoCliMirror = new JLabel();
    private final JLabel projectConfigFileMirror = new JLabel();
    private final JLabel wizardArduinoCliMirror = new JLabel();
    private final JLabel wizardConfigFileMirror = new JLabel();
    private final JLabel projectCacheRootMirror = new JLabel();
    private final JLabel wizardCacheRootMirror = new JLabel();

    // --- Project tab ---
    private final JTextField sketchField = new JTextField();
    private final JTextField fqbnField = new JTextField();
    private final JTextField cacheRootField = new JTextField();
    private final JCheckBox verboseCheck = new JCheckBox("Verbose (pass -verbose through to arduino-cli)");

    // --- Cache & Dependencies tab ---
    private final JCheckBox hashLibraryHeadersCheck = new JCheckBox("Hash library/core headers (requires Config File in App Settings)");
    private final JCheckBox hashToolchainCheck = new JCheckBox("Hash installed toolchain version (requires Config File in App Settings)");
    private final JComboBox<String> depsModeCombo = new JComboBox<String>(new String[]{"regex", "depfile"});
    private final JComboBox<String> platformVersionCombo = new JComboBox<String>();
    private final JLabel platformVersionCaption = new JLabel(" ");
    private final JButton cancelPrefetchButton = new JButton("Cancel Prefetch");
    private final JLabel prefetchStatusLabel = new JLabel(" ");
    private volatile boolean prefetchCancelled = false;
    private volatile java.util.concurrent.ExecutorService currentPrefetchPool;
    private javax.swing.Timer cancelSweepTimer;
    private final java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.atomic.AtomicReference<Process>> currentPrefetchProcessRefs =
            new java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.atomic.AtomicReference<Process>>();
    private volatile java.util.List<java.util.concurrent.Future<?>> currentPrefetchFutures;
    private final JCheckBox gccInjectMMDCheck = new JCheckBox("Inject -MMD manually (only relevant for depfile mode)");
    private final JSpinner depsIndexMaxAgeSpinner = new JSpinner(new SpinnerNumberModel(24, 0, 100000, 1));

    private final JCheckBox forceCheck = new JCheckBox("Force (-force): bypass skip-if-unchanged for this run only");
    private final JCheckBox cleanCheck = new JCheckBox("Clean (-clean): wipe persistent build folder before building");
    private final JCheckBox noDepsCheck = new JCheckBox("No deps (-no-deps): disable dependency hashing for this run");
    private final JCheckBox noToolchainCheck = new JCheckBox("No toolchain (-no-toolchain): disable toolchain fingerprinting for this run");
    private final JCheckBox refreshDepsIndexCheck = new JCheckBox("Refresh deps index now (-refresh-deps-index)");
    private final JButton forceRebuildHeaderIndexButton = new JButton("Force Rebuild Header Index Now");
    private final JButton forceCleanRebuildButton = new JButton("Clean & Rebuild Now");
    private final JButton forceRecompileButton = new JButton("Force Recompile Now");
    private final JCheckBox assumeYesStaleDepsCheck = new JCheckBox("Auto-rebuild stale index, no prompt (-assume-yes-stale-deps)");
    private final JCheckBox skipStaleDepsRefreshCheck = new JCheckBox("Keep stale index, no prompt (-skip-stale-deps-refresh)");

    // --- Output & Export tab ---
    private final JCheckBox showStatsCheck = new JCheckBox("Show stats after build (-stats)");
    private final JCheckBox jsonOutputCheck = new JCheckBox("JSON output (-json)");
    private final JCheckBox saveLogCheck = new JCheckBox("Save log to file (-save-log)");
    private final JTextField logDirField = new JTextField();
    private final JCheckBox exportCheck = new JCheckBox("Export binary to sketch folder (-export)");
    private final JComboBox<String> exportConflictCombo = new JComboBox<String>(new String[]{"ask", "overwrite", "rename"});
    private final JCheckBox alwaysReplaceOutputCheck = new JCheckBox("Always replace existing output file (hex/bin)");
    private final JTextArea buildPropsArea = new JTextArea(6, 30);
    private final JButton saveConfigButton = new JButton("Save Config");
    private final JLabel projectSettingsStatusLabel = new JLabel(" ");

    // --- Upload tab ---
    private final JCheckBox uploadCheck = new JCheckBox("Upload after build (-upload)");
    /** Editable so a port jSerialComm doesn't enumerate (network/Bluetooth serial, etc.) can still be typed in by hand. */
    private final JComboBox<String> portField = new JComboBox<String>();
    private final JButton refreshPortsButton = new JButton("Refresh Ports");
    private final JButton uploadNowButton = new JButton("Upload Now");
    private final JTextField hexFileField = new JTextField();
    private final JButton browseHexFileButton = new JButton("Browse\u2026");
    private final JButton uploadHexFileButton = new JButton("Upload This File");
    private final JLabel uploadStatusLabel = new JLabel(" ");
    private final JComboBox<String> baudRateCombo = new JComboBox<String>(
            new String[]{"300", "1200", "2400", "4800", "9600", "19200", "38400", "57600", "74880", "115200", "230400", "250000"});
    private final JComboBox<String> lineEndingCombo = new JComboBox<String>(new String[]{"No line ending", "Newline (\\n)", "Carriage return (\\r)", "Both (\\r\\n)"});
    private final JButton connectSerialButton = new JButton("Connect");
    private final JButton disconnectSerialButton = new JButton("Disconnect");
    private final JTextArea serialMonitorArea = new JTextArea();
    private final JTextField serialSendField = new JTextField();
    private final JButton serialSendButton = new JButton("Send");
    private final JButton serialClearButton = new JButton("Clear");
    private SerialMonitorSession serialSession;
    private String serialSessionPortName;
    private int uploadTabIndex = 0;

    // --- Board Wizard tab ---
    private final JTextField wizardCacheDirField = new JTextField();
    private final JCheckBox refreshWizardCacheCheck = new JCheckBox("Refresh wizard cache (-refresh-wizard-cache)");
    private final JComboBox<String> wizardPrefetchCombo = new JComboBox<String>(new String[]{"ask", "full", "off"});
    private final JSpinner wizardPrefetchWorkersSpinner = new JSpinner(new SpinnerNumberModel(8, 1, 64, 1));

    private final JButton loadPlatformsButton = new JButton("Load Platforms\u2026");
    private final JButton forceRefreshWizardCacheButton = new JButton("Force Refresh Wizard Cache & Reload");
    private final JCheckBox carryOverBoardOptionsCheck = new JCheckBox("Carry over previously cached board options on refresh");
    private final JButton loadSketchCacheButton = new JButton("Load Saved Selection\u2026");
    private final JLabel wizardStatusLabel = new JLabel(" ");
    private final JComboBox<InstalledPlatform> platformCombo = new JComboBox<InstalledPlatform>();
    private final JTextField boardFilterField = new JTextField();
    private final JComboBox<BoardEntry> boardCombo = new JComboBox<BoardEntry>();
    private final JPanel boardOptionsPanel = new JPanel(new GridBagLayout());
    private final JTextField fqbnPreviewField = new JTextField();
    private final JButton applyFqbnButton = new JButton("Apply to Project");
    private final JLabel wizardNotAppliedWarningLabel = new JLabel(" ");
    private final JLabel selectedBoardLabel = new JLabel("-");
    private final JProgressBar buildProgressBar = new JProgressBar(0, 100);
    private final JLabel buildTimerLabel = new JLabel(" ");
    private javax.swing.Timer buildElapsedTicker;

    /** All boards for the currently selected platform, unfiltered - boardFilterField narrows boardCombo's contents from this. */
    private java.util.List<BoardEntry> wizardPlatformBoards = new java.util.ArrayList<BoardEntry>();
    /** Board options for whichever board is currently selected in boardCombo. */
    private java.util.List<BoardConfigOption> wizardCurrentOptions = new java.util.ArrayList<BoardConfigOption>();
    /** One combo box per entry in wizardOptionsForCombos, same order, for reading back the chosen values. */
    private java.util.List<JComboBox<BoardConfigValue>> wizardOptionCombos = new java.util.ArrayList<JComboBox<BoardConfigValue>>();
    /** The subset of wizardCurrentOptions that actually got a combo box (options with no values are skipped) - kept 1:1 with wizardOptionCombos. */
    private java.util.List<BoardConfigOption> wizardOptionsForCombos = new java.util.ArrayList<BoardConfigOption>();
    private WizardCacheData wizardCache;
    private String wizardCurrentBaseFqbn = "";
    /** Suppresses platformCombo's own selection listener while we populate/restore it programmatically, so onPlatformSelected() runs exactly once instead of twice. */
    private boolean suppressPlatformComboEvents = false;
    /** Same idea for boardCombo - a JComboBox auto-selects (and fires) its first item as soon as it's added, mid-population, before any preferred selection is applied. */
    private boolean suppressBoardComboEvents = false;

    /** Set when a per-sketch cache is loaded, consumed (and cleared) as the wizard flow reaches each step. */
    private String pendingRestorePlatformId;
    private String pendingRestoreBoardFqbn;
    private java.util.Map<String, String> pendingRestoreOptionValues;

    // --- Daemon tab ---
    private final JCheckBox daemonCheck = new JCheckBox("Run as daemon (-daemon)");
    private final JTextField daemonAddrField = new JTextField();
    private final JComboBox<String> daemonStaleDepsPolicyCombo = new JComboBox<String>(new String[]{"skip", "refresh"});
    private final JCheckBox connectCheck = new JCheckBox("Send this build to a running daemon (-connect)");
    private final JTextField connectAddrField = new JTextField();
    private final JButton startDaemonButton = new JButton("Start Daemon");
    private final JButton connectAndBuildButton = new JButton("Connect & Build\u2026");
    private final JTextArea daemonLogArea = new JTextArea();
    private final JLabel daemonStatusLabel = new JLabel("Idle");
    private volatile Process daemonProcess;
    private SwingWorker<Integer, String> daemonWorker;
    private volatile Integer daemonPid;
    private volatile Process connectProcess;
    private final JTextArea watchLogArea = new JTextArea();
    private final JLabel watchStatusLabel = new JLabel("Idle");
    private final JButton startWatchButton = new JButton("Start Watch");
    private final JButton openSketchFolderButton = new JButton("Open Sketch Folder");
    private volatile Process watchProcess;
    private SwingWorker<Integer, String> watchWorker;
    private volatile Integer watchPid;
    private SwingWorker<Integer, String> connectWorker;

    // --- Watch tab ---
    private final JCheckBox watchCheck = new JCheckBox("Rebuild automatically on change (-watch)");
    private final JTextField watchIntervalField = new JTextField();

    // --- Bottom bar ---
    private final JLabel statusLabel = new JLabel("New (unsaved) config");
    private final JButton runBuildButton = new JButton("Run Build\u2026");
    private final JButton validateButton = new JButton("Validate");

    // --- Build Log tab ---
    private final JTextPane logArea = new JTextPane() {
        // JTextPane has no setLineWrap() - this is the standard way to get the same
        // "no wrap, horizontal scrollbar instead" behavior setLineWrap(false) gave on
        // the old JTextArea: only track the viewport's width when content actually
        // fits within it, otherwise let it overflow so the scroll pane shows a
        // horizontal scrollbar rather than wrapping long build command lines.
        public boolean getScrollableTracksViewportWidth() {
            java.awt.Container parent = getParent();
            return parent == null || getUI().getPreferredSize(this).width <= parent.getSize().width;
        }
    };
    private final JCheckBox hideRepeatingPathsCheck = new JCheckBox("Hide repeating tool paths");
    private final JCheckBox hideUserPathCheck = new JCheckBox("Hide user folder path");
    private final java.util.List<String> logRawLines = new java.util.ArrayList<String>();

    // --- Persistent activity log (visible under every tab, not just Build Log) ---
    private final JTextPane activityLogArea = new JTextPane();
    private final JCheckBox activityHideRepeatingPathsCheck = new JCheckBox("Hide repeating tool paths");
    private final JCheckBox activityHideUserPathCheck = new JCheckBox("Hide user folder path");
    private final java.util.List<String> activityLogRawLines = new java.util.ArrayList<String>();
    private final JLabel logStatusLabel = new JLabel("Idle");

    // --- Persistent status bar (Sketch / Board / Cache / Build time) ---
    private final JLabel statusBarSketchLabel = new JLabel("-");
    private final JLabel statusBarBoardLabel = new JLabel("-");
    private final JLabel statusBarCacheLabel = new JLabel("-");
    private final JLabel statusBarTimeLabel = new JLabel("-");
    private final JLabel statusBarFlashRamLabel = new JLabel("-");
    private volatile long currentOperationStartMillis;
    private volatile boolean sawCacheHitThisRun;
    private volatile boolean sawCacheMissThisRun;
    private final JButton cancelBuildButton = new JButton("Cancel");
    private int logTabIndex = 0;
    private JMenu fileMenu;
    private JMenu settingsMenu;

    // --- Running build state (accessed from both the EDT and the build's background thread) ---
    private volatile Process currentBuildProcess;
    private volatile Integer currentBuildPid;
    private volatile boolean currentBuildCancelled;
    private String currentOperationVerb = "Build";
    private File hexAutoFillSketchDir;
    private String lastProcessedSketchPath;
    private SwingWorker<Integer, String> currentBuildWorker;

    public SettingsFrame() {
        super("fastbuild settings");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        try {
            java.util.List<Image> icons = new java.util.ArrayList<>();
            for (String size : new String[]{"16", "32", "48", "256"}) {
                java.net.URL url = getClass().getResource("/resources/icon-" + size + ".png");
                if (url != null) {
                    icons.add(Toolkit.getDefaultToolkit().getImage(url));
                }
            }
            if (!icons.isEmpty()) {
                setIconImages(icons);
            } else {
                // none of the sized icons exist - fall back to the single icon that already worked
                Image fallback = Toolkit.getDefaultToolkit().getImage(getClass().getResource("/resources/icon.png"));
                setIconImage(fallback);
            }
        } catch (Exception e) {
            // missing icon shouldn't stop the app from launching
        }

        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent e) {
                saveProjectSettingsToDisk();
                saveSketchCacheSynchronously(readSettingsFromUI());
                System.exit(0);
            }
        });
        setJMenuBar(buildMenuBar());

        tabs = new JTabbedPane();
        appSettingsTabContent = buildAppSettingsTab(); // built once, but only shown in the tab bar on demand - see showOptionalTab()
        tabs.addTab("Project", buildProjectTab());
        projectTabIndex = tabs.getTabCount() - 1;
        tabs.addTab("Explorer", buildEditorTab());
        explorerTabIndex = tabs.getTabCount() - 1;
        tabs.addTab("Board Wizard", buildWizardTab());
        cacheTabContent = buildCacheTab();
        outputTabContent = buildOutputTab();
        tabs.addTab("Upload", buildUploadTab());
        uploadTabIndex = tabs.getTabCount() - 1;
        hexViewerPanel = buildHexViewerTab();
        daemonTabContent = buildDaemonTab();
        watchTabContent = buildWatchTab();
        dependencyViewerTabContent = buildDependencyViewerTab();
        tabs.addTab("Build Log", buildLogTab());
        logTabIndex = tabs.getTabCount() - 1;

        tabs.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent e) {
                if (tabs.getSelectedIndex() == uploadTabIndex) {
                    onRefreshPortsClicked();
                }
            }
        });

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(tabs, BorderLayout.CENTER);
        JPanel southStack = new JPanel(new BorderLayout());
        southStack.add(buildStatusBar(), BorderLayout.NORTH);
        southStack.add(buildActivityLogPanel(), BorderLayout.CENTER);
        getContentPane().add(southStack, BorderLayout.SOUTH);

        wireEnablement();
        wireAppSettingsAutoSave();
        wireContextMenus();
        sketchField.addFocusListener(new FocusAdapter() {
            public void focusLost(FocusEvent e) {
                onSketchPathChanged();
            }
        });
        loadFromSettings(new BuildSettings());
        loadAppSettingsFromDisk();
        loadProjectSettingsFromDisk();
        onSketchPathChanged();

        if (arduinoCliField.getText().trim().isEmpty() || fastbuildExeField.getText().trim().isEmpty()) {
            // Not configured yet - show it once automatically rather than leaving a first-time
            // user hunting through the File menu for where to set the required paths.
            showOptionalTab(appSettingsTabContent, "App Settings");
        }

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK), "fastbuild-ctrl-b");
        getRootPane().getActionMap().put("fastbuild-ctrl-b", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (editorTabPanel != null) {
                    editorTabPanel.saveAllOpenFiles();
                }
                runFastbuild(false, false, false, false);
            }
        });

        setPreferredSize(new Dimension(880, 600));
        pack();
        setMinimumSize(new Dimension(680, 460));
        setLocationRelativeTo(null);
    }

    // ------------------------------------------------------------------
    // Menu bar / file actions
    // ------------------------------------------------------------------
    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        fileMenu = new JMenu("File");

        JMenuItem newItem = new JMenuItem("New");
        newItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                currentFile = null;
                loadFromSettings(new BuildSettings());
                setStatusLabelText("New (unsaved) config");
                logActivity("New config started.");
            }
        });

        JMenuItem openItem = new JMenuItem("Open Config\u2026");
        openItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onOpen();
            }
        });

        JMenuItem saveItem = new JMenuItem("Save");
        saveItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onSave();
            }
        });

        JMenuItem saveAsItem = new JMenuItem("Save As\u2026");
        saveAsItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onSaveAs();
            }
        });

        JMenuItem exportBatItem = new JMenuItem("Export .bat File\u2026");
        exportBatItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onExportBatchFileClicked();
            }
        });

        JMenuItem appSettingsItem = new JMenuItem("App Settings\u2026");
        appSettingsItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showOptionalTab(appSettingsTabContent, "App Settings");
            }
        });

        JMenuItem cacheItem = new JMenuItem("Cache & Dependencies\u2026");
        cacheItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showOptionalTab(cacheTabContent, "Cache & Dependencies");
            }
        });

        JMenuItem outputItem = new JMenuItem("Output & Export\u2026");
        outputItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showOptionalTab(outputTabContent, "Output & Export");
            }
        });

        JMenuItem hexViewerItem = new JMenuItem("Hex Viewer\u2026");
        hexViewerItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showOptionalTab(hexViewerPanel, "Hex Viewer");
            }
        });

        JMenuItem daemonItem = new JMenuItem("Daemon\u2026");
        daemonItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showOptionalTab(daemonTabContent, "Daemon");
            }
        });

        JMenuItem watchItem = new JMenuItem("Watch\u2026");
        watchItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showOptionalTab(watchTabContent, "Watch");
            }
        });

        JMenuItem dependencyViewerItem = new JMenuItem("Dependency Viewer\u2026");
        dependencyViewerItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showOptionalTab(dependencyViewerTabContent, "Dependency Viewer");
            }
        });

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                saveProjectSettingsToDisk();
                saveSketchCacheSynchronously(readSettingsFromUI());
                System.exit(0);
            }
        });

        fileMenu.add(newItem);
        fileMenu.add(openItem);
        fileMenu.addSeparator();
        fileMenu.add(saveItem);
        fileMenu.add(saveAsItem);
        fileMenu.addSeparator();
        fileMenu.add(exportBatItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);
        menuBar.add(fileMenu);

        settingsMenu = new JMenu("Settings");
        settingsMenu.add(appSettingsItem);
        settingsMenu.add(cacheItem);
        settingsMenu.add(outputItem);
        settingsMenu.add(hexViewerItem);
        settingsMenu.add(daemonItem);
        settingsMenu.add(watchItem);
        settingsMenu.add(dependencyViewerItem);
        menuBar.add(settingsMenu);

        JMenu helpMenu = new JMenu("Help");
        JMenuItem quickHelpItem = new JMenuItem("Quick Help\u2026");
        quickHelpItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showQuickHelpDialog();
            }
        });
        JMenuItem aboutItem = new JMenuItem("About fastbuild settings\u2026");
        aboutItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showAboutDialog();
            }
        });
        helpMenu.add(quickHelpItem);
        helpMenu.addSeparator();
        helpMenu.add(aboutItem);
        menuBar.add(helpMenu);

        return menuBar;
    }

    private void showAboutDialog() {
        String message = "fastbuild settings\n"
                + "Version 1.0\n\n"
                + "A desktop UI for the fastbuild Arduino build tool.\n"
                + "This app doesn't replace arduino-cli - it wraps it with faster\n"
                + "incremental builds, a friendlier board picker, better logging,\n"
                + "easier configuration, and a lightweight sketch editor.\n\n"
                + "See README.md and HOW_TO_GUIDE.md alongside the source for\n"
                + "full documentation.";
        JOptionPane.showMessageDialog(this, message, "About", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showQuickHelpDialog() {
        String helpText =
                "EVERYDAY USE\n"
                + "1. Project tab - browse to your sketch's .ino file.\n"
                + "2. Board Wizard tab - pick your Platform, Board, and options,\n"
                + "   then click Apply to Project (picking a board alone doesn't\n"
                + "   change what gets built until you do this).\n"
                + "3. Run Build on the Project tab. Output streams into Build Log.\n"
                + "4. To upload, pick a port on the Upload tab and click Upload Now\n"
                + "   (or tick Upload as part of the build).\n\n"
                + "WHAT DO I CLICK WHEN...?\n"
                + "- Just installed a new board type / library\n"
                + "    -> Cache & Dependencies -> Force Rebuild Header Index Now\n"
                + "- Build seems to be using old/wrong code\n"
                + "    -> Cache & Dependencies -> Force Recompile Now\n"
                + "- Something's properly broken and won't rebuild right\n"
                + "    -> Cache & Dependencies -> Clean & Rebuild Now\n"
                + "- Picked a different board in the wizard\n"
                + "    -> Click Apply to Project before building\n"
                + "- Want to re-upload something already built, without rebuilding\n"
                + "    -> Upload tab -> Upload This File\n"
                + "- Board Wizard is showing an old list of boards\n"
                + "    -> Board Wizard -> Force Refresh Wizard Cache & Reload\n\n"
                + "STATUS BAR (bottom of every tab)\n"
                + "Sketch / Board / Cache (HIT = reused previous result, MISS =\n"
                + "actually recompiled) / Flash-RAM (from the last real compile -\n"
                + "kept showing through cache hits, since a hit means the exact\n"
                + "same binary as before).\n\n"
                + "KEYBOARD SHORTCUTS\n"
                + "Ctrl+B   Save every open file with unsaved changes, then run a\n"
                + "         build - works from anywhere in the window, not just\n"
                + "         while editing.\n"
                + "Ctrl+S   Save the current file (while editing, in the Explorer\n"
                + "         tab).\n"
                + "Enter    In the Explorer tab's Find box, jumps to the next\n"
                + "         match (same as clicking Find Next).\n\n"
                + "For the full guide (every setting explained, plus a more\n"
                + "technical Advanced section), see HOW_TO_GUIDE.md alongside\n"
                + "the source.";
        JTextArea textArea = new JTextArea(helpText, 24, 64);
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(textArea);
        JOptionPane.showMessageDialog(this, scroll, "Quick Help", JOptionPane.PLAIN_MESSAGE);
    }

    private void onOpen() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open fastbuild config");
        if (currentFile != null) {
            chooser.setCurrentDirectory(currentFile.getParentFile());
        }
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        try {
            BuildSettings loaded = ConfigFileCodec.load(file);
            loadFromSettings(loaded);
            currentFile = file;
            setStatusLabelText(file.getAbsolutePath());
            logActivity("Opened config: " + file.getAbsolutePath());
        } catch (Exception ex) {
            logActivity("Failed to open config " + file.getAbsolutePath() + ": " + ex.getMessage());
            JOptionPane.showMessageDialog(this,
                    "Could not load config:\n" + ex.getMessage(),
                    "Open Config", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onSave() {
        if (currentFile == null) {
            onSaveAs();
            return;
        }
        writeToFile(currentFile, false);
    }

    private void onSaveAs() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save fastbuild config");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("fastbuild config (*.config)", "config"));
        if (currentFile != null) {
            chooser.setSelectedFile(currentFile);
        }
        int result = chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = ensureConfigExtension(chooser.getSelectedFile());
        writeToFile(file, true);
    }

    /** Appends .config if the user's chosen/typed name doesn't already end with it (case-insensitively) - lets someone freely customize the filename in Save As without ending up with an extensionless file. */
    private static File ensureConfigExtension(File file) {
        if (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".config")) {
            return file;
        }
        return new File(file.getParentFile(), file.getName() + ".config");
    }

    /** updateCurrentFileOnSuccess: Save As wants currentFile updated once the write actually succeeds; plain Save already has the right currentFile. */
    private void writeToFile(final File file, final boolean updateCurrentFileOnSuccess) {
        final BuildSettings settings = readSettingsFromUI();
        String missing = settings.validateRequired();
        if (missing != null) {
            int choice = JOptionPane.showConfirmDialog(this,
                    missing + "\n\nSave anyway?",
                    "Incomplete config", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }
        new SwingWorker<Void, Void>() {
            private String error;

            protected Void doInBackground() {
                try {
                    ConfigFileCodec.save(settings, file);
                } catch (Exception ex) {
                    error = ex.getMessage();
                }
                return null;
            }

            protected void done() {
                if (error == null) {
                    setStatusLabelText(file.getAbsolutePath());
                    logActivity("Saved config: " + file.getAbsolutePath());
                    if (updateCurrentFileOnSuccess) {
                        currentFile = file;
                    }
                } else {
                    setStatusLabelText("Error saving: " + error);
                    logActivity("Failed to save config " + file.getAbsolutePath() + ": " + error);
                    JOptionPane.showMessageDialog(SettingsFrame.this,
                            "Could not save config:\n" + error,
                            "Save Config", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    // ------------------------------------------------------------------
    // App Settings: shared fields + JSON persistence
    // ------------------------------------------------------------------

    private void wireAppSettingsAutoSave() {
        DocumentListener listener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                handleAppSettingsChanged();
            }

            public void removeUpdate(DocumentEvent e) {
                handleAppSettingsChanged();
            }

            public void changedUpdate(DocumentEvent e) {
                handleAppSettingsChanged();
            }
        };
        arduinoCliField.getDocument().addDocumentListener(listener);
        configFileField.getDocument().addDocumentListener(listener);
        fastbuildExeField.getDocument().addDocumentListener(listener);
        defaultCacheRootField.getDocument().addDocumentListener(listener);

        // Covers typing/pasting the CLI path directly instead of using Browse
        // (Browse itself triggers this via addPathRow's afterBrowse callback).
        arduinoCliField.addFocusListener(new FocusAdapter() {
            public void focusLost(FocusEvent e) {
                maybeAutoFillYamlPath();
            }
        });
    }

    /**
     * If arduino-cli.yaml sits right next to the configured Arduino CLI
     * executable, fills the yaml field in automatically - but only while
     * that field is still empty, so it never overwrites a path someone
     * deliberately typed or browsed to.
     */
    private void maybeAutoFillYamlPath() {
        String cliPath = arduinoCliField.getText().trim();
        if (cliPath.isEmpty() || !configFileField.getText().trim().isEmpty()) {
            return;
        }
        File cliFile = new File(cliPath);
        File parent = cliFile.getParentFile();
        if (parent == null) {
            return;
        }
        File candidate = new File(parent, "arduino-cli.yaml");
        if (candidate.exists()) {
            configFileField.setText(candidate.getAbsolutePath());
        }
    }

    private void handleAppSettingsChanged() {
        refreshAppSettingsMirrors();
        if (!loadingSettings) {
            saveAppSettingsToDisk();
        }
    }

    private void refreshAppSettingsMirrors() {
        String cli = arduinoCliField.getText().trim();
        String yaml = configFileField.getText().trim();
        projectArduinoCliMirror.setText(mirrorText(cli));
        projectConfigFileMirror.setText(mirrorText(yaml));
        wizardArduinoCliMirror.setText(mirrorText(cli));
        wizardConfigFileMirror.setText(mirrorText(yaml));

        // Cache root is app-wide now, not per-sketch - Cache root and Wizard cache
        // dir always mirror this one value, unconditionally, so there's nothing left
        // to independently edit or drift out of sync.
        String cacheRoot = defaultCacheRootField.getText().trim();
        if (cacheRoot.isEmpty()) {
            cacheRoot = BuildSettings.defaultCacheRoot();
        }
        cacheRootField.setText(cacheRoot);
        wizardCacheDirField.setText(cacheRoot);
        projectCacheRootMirror.setText(mirrorText(cacheRoot));
        wizardCacheRootMirror.setText(mirrorText(cacheRoot));
    }

    private static String mirrorText(String value) {
        return value.isEmpty() ? "(not set)" : value;
    }

    private void loadAppSettingsFromDisk() {
        File file = AppSettingsStore.defaultFile();
        try {
            AppSettings loaded = AppSettingsStore.load(file);
            loadingSettings = true;
            arduinoCliField.setText(loaded.arduinoCliPath);
            configFileField.setText(loaded.arduinoCliYamlPath);
            fastbuildExeField.setText(loaded.fastbuildExePath);
            defaultCacheRootField.setText(loaded.defaultCacheRoot.isEmpty()
                    ? new File(System.getProperty("user.home"), ".arduino-fastbuild").getAbsolutePath()
                    : loaded.defaultCacheRoot);
            loadingSettings = false;
            refreshAppSettingsMirrors();
            if (file.exists()) {
                appSettingsStatusLabel.setText("Loaded from " + file.getAbsolutePath());
                logActivity("Loaded App Settings from " + file.getAbsolutePath());
            } else {
                appSettingsStatusLabel.setText("Will be saved to " + file.getAbsolutePath());
            }
        } catch (Exception ex) {
            loadingSettings = false;
            appSettingsStatusLabel.setText("Could not load " + file.getAbsolutePath() + ": " + ex.getMessage());
            logActivity("Failed to load App Settings: " + ex.getMessage());
        }
    }

    private void saveAppSettingsToDisk() {
        final File file = AppSettingsStore.defaultFile();
        final AppSettings settings = new AppSettings();
        settings.arduinoCliPath = arduinoCliField.getText().trim();
        settings.arduinoCliYamlPath = configFileField.getText().trim();
        settings.fastbuildExePath = fastbuildExeField.getText().trim();
        settings.defaultCacheRoot = defaultCacheRootField.getText().trim();
        new SwingWorker<Void, Void>() {
            private String error;

            protected Void doInBackground() {
                try {
                    AppSettingsStore.save(settings, file);
                } catch (Exception ex) {
                    error = ex.getMessage();
                }
                return null;
            }

            protected void done() {
                if (error == null) {
                    appSettingsStatusLabel.setText("Saved to " + file.getAbsolutePath());
                    logActivity("Saved App Settings to " + file.getAbsolutePath());
                } else {
                    appSettingsStatusLabel.setText("Could not save to " + file.getAbsolutePath() + ": " + error);
                    logActivity("Failed to save App Settings: " + error);
                }
            }
        }.execute();
    }

    /** Adds an optional tab back if it's currently closed, then selects it either way. */
    private void showOptionalTab(JComponent content, String title) {
        int idx = tabs.indexOfComponent(content);
        if (idx < 0) {
            tabs.addTab(title, content);
            idx = tabs.getTabCount() - 1;
            tabs.setTabComponentAt(idx, buildClosableTabHeader(title, content));
        }
        tabs.setSelectedIndex(idx);
    }

    private void closeOptionalTab(JComponent content) {
        int idx = tabs.indexOfComponent(content);
        if (idx >= 0) {
            tabs.remove(idx);
        }
    }

    /** Tab header with a close button - for tabs that are only meant to be open while actively in use, reopenable via the File menu. */
    private JPanel buildClosableTabHeader(String title, final JComponent content) {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        header.setOpaque(false);
        header.add(new JLabel(title));

        JButton closeButton = new JButton("\u00d7");
        closeButton.setMargin(new Insets(0, 4, 0, 4));
        closeButton.setBorderPainted(false);
        closeButton.setContentAreaFilled(false);
        closeButton.setFocusable(false);
        closeButton.setFont(closeButton.getFont().deriveFont(Font.BOLD));
        closeButton.setToolTipText("Close (reopen anytime via the File menu)");
        closeButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                closeOptionalTab(content);
            }
        });
        header.add(closeButton);
        return header;
    }

    // ------------------------------------------------------------------
    // Project settings JSON (whole BuildSettings, fixed location, no dialog)
    // ------------------------------------------------------------------

    private void loadProjectSettingsFromDisk() {
        File file = ProjectSettingsStore.defaultFile();
        if (!file.exists()) {
            projectSettingsStatusLabel.setText("Will be saved to " + file.getAbsolutePath());
            return;
        }
        try {
            BuildSettings loaded = ProjectSettingsStore.load(file);
            loadFromSettings(loaded);
            projectSettingsStatusLabel.setText("Loaded from " + file.getAbsolutePath());
            logActivity("Loaded project settings from " + file.getAbsolutePath());
        } catch (Exception ex) {
            projectSettingsStatusLabel.setText("Could not load " + file.getAbsolutePath() + ": " + ex.getMessage());
            logActivity("Failed to load project settings: " + ex.getMessage());
        }
    }

    /** Synchronous on purpose - used only at the two exit points (window close, File > Exit), where the write must actually finish before System.exit() runs, not just be fired off. */
    private void saveProjectSettingsToDisk() {
        File file = ProjectSettingsStore.defaultFile();
        try {
            ProjectSettingsStore.save(readSettingsFromUI(), file);
            projectSettingsStatusLabel.setText("Saved to " + file.getAbsolutePath());
            logActivity("Saved project settings to " + file.getAbsolutePath());
        } catch (Exception ex) {
            projectSettingsStatusLabel.setText("Could not save to " + file.getAbsolutePath() + ": " + ex.getMessage());
            logActivity("Failed to save project settings: " + ex.getMessage());
        }
    }

    /** Use this everywhere except the exit paths - same save, just off the EDT, since nothing here needs to block on it finishing. */
    private void saveProjectSettingsToDiskAsync() {
        final File file = ProjectSettingsStore.defaultFile();
        final BuildSettings settings = readSettingsFromUI();
        new SwingWorker<Void, Void>() {
            private String error;

            protected Void doInBackground() {
                try {
                    ProjectSettingsStore.save(settings, file);
                } catch (Exception ex) {
                    error = ex.getMessage();
                }
                return null;
            }

            protected void done() {
                if (error == null) {
                    projectSettingsStatusLabel.setText("Saved to " + file.getAbsolutePath());
                    logActivity("Saved project settings to " + file.getAbsolutePath());
                } else {
                    projectSettingsStatusLabel.setText("Could not save to " + file.getAbsolutePath() + ": " + error);
                    logActivity("Failed to save project settings: " + error);
                }
            }
        }.execute();
    }

    // ------------------------------------------------------------------
    // Per-sketch cache (settings + wizard platform/board/option selection),
    // saved next to the sketch itself so the same sketch always finds its
    // own settings again - most sketches get built many times over.
    // ------------------------------------------------------------------

    /**
     * If a cache exists next to the given sketch, loads it: repopulates every
     * tab, and stashes the remembered platform/board/option selection so the
     * Board Wizard can restore it too, the next time Load Platforms runs
     * (platforms/boards/options aren't known until that fetch completes, so
     * the restore can't happen immediately here).
     */
    /**
     * Discards whatever's in hexFileField if it doesn't actually belong to
     * the current sketch - either it no longer exists, or it lives in a
     * different folder entirely. A per-sketch cache file can end up with a
     * wrong path baked in (e.g. from a past bug, or the file was since
     * moved/deleted); this stops that from being trusted blindly on restore,
     * and lets maybeAutoFillHexFile() correctly re-detect the right one
     * afterward instead.
     */
    private void discardHexFileIfNotForThisSketch() {
        String hexPath = hexFileField.getText().trim();
        if (hexPath.isEmpty()) {
            return;
        }
        String sketchPath = sketchField.getText().trim();
        File sketchDir = sketchPath.isEmpty() ? null : new File(sketchPath).getParentFile();
        File hexDir = new File(hexPath).getParentFile();
        boolean stillExists = new File(hexPath).isFile();
        boolean sameFolderAsSketch = sketchDir != null && hexDir != null && hexDir.equals(sketchDir);
        if (!stillExists || !sameFolderAsSketch) {
            logActivity("Discarding remembered hex/bin file that no longer matches this sketch: " + hexPath);
            hexFileField.setText("");
        }
    }

    private void maybeLoadSketchCache() {
        String sketchPath = sketchField.getText().trim();
        File cacheFile = SketchSettingsStore.cacheFileFor(sketchPath);
        if (cacheFile == null || !cacheFile.isFile()) {
            lastFlashUsageText = null;
            lastRamUsageText = null;
            updateStatusBarFlashRamLabel();
            if (!sketchPath.isEmpty() && !fqbnField.getText().trim().isEmpty()) {
                setStatusLabelText("No saved settings for this sketch yet - FQBN/board fields may still be from a different sketch, double-check before building/uploading.");
            }
            return;
        }
        try {
            SketchSettingsStore.Loaded loaded = SketchSettingsStore.load(cacheFile);
            if (loaded == null) {
                return;
            }
            loadFromSettings(loaded.settings);
            discardHexFileIfNotForThisSketch();
            pendingRestorePlatformId = loaded.platformId;
            pendingRestoreBoardFqbn = loaded.boardFqbn;
            pendingRestoreOptionValues = loaded.optionValues;
            // A cache hit means the exact same binary as last time, so these figures are
            // still accurate even though this session hasn't actually compiled anything
            // yet - see the matching note in SketchSettingsStore.
            lastFlashUsageText = loaded.lastFlashUsageText;
            lastRamUsageText = loaded.lastRamUsageText;
            updateStatusBarFlashRamLabel();
            setStatusLabelText("Restored previous settings for this sketch from " + cacheFile.getAbsolutePath());
            logActivity("Restored sketch settings from " + cacheFile.getAbsolutePath());

            // The Board Wizard tab only actually applies a pending restore once
            // Load Platforms runs (it can't select a platform/board that hasn't
            // been fetched yet) - so if there's a remembered wizard selection,
            // kick that fetch off automatically instead of leaving it stranded
            // until the user happens to click Load Platforms themselves. This
            // will very likely hit the shared wizard cache, so it should be quick.
            if (pendingRestorePlatformId != null) {
                if (arduinoCliField.getText().trim().isEmpty()) {
                    wizardStatusLabel.setText("This sketch has a saved board selection, but Arduino CLI isn't set in App Settings yet.");
                } else {
                    wizardStatusLabel.setText("Restoring saved platform/board selection\u2026");
                    onLoadPlatformsClicked(false);
                }
            } else {
                wizardStatusLabel.setText("This sketch's saved settings don't include a board-wizard selection.");
            }
        } catch (Exception ex) {
            // Best-effort - a corrupt/unreadable per-sketch cache just means starting fresh for it.
        }
    }

    /** Everything that should happen when the sketch path changes - restoring its remembered settings, then auto-detecting a hex file if nothing was remembered. */
    private void onSketchPathChanged() {
        String sketchPath = sketchField.getText().trim();
        if (sketchPath.equals(lastProcessedSketchPath)) {
            return; // nothing actually changed - e.g. a right-click context menu can fire a spurious focus-lost
        }
        String previousSketchPath = lastProcessedSketchPath;
        lastProcessedSketchPath = sketchPath;

        // The UI still reflects the outgoing sketch's settings at this point (maybeLoadSketchCache()
        // below is what overwrites the fields with the new sketch's saved values) - so this is the
        // last chance to persist anything changed since the last build/Apply for that sketch, before
        // it's gone. Overriding .sketch is needed because sketchField itself already shows the new path.
        if (previousSketchPath != null && !previousSketchPath.isEmpty()) {
            BuildSettings outgoing = readSettingsFromUI();
            outgoing.sketch = previousSketchPath;
            saveSketchCacheAfterSuccessfulBuild(outgoing);
        }

        File currentDir = sketchPath.isEmpty() ? null : new File(sketchPath).getParentFile();
        boolean dirChanged = currentDir == null ? (hexAutoFillSketchDir != null) : !currentDir.equals(hexAutoFillSketchDir);
        if (dirChanged) {
            // Reset so this folder gets a clean look - a per-sketch cache restore (below) or
            // maybeAutoFillHexFile() can then correctly fill it in for THIS sketch, instead of
            // silently leaving whatever path was left over from the previous sketch in place.
            hexFileField.setText("");
        }
        hexAutoFillSketchDir = currentDir;
        if (hexViewerPanel != null) {
            hexViewerPanel.setDefaultDirectory(currentDir);
        }

        maybeLoadSketchCache();
        maybeAutoFillHexFile();
        if (editorTabPanel != null) {
            editorTabPanel.refreshForSketch(sketchField.getText().trim());
        }
        if (dependencyViewerPanel != null) {
            dependencyViewerPanel.clearForSketchChange();
        }
        saveProjectSettingsToDiskAsync(); // remember "whatever sketch is open now" immediately, not just on an explicit Save Config click
    }

    /**
     * If the hex/bin field is still empty (nothing remembered for this
     * sketch), looks in the sketch's own folder for a hex/bin file - e.g.
     * one left behind by "Export binary to sketch folder" on the Output &
     * Export tab - and fills it in automatically, preferring whichever one
     * was modified most recently if there's more than one.
     */
    private void maybeAutoFillHexFile() {
        if (!hexFileField.getText().trim().isEmpty()) {
            return;
        }
        String sketchPath = sketchField.getText().trim();
        if (sketchPath.isEmpty()) {
            return;
        }
        File sketchDir = new File(sketchPath).getParentFile();
        if (sketchDir == null || !sketchDir.isDirectory()) {
            return;
        }
        File[] candidates = sketchDir.listFiles(new java.io.FilenameFilter() {
            public boolean accept(File dir, String name) {
                String lower = name.toLowerCase();
                return lower.endsWith(".hex") || lower.endsWith(".bin");
            }
        });
        if (candidates == null || candidates.length == 0) {
            return;
        }
        File newest = candidates[0];
        for (File f : candidates) {
            if (f.lastModified() > newest.lastModified()) {
                newest = f;
            }
        }
        hexFileField.setText(newest.getAbsolutePath());
        uploadStatusLabel.setText("Found " + newest.getName() + " in the sketch folder.");
        logActivity("Auto-detected hex/bin file: " + newest.getAbsolutePath());
    }

    private void onBrowseHexFileClicked() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Hex/Bin File to Upload");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Hex/bin files (*.hex, *.bin)", "hex", "bin"));
        String existing = hexFileField.getText().trim();
        if (!existing.isEmpty()) {
            File existingFile = new File(existing);
            if (existingFile.getParentFile() != null && existingFile.getParentFile().isDirectory()) {
                chooser.setCurrentDirectory(existingFile.getParentFile());
            }
        } else {
            String sketchPath = sketchField.getText().trim();
            if (!sketchPath.isEmpty()) {
                File sketchDir = new File(sketchPath).getParentFile();
                if (sketchDir != null && sketchDir.isDirectory()) {
                    chooser.setCurrentDirectory(sketchDir);
                }
            }
        }
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        hexFileField.setText(chooser.getSelectedFile().getAbsolutePath());
        logActivity("Selected hex/bin file: " + chooser.getSelectedFile().getAbsolutePath());
    }

    /**
     * Manual fallback for maybeLoadSketchCache(): opens a file chooser
     * starting in the current sketch's own folder (where its cache file
     * would normally sit) so you can pick it - or any other saved cache -
     * by hand if the automatic restore didn't work out.
     */
    private void onLoadSketchCacheClicked() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Load Saved Board Selection");
        String sketchPath = sketchField.getText().trim();
        if (!sketchPath.isEmpty()) {
            File sketchFile = new File(sketchPath);
            File sketchDir = sketchFile.getParentFile();
            if (sketchDir != null && sketchDir.isDirectory()) {
                chooser.setCurrentDirectory(sketchDir);
                chooser.setSelectedFile(new File(sketchDir, SketchSettingsStore.CACHE_FILE_NAME));
            }
        }
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File chosen = chooser.getSelectedFile();
        try {
            SketchSettingsStore.Loaded loaded = SketchSettingsStore.load(chosen);
            if (loaded == null) {
                JOptionPane.showMessageDialog(this,
                        "That doesn't look like a fastbuild-ui settings file.", "Load Saved Selection", JOptionPane.WARNING_MESSAGE);
                return;
            }
            loadFromSettings(loaded.settings);
            discardHexFileIfNotForThisSketch();
            pendingRestorePlatformId = loaded.platformId;
            pendingRestoreBoardFqbn = loaded.boardFqbn;
            pendingRestoreOptionValues = loaded.optionValues;
            setStatusLabelText("Restored settings from " + chosen.getAbsolutePath());
            logActivity("Loaded saved board selection from " + chosen.getAbsolutePath());
            maybeAutoFillHexFile();
            if (pendingRestorePlatformId != null) {
                if (arduinoCliField.getText().trim().isEmpty()) {
                    wizardStatusLabel.setText("This file has a saved board selection, but Arduino CLI isn't set in App Settings yet.");
                } else {
                    wizardStatusLabel.setText("Restoring saved platform/board selection\u2026");
                    onLoadPlatformsClicked(false);
                }
            } else {
                wizardStatusLabel.setText("Loaded settings, but this file has no saved board-wizard selection - pick a platform/board below.");
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Could not load that file:\n" + rootErrorMessage(ex), "Load Saved Selection", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Remembers this sketch's settings (and wizard selection, if any) for next time - called after a successful build/upload, and also immediately on Apply to Project so a board choice isn't lost if the app closes before the next rebuild. */
    /** Async wrapper for routine calls - use saveSketchCacheSynchronously instead at exit, where the JVM terminates right after and an in-flight background write would be lost. */
    private void saveSketchCacheAfterSuccessfulBuild(BuildSettings settings) {
        final File cacheFile = SketchSettingsStore.cacheFileFor(settings.sketch);
        if (cacheFile == null) {
            return;
        }
        String platformId = null;
        InstalledPlatform selectedPlatform = (InstalledPlatform) platformCombo.getSelectedItem();
        if (selectedPlatform != null) {
            platformId = selectedPlatform.id;
        }
        final String finalPlatformId = platformId;
        final String boardFqbn = wizardCurrentBaseFqbn.isEmpty() ? null : wizardCurrentBaseFqbn;
        final java.util.Map<String, String> optionValues = currentWizardOptionSelections();

        new SwingWorker<Void, Void>() {
            private String error;

            protected Void doInBackground() {
                try {
                    SketchSettingsStore.save(settings, finalPlatformId, boardFqbn, optionValues, lastFlashUsageText, lastRamUsageText, cacheFile);
                } catch (IOException ex) {
                    error = ex.getMessage();
                }
                return null;
            }

            protected void done() {
                if (error == null) {
                    logActivity("Saved per-sketch settings cache to " + cacheFile.getAbsolutePath());
                } else {
                    appendLogLine("(could not save per-sketch settings cache: " + error + ")");
                    logActivity("Failed to save per-sketch settings cache: " + error);
                }
            }
        }.execute();
    }

    /** Same save as saveSketchCacheAfterSuccessfulBuild, but on the calling thread - use this at exit, where System.exit(0) runs immediately after and would otherwise kill an async write mid-flight. */
    private void saveSketchCacheSynchronously(BuildSettings settings) {
        File cacheFile = SketchSettingsStore.cacheFileFor(settings.sketch);
        if (cacheFile == null) {
            return;
        }
        String platformId = null;
        InstalledPlatform selectedPlatform = (InstalledPlatform) platformCombo.getSelectedItem();
        if (selectedPlatform != null) {
            platformId = selectedPlatform.id;
        }
        String boardFqbn = wizardCurrentBaseFqbn.isEmpty() ? null : wizardCurrentBaseFqbn;
        java.util.Map<String, String> optionValues = currentWizardOptionSelections();
        try {
            SketchSettingsStore.save(settings, platformId, boardFqbn, optionValues, lastFlashUsageText, lastRamUsageText, cacheFile);
        } catch (IOException ex) {
            // Best-effort at exit - nothing left to report to once the window is gone.
        }
    }

    /** The option -> chosen-value map for whatever's currently selected in the wizard's option combos. */
    private java.util.Map<String, String> currentWizardOptionSelections() {
        java.util.Map<String, String> selections = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < wizardOptionsForCombos.size() && i < wizardOptionCombos.size(); i++) {
            BoardConfigOption opt = wizardOptionsForCombos.get(i);
            BoardConfigValue chosen = (BoardConfigValue) wizardOptionCombos.get(i).getSelectedItem();
            if (chosen != null) {
                selections.put(opt.option, chosen.value);
            }
        }
        return selections;
    }

    // ------------------------------------------------------------------
    // Tab builders
    // ------------------------------------------------------------------

    private JScrollPane buildAppSettingsTab() {
        JPanel panel = newFormPanel();
        int row = 0;

        JLabel intro = new JLabel("<html>Set the Arduino CLI executable and its arduino-cli.yaml once here."
                + " The Project and Board Wizard tabs both reuse these values instead of asking again.</html>");
        row = addFullWidth(panel, row, intro);

        row = addPathRow(panel, row, "Arduino CLI executable:", arduinoCliField, false, new Runnable() {
            public void run() {
                maybeAutoFillYamlPath();
            }
        });
        row = addPathRow(panel, row, "arduino-cli.yaml (config file):", configFileField, false);
        row = addPathRow(panel, row, "fastbuild executable:", fastbuildExeField, false);
        row = addPathRow(panel, row, "Default cache root:", defaultCacheRootField, true);
        JLabel cacheRootNote = new JLabel("<html>Used for every cache operation - the build cache (Project tab) and the"
                + " Board Wizard's own cache both always mirror this single value, shown there read-only.</html>");
        row = addFullWidth(panel, row, cacheRootNote);

        JButton saveNowButton = new JButton("Save App Settings Now");
        saveNowButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                saveAppSettingsToDisk();
            }
        });
        row = addFullWidth(panel, row, saveNowButton);

        appSettingsStatusLabel.setFont(appSettingsStatusLabel.getFont().deriveFont(Font.ITALIC));
        row = addFullWidth(panel, row, appSettingsStatusLabel);

        JLabel note = new JLabel("<html>These two fields are auto-saved to <code>" + AppSettingsStore.DEFAULT_FILE_NAME
                + "</code> in this app's working directory, and reloaded automatically next time it starts.</html>");
        row = addFullWidth(panel, row, note);

        addVerticalGlue(panel, row);
        return wrapScroll(panel);
    }

    private JScrollPane buildProjectTab() {
        JPanel panel = newFormPanel();
        int row = 0;

        JPanel appSettingsSummary = titled("From App Settings");
        int ar = 0;
        ar = addFullWidth(appSettingsSummary, ar, labelRow("Arduino CLI executable:", projectArduinoCliMirror));
        ar = addFullWidth(appSettingsSummary, ar, labelRow("arduino-cli.yaml:", projectConfigFileMirror));
        ar = addFullWidth(appSettingsSummary, ar, labelRow("Cache root:", projectCacheRootMirror));
        JButton editButton = new JButton("Edit in App Settings\u2026");
        editButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showOptionalTab(appSettingsTabContent, "App Settings");
            }
        });
        ar = addFullWidth(appSettingsSummary, ar, editButton);
        row = addFullWidthComponent(panel, row, appSettingsSummary);

        row = addPathRow(panel, row, "Sketch (.ino):", sketchField, false, new Runnable() {
            public void run() {
                onSketchPathChanged();
            }
        });
        row = addRow(panel, row, "FQBN:", fqbnField);
        row = addFullWidth(panel, row, verboseCheck);

        row = addFullWidthComponent(panel, row, new JSeparator());
        row = addFullWidthComponent(panel, row, buildProjectActionBar());

        addVerticalGlue(panel, row);
        return wrapScroll(panel);
    }

    /**
     * Validate / Run Build act on settings gathered from every tab (cache,
     * output, daemon, watch - not just this one), but they and the
     * current-file status live here in Project since that's the one place
     * you're always working with a specific ".config" file.
     */
    /** Sets statusLabel's text wrapped at a fixed width via HTML, so a long path wraps onto multiple lines instead of expanding the label wide enough to overlap the Validate/Run Build buttons next to it. */
    private void setStatusLabelText(String text) {
        statusLabel.setText("<html><div style='width: 380px;'>" + htmlEscape(text) + "</div></html>");
    }

    private static String htmlEscape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private JPanel buildProjectActionBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(new EmptyBorder(8, 0, 0, 0));
        bar.add(statusLabel, BorderLayout.WEST);

        validateButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (!confirmUnappliedWizardChangesIfAny()) {
                    return;
                }
                BuildSettings settings = readSettingsFromUI();
                String missing = settings.validateRequired();
                if (missing == null) {
                    JOptionPane.showMessageDialog(SettingsFrame.this,
                            "All required fields are set.", "Validate", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(SettingsFrame.this,
                            missing, "Validate", JOptionPane.WARNING_MESSAGE);
                }
            }
        });

        runBuildButton.setToolTipText("Runs fastbuild with these settings - see the Build Log tab");
        runBuildButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (!confirmUnappliedWizardChangesIfAny()) {
                    return;
                }
                runFastbuild(false, false, false, false);
            }
        });

        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        rightButtons.add(validateButton);
        rightButtons.add(runBuildButton);
        bar.add(rightButtons, BorderLayout.EAST);
        return bar;
    }

    /**
     * If the Board Wizard has a pending selection that was never applied to the Project
     * tab (same condition as wizardNotAppliedWarningLabel), asks before Validate/Build
     * proceeds - building against the wrong board silently is exactly the confusion
     * that label was already added to prevent, this closes the same gap at the moment
     * it actually matters instead of relying on someone noticing the label first.
     * Returns true if it's fine to proceed (either there was nothing to apply, or the
     * user made a choice), false if the user cancelled and the calling action should stop.
     */
    private boolean confirmUnappliedWizardChangesIfAny() {
        String preview = fqbnPreviewField.getText().trim();
        String applied = fqbnField.getText().trim();
        if (preview.isEmpty() || preview.equals(applied)) {
            return true; // nothing pending - normal case, don't interrupt
        }
        String summary;
        if (!baseFqbnOf(preview).equals(baseFqbnOf(applied))) {
            summary = "Board changed from \"" + friendlyBoardNameFor(applied) + "\" to \"" + friendlyBoardNameFor(preview) + "\".";
        } else {
            summary = "Board options changed for \"" + friendlyBoardNameFor(preview) + "\" (same board, different settings).";
        }
        Object[] options = {"Apply and Continue", "Use Current Board Settings", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this,
                "The Board Wizard has a selection that was never applied to the Project tab.\n\n"
                        + summary + "\n\n"
                        + "Apply the wizard's selection now, or continue with the currently applied board?",
                "Unapplied Board Wizard Changes", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);
        if (choice == 0) {
            applyWizardFqbnToProject();
            return true;
        }
        if (choice == 1) {
            return true; // proceed with whatever's currently on the Project tab, wizard preview left as-is
        }
        return false; // Cancel, or dialog closed without a choice
    }

    /** Yes/No confirmation for a force-action button - explains what's about to happen before it runs. Returns true only if the user chose Yes. */
    private boolean confirmForceAction(String title, String message) {
        int choice = JOptionPane.showConfirmDialog(this, message, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }

    private JPanel buildForceRebuildHeaderIndexButton() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        forceRebuildHeaderIndexButton.setToolTipText("Runs fastbuild with -refresh-deps-index right now, independent of the checkbox above - see the Build Log tab.");
        forceRebuildHeaderIndexButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (confirmForceAction("Force Rebuild Header Index",
                        "This will rebuild the header dependency index by walking the platform and library folders again, "
                                + "instead of using the cached one. It may take a moment on a large installation.\n\n"
                                + "The build will still only recompile if something actually changed - this just refreshes "
                                + "what fastbuild knows about your dependencies first.\n\nContinue?")) {
                    runFastbuild(false, true, false, false);
                }
            }
        });
        row.add(forceRebuildHeaderIndexButton);
        return row;
    }

    private JPanel buildForceCleanRebuildButton() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        forceCleanRebuildButton.setToolTipText("Runs fastbuild with -clean right now, independent of the checkbox above - see the Build Log tab.");
        forceCleanRebuildButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (confirmForceAction("Clean & Rebuild",
                        "This will wipe this sketch's persistent build folder in the cache root and rebuild everything from "
                                + "scratch - every source and library file gets recompiled, not just what changed.\n\n"
                                + "This can take significantly longer than a normal build, depending on the sketch and "
                                + "libraries involved.\n\nContinue?")) {
                    runFastbuild(false, false, true, false);
                }
            }
        });
        row.add(forceCleanRebuildButton);
        return row;
    }

    private JPanel buildForceRecompileButton() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        forceRecompileButton.setToolTipText("Runs fastbuild with -force right now, independent of the checkbox above - see the Build Log tab.");
        forceRecompileButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (confirmForceAction("Force Recompile",
                        "This bypasses fastbuild's skip-if-unchanged check and asks arduino-cli to recompile even if "
                                + "nothing has actually changed since the last build.\n\n"
                                + "Unlike Clean & Rebuild, this keeps the existing build cache in place - it just refuses "
                                + "to trust it for this one run.\n\nContinue?")) {
                    runFastbuild(false, false, false, true);
                }
            }
        });
        row.add(forceRecompileButton);
        return row;
    }

    private JScrollPane buildCacheTab() {
        JPanel panel = newFormPanel();
        int row = 0;

        JPanel persisted = titled("Persisted in config file");
        int pr = 0;
        pr = addFullWidth(persisted, pr, hashLibraryHeadersCheck);
        pr = addFullWidth(persisted, pr, hashToolchainCheck);
        pr = addRow(persisted, pr, "Dependency detection mode:", depsModeCombo);
        platformVersionCaption.setFont(platformVersionCaption.getFont().deriveFont(Font.ITALIC, platformVersionCaption.getFont().getSize2D() - 1f));
        platformVersionCaption.setForeground(java.awt.Color.GRAY);
        pr = addFullWidth(persisted, pr, platformVersionCaption);
        JPanel platformVersionRow = new JPanel(new BorderLayout(6, 0));
        platformVersionCombo.setEditable(false);
        platformVersionRow.add(platformVersionCombo, BorderLayout.CENTER);
        JButton refreshPlatformVersionsButton = new JButton("Refresh");
        refreshPlatformVersionsButton.setToolTipText("Re-scan installed versions - useful if you just installed/updated a platform without restarting the app.");
        refreshPlatformVersionsButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                refreshPlatformVersionOptions();
            }
        });
        platformVersionRow.add(refreshPlatformVersionsButton, BorderLayout.EAST);
        pr = addRow(persisted, pr, "Pin platform version:", platformVersionRow);
        JLabel platformVersionNote = new JLabel("<html>Auto-detected from what's actually installed for the current board's"
                + " platform, highest version selected by default (same as fastbuild's own default when this is left"
                + " blank) - pick a different one from the list if you need to.</html>");
        pr = addFullWidth(persisted, pr, platformVersionNote);
        pr = addFullWidth(persisted, pr, gccInjectMMDCheck);
        pr = addRow(persisted, pr, "Header index max age (hours, 0 = never expires):", depsIndexMaxAgeSpinner);
        row = addFullWidthComponent(panel, row, persisted);

        JPanel oneShot = titled("One-shot overrides for the next run only (not saved to the config file)");
        int or = 0;
        or = addFullWidth(oneShot, or, forceCheck);
        or = addFullWidth(oneShot, or, buildForceRecompileButton());
        or = addFullWidth(oneShot, or, cleanCheck);
        or = addFullWidth(oneShot, or, buildForceCleanRebuildButton());
        or = addFullWidth(oneShot, or, noDepsCheck);
        or = addFullWidth(oneShot, or, noToolchainCheck);
        or = addFullWidth(oneShot, or, refreshDepsIndexCheck);
        or = addFullWidth(oneShot, or, buildForceRebuildHeaderIndexButton());
        or = addFullWidth(oneShot, or, assumeYesStaleDepsCheck);
        or = addFullWidth(oneShot, or, skipStaleDepsRefreshCheck);
        row = addFullWidthComponent(panel, row, oneShot);

        addVerticalGlue(panel, row);
        return wrapScroll(panel);
    }

    private JScrollPane buildOutputTab() {
        JPanel panel = newFormPanel();
        int row = 0;

        row = addFullWidth(panel, row, showStatsCheck);
        row = addFullWidth(panel, row, jsonOutputCheck);
        row = addFullWidth(panel, row, saveLogCheck);
        row = addPathRow(panel, row, "Log directory (blank = <project>/logs):", logDirField, true);

        row = addFullWidth(panel, row, exportCheck);
        exportConflictCombo.setSelectedItem("rename"); // matches BuildSettings.exportConflict's own default - a fresh combo otherwise shows its first array item ("ask") instead
        row = addRow(panel, row, "On export conflict:", exportConflictCombo);
        row = addFullWidth(panel, row, alwaysReplaceOutputCheck);

        JLabel buildPropsLabel = new JLabel("Extra build properties (one key=value per line):");
        row = addFullWidth(panel, row, buildPropsLabel);
        buildPropsArea.setLineWrap(false);
        JScrollPane buildPropsScroll = new JScrollPane(buildPropsArea);
        row = addFullWidthComponent(panel, row, buildPropsScroll);

        row = addFullWidthComponent(panel, row, new JSeparator());
        JPanel saveBar = new JPanel(new BorderLayout());
        projectSettingsStatusLabel.setFont(projectSettingsStatusLabel.getFont().deriveFont(Font.ITALIC));
        saveBar.add(projectSettingsStatusLabel, BorderLayout.WEST);
        saveConfigButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                saveProjectSettingsToDiskAsync();
                saveSketchCacheAfterSuccessfulBuild(readSettingsFromUI());
            }
        });
        JPanel saveButtonWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        saveButtonWrap.add(saveConfigButton);
        saveBar.add(saveButtonWrap, BorderLayout.EAST);
        row = addFullWidthComponent(panel, row, saveBar);

        JLabel saveNote = new JLabel("<html>Saves every tab's settings to <code>" + ProjectSettingsStore.DEFAULT_FILE_NAME
                + "</code> in this app's working directory, and reloads automatically next time it starts.</html>");
        row = addFullWidth(panel, row, saveNote);

        addVerticalGlue(panel, row);
        return wrapScroll(panel);
    }

    private JScrollPane buildUploadTab() {
        JPanel panel = newFormPanel();
        int row = 0;

        JLabel intro = new JLabel("<html>Port selection and upload-after-build, uploading an arbitrary hex/bin file directly,"
                + " plus a serial monitor for talking to the board once it's flashed.</html>");
        row = addFullWidth(panel, row, intro);

        portField.setEditable(true);
        JPanel portRow = new JPanel(new BorderLayout(6, 0));
        portRow.add(portField, BorderLayout.CENTER);
        portRow.add(refreshPortsButton, BorderLayout.EAST);
        row = addRow(panel, row, "Serial port:", portRow);

        JPanel hexFileRow = new JPanel(new BorderLayout(6, 0));
        hexFileRow.add(hexFileField, BorderLayout.CENTER);
        hexFileRow.add(browseHexFileButton, BorderLayout.EAST);
        row = addRow(panel, row, "Hex/bin file to upload:", hexFileRow);
        row = addFullWidth(panel, row, uploadHexFileButton);

        row = addFullWidthComponent(panel, row, new JSeparator());

        row = addFullWidth(panel, row, uploadCheck);
        row = addFullWidth(panel, row, uploadNowButton);
        row = addFullWidth(panel, row, uploadStatusLabel);

        row = addFullWidthComponent(panel, row, new JSeparator());

        JPanel monitorPanel = titled("Serial Monitor");
        int mr = 0;
        mr = addRow(monitorPanel, mr, "Baud rate:", baudRateCombo);
        mr = addRow(monitorPanel, mr, "Line ending on send:", lineEndingCombo);

        JPanel connectButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        connectButtons.add(connectSerialButton);
        connectButtons.add(disconnectSerialButton);
        connectButtons.add(serialClearButton);
        mr = addFullWidthComponent(monitorPanel, mr, connectButtons);

        serialMonitorArea.setEditable(false);
        serialMonitorArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane monitorScroll = new JScrollPane(serialMonitorArea);
        monitorScroll.setPreferredSize(new Dimension(100, 220));
        mr = addFullWidthComponent(monitorPanel, mr, monitorScroll);

        JPanel sendRow = new JPanel(new BorderLayout(6, 0));
        sendRow.add(serialSendField, BorderLayout.CENTER);
        sendRow.add(serialSendButton, BorderLayout.EAST);
        mr = addFullWidthComponent(monitorPanel, mr, sendRow);

        row = addFullWidthComponent(panel, row, monitorPanel);

        baudRateCombo.setSelectedItem("9600");
        disconnectSerialButton.setEnabled(false);
        serialSendField.setEnabled(false);
        serialSendButton.setEnabled(false);

        wireUploadTab();
        onRefreshPortsClicked(); // populate the port list right away

        addVerticalGlue(panel, row);
        return wrapScroll(panel);
    }

    private void wireUploadTab() {
        portField.addPopupMenuListener(new PopupMenuListener() {
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                onRefreshPortsClicked();
            }

            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });

        uploadNowButton.setToolTipText("Builds only if something changed, then uploads to the port above - regardless of the checkbox.");
        uploadNowButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                runFastbuild(true, false, false, false);
            }
        });

        browseHexFileButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onBrowseHexFileClicked();
            }
        });
        uploadHexFileButton.setToolTipText("Uploads exactly this file via arduino-cli directly - no build step, no fastbuild involved.");
        uploadHexFileButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onUploadHexFileClicked();
            }
        });
        installEditPopup(hexFileField);

        installEditPopup((JTextComponent) portField.getEditor().getEditorComponent());
        installEditPopup(serialMonitorArea);
        installEditPopup(serialSendField);

        refreshPortsButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onRefreshPortsClicked();
            }
        });
        connectSerialButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onConnectSerialClicked();
            }
        });
        disconnectSerialButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onDisconnectSerialClicked("Disconnected.");
            }
        });
        serialClearButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                serialMonitorArea.setText("");
            }
        });
        ActionListener sendAction = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onSendSerialClicked();
            }
        };
        serialSendButton.addActionListener(sendAction);
        serialSendField.addActionListener(sendAction); // Enter key sends too
    }

    private void onRefreshPortsClicked() {
        String previousSelection = (String) portField.getSelectedItem();
        try {
            java.util.List<String> names = SerialPorts.listPortDescriptions();
            portField.removeAllItems();
            for (String name : names) {
                portField.addItem(name);
            }
            if (previousSelection != null && !previousSelection.trim().isEmpty()) {
                portField.setSelectedItem(previousSelection); // editable combo - keeps it even if not in the fresh list
            }
            uploadStatusLabel.setText(names.isEmpty() ? "No serial ports found." : "Found " + names.size() + " port(s).");
            logActivity("Refreshed serial ports: " + names.size() + " found.");
        } catch (Throwable t) {
            uploadStatusLabel.setText("Could not list serial ports: " + t.getMessage());
            logActivity("Failed to list serial ports: " + t.getMessage());
        }
    }

    /** The combo shows "COM3 - USB-SERIAL CH340" but BuildSettings.port (and an upload) only ever wants "COM3". */
    private static String systemPortNameOnly(String comboText) {
        if (comboText == null) {
            return "";
        }
        int dash = comboText.indexOf(" - ");
        return (dash >= 0 ? comboText.substring(0, dash) : comboText).trim();
    }

    /**
     * A quick sanity check, not a real architecture database: AVR boards
     * (avrdude, STK500-family protocols) expect a .hex file; ESP8266/ESP32
     * boards (esptool) produce and expect a .bin file. Uploading the wrong
     * pairing doesn't corrupt anything, but it does waste time - avrdude
     * will retry its sync handshake ten times against a board that was never
     * speaking that protocol in the first place before finally giving up.
     * Returns a warning message to show in a Yes/No dialog, or null if
     * nothing looks obviously wrong (including when the FQBN's platform
     * isn't one we recognize - this only flags a small set of clear cases).
     */
    private static String detectArchMismatchWarning(String fqbn, String filePath) {
        if (fqbn == null || fqbn.trim().isEmpty() || filePath == null || filePath.trim().isEmpty()) {
            return null;
        }
        String fqbnLower = fqbn.trim().toLowerCase();
        String fileLower = filePath.trim().toLowerCase();
        boolean looksAvr = fqbnLower.startsWith("arduino:avr:") || fqbnLower.contains(":avr:");
        boolean looksEsp = fqbnLower.startsWith("esp8266:") || fqbnLower.startsWith("esp32:");
        boolean fileIsBin = fileLower.endsWith(".bin");
        boolean fileIsHex = fileLower.endsWith(".hex");

        if (looksAvr && fileIsBin) {
            return "The FQBN (" + fqbn + ") looks like an AVR board, but the selected file is a .bin"
                    + " - AVR boards (via avrdude) normally need a .hex file instead.\n\n"
                    + "This usually means the FQBN is left over from a different sketch/board. Upload anyway?";
        }
        if (looksEsp && fileIsHex) {
            return "The FQBN (" + fqbn + ") looks like an ESP8266/ESP32 board, but the selected file is a .hex"
                    + " - ESP boards normally produce and expect a .bin file instead.\n\n"
                    + "This usually means the FQBN is left over from a different sketch/board. Upload anyway?";
        }
        return null;
    }

    private void onConnectSerialClicked() {
        String portName = systemPortNameOnly((String) portField.getEditor().getItem());
        if (portName.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Pick or type a serial port first.", "Serial Monitor", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int baudRate;
        try {
            baudRate = Integer.parseInt(((String) baudRateCombo.getSelectedItem()).trim());
        } catch (NumberFormatException e) {
            baudRate = 9600;
        }
        if (serialSession != null) {
            onDisconnectSerialClicked(null);
        }
        try {
            final String portNameForLog = portName;
            serialSession = SerialMonitorSession.open(portName, baudRate, new SerialMonitorSession.Listener() {
                public void onDataReceived(String text) {
                    serialMonitorArea.append(text);
                    serialMonitorArea.setCaretPosition(serialMonitorArea.getDocument().getLength());
                }

                public void onClosed(String reason) {
                    onDisconnectSerialClicked(reason);
                }
            });
            connectSerialButton.setEnabled(false);
            disconnectSerialButton.setEnabled(true);
            serialSendField.setEnabled(true);
            serialSendButton.setEnabled(true);
            serialSessionPortName = portName;
            uploadStatusLabel.setText("Connected to " + portNameForLog + " at " + baudRate + " baud.");
            logActivity("Serial Monitor: connected to " + portNameForLog + " at " + baudRate + " baud.");
        } catch (IOException ex) {
            uploadStatusLabel.setText("Could not connect: " + ex.getMessage());
            logActivity("Serial Monitor: failed to connect - " + ex.getMessage());
        }
    }

    private void onDisconnectSerialClicked(String reason) {
        if (serialSession != null) {
            serialSession.close();
            serialSession = null;
        }
        serialSessionPortName = null;
        connectSerialButton.setEnabled(true);
        disconnectSerialButton.setEnabled(false);
        serialSendField.setEnabled(false);
        serialSendButton.setEnabled(false);
        if (reason != null) {
            uploadStatusLabel.setText(reason);
            logActivity("Serial Monitor: " + reason);
        }
    }

    private void onSendSerialClicked() {
        if (serialSession == null) {
            return;
        }
        String text = serialSendField.getText();
        String ending = (String) lineEndingCombo.getSelectedItem();
        if ("Newline (\\n)".equals(ending)) {
            text += "\n";
        } else if ("Carriage return (\\r)".equals(ending)) {
            text += "\r";
        } else if ("Both (\\r\\n)".equals(ending)) {
            text += "\r\n";
        }
        try {
            serialSession.send(text);
            serialSendField.setText("");
        } catch (IOException ex) {
            uploadStatusLabel.setText("Send failed: " + ex.getMessage());
            logActivity("Serial Monitor: send failed - " + ex.getMessage());
        }
    }

    /**
     * Uploads exactly the chosen file via `arduino-cli upload --input-file`
     * directly - no fastbuild involved, no build step, no cache. For
     * whatever's currently in hexFileField, which may not be anything
     * fastbuild itself produced this session.
     */
    private void onUploadHexFileClicked() {
        if (currentBuildWorker != null) {
            return; // already running - see the same guard in runFastbuild for why this matters
        }
        String arduinoCli = arduinoCliField.getText().trim();
        if (arduinoCli.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Set the Arduino CLI executable in App Settings first.", "Upload Hex File", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final String fqbn = fqbnField.getText().trim();
        if (fqbn.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Set the FQBN in the Project tab first.", "Upload Hex File", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Object rawPort = portField.getEditor().getItem();
        final String port = systemPortNameOnly(rawPort == null ? "" : rawPort.toString());
        if (port.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Set a serial port in the Upload tab first.", "Upload Hex File", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final String hexFile = hexFileField.getText().trim();
        if (hexFile.isEmpty() || !new File(hexFile).isFile()) {
            JOptionPane.showMessageDialog(this,
                    "Pick a valid hex/bin file first.", "Upload Hex File", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String mismatchWarning = detectArchMismatchWarning(fqbn, hexFile);
        if (mismatchWarning != null) {
            int choice = JOptionPane.showConfirmDialog(this, mismatchWarning,
                    "Possible Board Mismatch", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                logActivity("Upload hex file cancelled - board/file mismatch warning declined.");
                return;
            }
        }
        final String configFile = configFileField.getText().trim();

        // Same same-port-conflict guard as a normal build/upload.
        if (serialSession != null && port.equalsIgnoreCase(String.valueOf(serialSessionPortName))) {
            onDisconnectSerialClicked("Disconnected automatically to free " + port + " for uploading.");
        }

        final java.util.List<String> command = new java.util.ArrayList<String>();
        command.add(arduinoCli);
        if (!configFile.isEmpty()) {
            command.add("--config-file");
            command.add(configFile);
        }
        command.add("upload");
        command.add("--fqbn");
        command.add(fqbn);
        command.add("--port");
        command.add(port);
        command.add("--input-file");
        command.add(hexFile);

        clearLogDisplay();
        appendLogLine("Running: " + String.join(" ", command));
        appendLogLine("");
        tabs.setSelectedIndex(logTabIndex);
        setBuildRunning(true);
        currentOperationVerb = "Upload";
        logActivity("Upload hex file started: " + hexFile);

        currentBuildPid = null;
        currentBuildCancelled = false;
        beginStatusBarTiming();
        SwingWorker<Integer, String> worker = new SwingWorker<Integer, String>() {
            protected Integer doInBackground() throws Exception {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                currentBuildProcess = process;

                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        publish(line);
                    }
                } finally {
                    reader.close();
                }
                return process.waitFor();
            }

            protected void process(java.util.List<String> chunks) {
                for (String line : chunks) {
                    appendLogLine(line);
                }
            }

            protected void done() {
                setBuildRunning(false);
                currentBuildProcess = null;
                currentBuildWorker = null;
                boolean wasCancelled = currentBuildCancelled;
                try {
                    int exitCode = get();
                    appendLogLine("");
                    if (wasCancelled) {
                        appendLogLine("--- Upload cancelled (exit code " + exitCode + ") ---");
                        logStatusLabel.setText("Cancelled");
                        logActivity("Upload hex file cancelled: " + hexFile);
                    } else if (exitCode == 0) {
                        appendLogLine("--- Upload finished (exit code 0) ---");
                        logStatusLabel.setText("Succeeded");
                        logActivity("Upload hex file succeeded: " + hexFile);
                    } else {
                        appendLogLine("--- Upload finished (exit code " + exitCode + ") ---");
                        logStatusLabel.setText("Failed (exit code " + exitCode + ")");
                        logActivity("Upload hex file failed (exit code " + exitCode + "): " + hexFile);
                    }
                } catch (Exception e) {
                    if (wasCancelled) {
                        appendLogLine("--- Upload cancelled ---");
                        logStatusLabel.setText("Cancelled");
                        logActivity("Upload hex file cancelled: " + hexFile);
                    } else {
                        appendLogLine("--- Upload error: " + rootErrorMessage(e) + " ---");
                        logStatusLabel.setText("Error");
                        logActivity("Upload hex file error: " + rootErrorMessage(e));
                    }
                }
                finishStatusBarTiming(false); // unconditional, and after the banner above - see the matching note in runFastbuild's done()
            }
        };
        currentBuildWorker = worker;
        worker.execute();
    }

    private EditorTabPanel buildEditorTab() {
        editorTabPanel = new EditorTabPanel(new EditorTabPanel.StatusListener() {
            public void onStatus(String message) {
                logActivity("Explorer: " + message);
            }
        });
        editorTabPanel.refreshForSketch(sketchField.getText().trim());
        return editorTabPanel;
    }

    private HexViewerPanel buildHexViewerTab() {
        hexViewerPanel = new HexViewerPanel();
        return hexViewerPanel;
    }

    private JScrollPane buildWizardTab() {
        JPanel panel = newFormPanel();
        int row = 0;

        JLabel intro = new JLabel("<html>Builds a full FQBN: platform &rarr; board &rarr; menu options."
                + " Doesn't need or use a config file.</html>");
        row = addFullWidth(panel, row, intro);

        JPanel appSettingsSummary = titled("From App Settings");
        int ar = 0;
        ar = addFullWidth(appSettingsSummary, ar, labelRow("Arduino CLI executable:", wizardArduinoCliMirror));
        ar = addFullWidth(appSettingsSummary, ar, labelRow("arduino-cli.yaml:", wizardConfigFileMirror));
        ar = addFullWidth(appSettingsSummary, ar, labelRow("Wizard cache dir:", wizardCacheRootMirror));
        JButton editButton = new JButton("Edit in App Settings\u2026");
        editButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                showOptionalTab(appSettingsTabContent, "App Settings");
            }
        });
        ar = addFullWidth(appSettingsSummary, ar, editButton);
        row = addFullWidthComponent(panel, row, appSettingsSummary);

        row = addFullWidth(panel, row, refreshWizardCacheCheck);
        JLabel refreshWizardCacheNote = new JLabel("<html>\u26a0 While checked, this makes every ordinary <b>Load Platforms</b> click"
                + " behave like <b>Force Refresh Wizard Cache & Reload</b> below - bypassing the shared cache every time, not just"
                + " once (subject to the \"Carry over\" checkbox below too). Easy to leave checked by accident; normally left off.</html>");
        row = addFullWidth(panel, row, refreshWizardCacheNote);
        row = addRow(panel, row, "Prefetch mode (when a fresh fetch is needed):", wizardPrefetchCombo);
        row = addRow(panel, row, "Prefetch workers:", wizardPrefetchWorkersSpinner);

        row = addFullWidthComponent(panel, row, new JSeparator());

        JPanel refreshOptionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        carryOverBoardOptionsCheck.setSelected(true);
        carryOverBoardOptionsCheck.setToolTipText("Checked: boards that still exist after a refresh keep their already-cached "
                + "menu options, only new/changed boards need re-fetching. Unchecked: every refresh starts completely empty, "
                + "same as before.");
        refreshOptionsRow.add(carryOverBoardOptionsCheck);
        refreshOptionsRow.add(forceRefreshWizardCacheButton);
        row = addFullWidthComponent(panel, row, refreshOptionsRow);

        JPanel loadBar = new JPanel(new BorderLayout());
        JPanel loadButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        loadButtons.add(loadPlatformsButton);
        loadButtons.add(loadSketchCacheButton);
        loadBar.add(loadButtons, BorderLayout.WEST);
        wizardStatusLabel.setFont(wizardStatusLabel.getFont().deriveFont(Font.ITALIC));
        loadBar.add(wizardStatusLabel, BorderLayout.CENTER);

        JPanel prefetchControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        prefetchStatusLabel.setFont(prefetchStatusLabel.getFont().deriveFont(Font.ITALIC));
        prefetchControls.add(prefetchStatusLabel);
        cancelPrefetchButton.setVisible(false);
        cancelPrefetchButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onCancelPrefetchClicked();
            }
        });
        prefetchControls.add(cancelPrefetchButton);
        loadBar.add(prefetchControls, BorderLayout.EAST);
        row = addFullWidthComponent(panel, row, loadBar);

        platformCombo.setEnabled(false);
        row = addRow(panel, row, "Platform:", platformCombo);

        boardFilterField.setEnabled(false);
        row = addRow(panel, row, "Filter boards:", boardFilterField);

        boardCombo.setEnabled(false);
        row = addRow(panel, row, "Board:", boardCombo);

        JPanel optionsWrapper = titled("Board options");
        optionsWrapper.add(boardOptionsPanel, fullWidthGbc());
        row = addFullWidthComponent(panel, row, optionsWrapper);

        fqbnPreviewField.setEditable(false);
        row = addRow(panel, row, "Assembled FQBN:", fqbnPreviewField);

        applyFqbnButton.setEnabled(false);
        row = addFullWidth(panel, row, applyFqbnButton);
        wizardNotAppliedWarningLabel.setForeground(java.awt.Color.RED.darker());
        wizardNotAppliedWarningLabel.setFont(wizardNotAppliedWarningLabel.getFont().deriveFont(Font.BOLD));
        row = addFullWidth(panel, row, wizardNotAppliedWarningLabel);

        wireWizardTab();

        addVerticalGlue(panel, row);
        return wrapScroll(panel);
    }

    private static GridBagConstraints fullWidthGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        return gbc;
    }

    private void wireWizardTab() {
        DocumentListener appliedWarningListener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                refreshWizardAppliedWarning();
            }

            public void removeUpdate(DocumentEvent e) {
                refreshWizardAppliedWarning();
            }

            public void changedUpdate(DocumentEvent e) {
                refreshWizardAppliedWarning();
            }
        };
        fqbnPreviewField.getDocument().addDocumentListener(appliedWarningListener);
        fqbnField.getDocument().addDocumentListener(appliedWarningListener);

        loadPlatformsButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onLoadPlatformsClicked(false);
            }
        });
        forceRefreshWizardCacheButton.setToolTipText("Re-fetches platforms, boards, and board options from arduino-cli right now, independent of the checkbox above.");
        forceRefreshWizardCacheButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (confirmForceAction("Force Refresh Wizard Cache",
                        "This re-fetches the installed platforms, boards, and every board's menu options from "
                                + "arduino-cli instead of using the shared cache file.\n\n"
                                + (carryOverBoardOptionsCheck.isSelected()
                                        ? "\"Carry over previously cached board options\" is checked, so boards that "
                                                + "still exist afterward keep their already-cached options - only "
                                                + "new/changed boards actually need fetching.\n\n"
                                        : "\u26a0 \"Carry over previously cached board options\" is unchecked, so this "
                                                + "discards ALL previously prefetched board options, even for boards "
                                                + "that haven't changed - you'd need to prefetch again afterward to "
                                                + "get them back.\n\n")
                                + "Worth doing after installing/updating a board core so its options show up "
                                + "correctly.\n\nContinue?")) {
                    onLoadPlatformsClicked(true);
                }
            }
        });
        loadSketchCacheButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onLoadSketchCacheClicked();
            }
        });
        platformCombo.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (!suppressPlatformComboEvents) {
                    onPlatformSelected();
                }
            }
        });
        boardCombo.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (!suppressBoardComboEvents) {
                    onBoardSelected();
                }
            }
        });
        boardFilterField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                populateBoardCombo(boardFilterField.getText());
            }

            public void removeUpdate(DocumentEvent e) {
                populateBoardCombo(boardFilterField.getText());
            }

            public void changedUpdate(DocumentEvent e) {
                populateBoardCombo(boardFilterField.getText());
            }
        });
        applyFqbnButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                applyWizardFqbnToProject();
            }
        });
    }

    /** Applies the wizard's current FQBN preview to the Project tab - the same action the "Apply to Project" button performs, extracted so the unapplied-changes confirmation (validate/build) can trigger it too. */
    private void applyWizardFqbnToProject() {
        String fqbn = fqbnPreviewField.getText().trim();
        if (fqbn.isEmpty()) {
            return;
        }
        boolean boardActuallyChanged = !fqbn.equals(fqbnField.getText().trim());
        fqbnField.setText(fqbn);
        tabs.setSelectedIndex(projectTabIndex);
        logActivity("Applied FQBN to Project: " + fqbn);
        if (boardActuallyChanged) {
            // Flash/RAM figures are specific to a sketch+board combination - showing
            // the previous board's numbers right after switching to a different one
            // would be misleading (e.g. "over budget" for a board that actually fits).
            lastFlashUsageText = null;
            lastRamUsageText = null;
            updateStatusBarFlashRamLabel();
        }
        // Persist right away - previously this only got saved after the *next* successful
        // build, so applying a different board and closing the app before rebuilding
        // silently reverted to whatever board the last successful build actually used.
        saveSketchCacheAfterSuccessfulBuild(readSettingsFromUI());
    }

    private void onLoadPlatformsClicked(boolean forceRefreshOverride) {
        String arduinoCli = arduinoCliField.getText().trim();
        if (arduinoCli.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Set the Arduino CLI executable in App Settings first.", "Board Wizard", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final String arduinoCliPath = arduinoCli;
        final String configFile = configFileField.getText().trim();
        final String cacheDir = wizardCacheDirField.getText().trim();
        final boolean forceRefresh = forceRefreshOverride || refreshWizardCacheCheck.isSelected();
        final boolean carryOverOptions = carryOverBoardOptionsCheck.isSelected();
        final WizardCacheData previousCache = wizardCache;

        loadPlatformsButton.setEnabled(false);
        forceRefreshWizardCacheButton.setEnabled(false);
        platformCombo.setEnabled(false);
        platformCombo.removeAllItems();
        boardCombo.setEnabled(false);
        boardCombo.removeAllItems();
        boardFilterField.setEnabled(false);
        boardFilterField.setText("");
        clearBoardOptions();
        prefetchStatusLabel.setText(" ");
        wizardStatusLabel.setText("Loading platforms and boards\u2026");
        logActivity("Board Wizard: loading platforms and boards\u2026");

        SwingWorker<WizardDataService.ResolveResult, Void> worker = new SwingWorker<WizardDataService.ResolveResult, Void>() {
            protected WizardDataService.ResolveResult doInBackground() throws Exception {
                return WizardDataService.resolvePlatformsAndBoards(arduinoCliPath, configFile, cacheDir, forceRefresh);
            }

            protected void done() {
                loadPlatformsButton.setEnabled(true);
                forceRefreshWizardCacheButton.setEnabled(true);
                try {
                    WizardDataService.ResolveResult result = get();
                    wizardCache = result.data;

                    int carriedOver = 0;
                    if (!result.fromCache && carryOverOptions && previousCache != null && !previousCache.boardOptions.isEmpty()) {
                        java.util.Set<String> stillInstalledFqbns = new java.util.HashSet<String>();
                        for (BoardEntry b : wizardCache.boards) {
                            stillInstalledFqbns.add(b.fqbn);
                        }
                        for (java.util.Map.Entry<String, java.util.List<BoardConfigOption>> entry : previousCache.boardOptions.entrySet()) {
                            if (stillInstalledFqbns.contains(entry.getKey())) {
                                wizardCache.boardOptions.put(entry.getKey(), entry.getValue());
                                carriedOver++;
                            }
                        }
                        if (carriedOver > 0) {
                            wizardCache.complete = wizardCache.boardOptions.size() >= wizardCache.boards.size();
                            if (wizardCache.signature != null && !wizardCache.signature.isEmpty()) {
                                WizardDataService.saveCache(WizardDataService.cacheFile(cacheDir), wizardCache);
                            }
                            logActivity("Board Wizard: carried over cached options for " + carriedOver + " board(s) still installed after refresh.");
                        }
                    }
                    java.util.List<InstalledPlatform> platforms = new java.util.ArrayList<InstalledPlatform>(wizardCache.platforms);
                    java.util.Collections.sort(platforms, new java.util.Comparator<InstalledPlatform>() {
                        public int compare(InstalledPlatform a, InstalledPlatform b) {
                            return a.name.compareToIgnoreCase(b.name);
                        }
                    });
                    suppressPlatformComboEvents = true;
                    for (InstalledPlatform p : platforms) {
                        platformCombo.addItem(p);
                    }
                    platformCombo.setEnabled(!platforms.isEmpty());
                    if (pendingRestorePlatformId != null) {
                        InstalledPlatform match = findPlatformById(platforms, pendingRestorePlatformId);
                        if (match != null) {
                            platformCombo.setSelectedItem(match);
                        }
                        pendingRestorePlatformId = null;
                    }
                    suppressPlatformComboEvents = false;

                    if (platforms.isEmpty()) {
                        wizardStatusLabel.setText("No platforms installed - install one first, e.g. arduino-cli core install esp8266:esp8266.");
                        logActivity("Board Wizard: no platforms installed.");
                    } else {
                        wizardStatusLabel.setText((result.fromCache ? "Loaded from cache: " : "Loaded live: ")
                                + platforms.size() + " platform(s), " + wizardCache.boards.size() + " board(s) total.");
                        logActivity("Board Wizard: loaded " + platforms.size() + " platform(s), " + wizardCache.boards.size()
                                + " board(s) " + (result.fromCache ? "from cache" : "live") + ".");
                        onPlatformSelected(); // single, guaranteed call - the listener was suppressed above
                        if (!result.fromCache) {
                            maybeAutoPrefetchBoardOptions();
                        }
                    }
                } catch (Exception e) {
                    wizardStatusLabel.setText("Error loading platforms: " + rootErrorMessage(e));
                    logActivity("Board Wizard: error loading platforms - " + rootErrorMessage(e));
                }
            }
        };
        worker.execute();
    }

    private static InstalledPlatform findPlatformById(java.util.List<InstalledPlatform> platforms, String id) {
        for (InstalledPlatform p : platforms) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Board Wizard prefetch - mirrors fastbuild.go's own -configure-board
    // -wizard-prefetch behavior (ask/full/off), but implemented natively
    // here rather than shelling out to -configure-board, since that flag
    // is an interactive console wizard (it also prompts for platform/
    // board/option choices after prefetching, which we have no clean way
    // to drive or decline via a subprocess). Concurrency uses the same
    // stateless fetchBoardConfigOptions call the lazy per-board path
    // already relies on, just run across a worker pool - each fetch is
    // independent, so no shared-state races. Writes to the exact same
    // cache file/format either tool uses, so a prefetch run from here
    // benefits fastbuild's own CLI too, and vice versa.
    // ------------------------------------------------------------------

    private void maybeAutoPrefetchBoardOptions() {
        if (wizardCache == null) {
            return;
        }
        String mode = (String) wizardPrefetchCombo.getSelectedItem();
        if ("off".equals(mode)) {
            return;
        }
        final java.util.List<BoardEntry> remaining = new java.util.ArrayList<BoardEntry>();
        for (BoardEntry b : wizardCache.boards) {
            if (!wizardCache.boardOptions.containsKey(b.fqbn)) {
                remaining.add(b);
            }
        }
        if (remaining.isEmpty()) {
            return;
        }
        if ("ask".equals(mode)) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Prefetch menu options for " + remaining.size() + " board(s) now? This can take a while and "
                            + "briefly spawns several arduino-cli processes at once - after it's done, every board "
                            + "is instant on every future run.\n\nYou can change this (or turn it off) in the "
                            + "\"Prefetch mode\" setting above.",
                    "Board Wizard", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                logActivity("Board Wizard: prefetch declined for " + remaining.size() + " board(s).");
                return;
            }
        }
        startPrefetch(remaining);
    }

    /** Container.setEnabled() doesn't cascade to child components in Swing by default - this does that explicitly, for panels (like boardOptionsPanel) whose actual interactive children are added/removed dynamically. */
    private static void setContainerEnabledRecursive(java.awt.Container container, boolean enabled) {
        container.setEnabled(enabled);
        for (java.awt.Component child : container.getComponents()) {
            child.setEnabled(enabled);
            if (child instanceof java.awt.Container) {
                setContainerEnabledRecursive((java.awt.Container) child, enabled);
            }
        }
    }

    private void startPrefetch(final java.util.List<BoardEntry> remaining) {
        final String arduinoCliPath = arduinoCliField.getText().trim();
        final String configFile = configFileField.getText().trim();
        final int workers = Math.max(1, (Integer) wizardPrefetchWorkersSpinner.getValue());
        final String cacheDir = wizardCacheDirField.getText().trim();
        final WizardCacheData cacheSnapshot = wizardCache;

        // Release focus first - disabling a text component that currently has
        // keyboard focus can trigger a native input-method exception on Windows
        // (WInputMethod querying a screen location for a component that's about
        // to stop being interactive).
        KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();

        loadPlatformsButton.setEnabled(false);
        forceRefreshWizardCacheButton.setEnabled(false);
        platformCombo.setEnabled(false);
        boardFilterField.setEnabled(false);
        boardCombo.setEnabled(false);
        setContainerEnabledRecursive(boardOptionsPanel, false);
        applyFqbnButton.setEnabled(false);
        loadSketchCacheButton.setEnabled(false);
        wizardPrefetchWorkersSpinner.setEnabled(false);
        refreshWizardCacheCheck.setEnabled(false);
        carryOverBoardOptionsCheck.setEnabled(false);
        prefetchCancelled = false;
        currentPrefetchProcessRefs.clear();
        cancelPrefetchButton.setEnabled(true);
        cancelPrefetchButton.setVisible(true);
        prefetchStatusLabel.setText("Prefetching board options: 0 / " + remaining.size() + "\u2026");
        logActivity("Board Wizard: prefetching " + remaining.size() + " board(s) with " + workers + " worker(s)\u2026");

        SwingWorker<java.util.Map<String, java.util.List<BoardConfigOption>>, Integer> worker =
                new SwingWorker<java.util.Map<String, java.util.List<BoardConfigOption>>, Integer>() {
            protected java.util.Map<String, java.util.List<BoardConfigOption>> doInBackground() throws Exception {
                final java.util.Map<String, java.util.List<BoardConfigOption>> results =
                        new java.util.concurrent.ConcurrentHashMap<String, java.util.List<BoardConfigOption>>();
                final java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(workers);
                currentPrefetchPool = pool;
                java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                currentPrefetchFutures = futures;
                for (final BoardEntry board : remaining) {
                    futures.add(pool.submit(new Runnable() {
                        public void run() {
                            java.util.concurrent.atomic.AtomicReference<Process> processHolder =
                                    new java.util.concurrent.atomic.AtomicReference<Process>();
                            currentPrefetchProcessRefs.add(processHolder);
                            try {
                                // Check right before starting, not just once at the top of the
                                // pool - a board queued behind others might get its turn well
                                // after Cancel was clicked.
                                if (prefetchCancelled) {
                                    return;
                                }
                                java.util.List<BoardConfigOption> options =
                                        WizardDataService.fetchBoardConfigOptions(arduinoCliPath, configFile, board.fqbn, processHolder);
                                results.put(board.fqbn, options);
                            } catch (Exception ex) {
                                // Best-effort: one board failing to fetch (e.g. a transient
                                // arduino-cli hiccup, or a cancel forcibly killing an in-flight
                                // call) shouldn't abort the rest of the prefetch - it's simply
                                // not cached yet and falls back to lazy fetch later.
                            } finally {
                                currentPrefetchProcessRefs.remove(processHolder);
                            }
                            publish(completed.incrementAndGet());
                        }
                    }));
                }
                pool.shutdown();
                for (java.util.concurrent.Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception ignored) {
                        // already handled inside the Runnable above
                    }
                }
                return results;
            }

            protected void process(java.util.List<Integer> chunks) {
                int latest = chunks.get(chunks.size() - 1);
                String suffix = prefetchCancelled ? " (cancelling\u2026)" : "\u2026";
                prefetchStatusLabel.setText("Prefetching board options: " + latest + " / " + remaining.size() + suffix);
            }

            protected void done() {
                loadPlatformsButton.setEnabled(true);
                forceRefreshWizardCacheButton.setEnabled(true);
                platformCombo.setEnabled(true);
                boardFilterField.setEnabled(true);
                boardCombo.setEnabled(true);
                setContainerEnabledRecursive(boardOptionsPanel, true);
                applyFqbnButton.setEnabled(true);
                loadSketchCacheButton.setEnabled(true);
                wizardPrefetchWorkersSpinner.setEnabled(true);
                refreshWizardCacheCheck.setEnabled(true);
                carryOverBoardOptionsCheck.setEnabled(true);
                cancelPrefetchButton.setVisible(false);
                if (cancelSweepTimer != null) {
                    cancelSweepTimer.stop();
                    cancelSweepTimer = null;
                }
                currentPrefetchPool = null;
                currentPrefetchFutures = null;
                try {
                    java.util.Map<String, java.util.List<BoardConfigOption>> results = get();
                    // Partial results are still worth keeping - whatever got fetched before a
                    // cancel/interruption is real, valid data, not something to discard just
                    // because the run didn't reach every board.
                    cacheSnapshot.boardOptions.putAll(results);
                    cacheSnapshot.complete = cacheSnapshot.boardOptions.size() >= cacheSnapshot.boards.size();
                    if (cacheSnapshot.signature != null && !cacheSnapshot.signature.isEmpty()) {
                        WizardDataService.saveCache(WizardDataService.cacheFile(cacheDir), cacheSnapshot);
                    }
                    final String finalMessage;
                    if (prefetchCancelled) {
                        finalMessage = "Prefetch cancelled - " + results.size() + " of " + remaining.size() + " board(s) cached before stopping.";
                        logActivity("Board Wizard: prefetch cancelled - " + results.size() + " of " + remaining.size() + " board(s) cached.");
                    } else {
                        finalMessage = "Prefetched " + results.size() + " of " + remaining.size() + " board(s).";
                        logActivity("Board Wizard: prefetch finished - " + results.size() + " of " + remaining.size() + " board(s) cached.");
                    }
                    // Scheduled via invokeLater rather than set directly here - guarantees this
                    // runs after anything already queued on the EDT, including a straggler
                    // process() call from a worker that published its last progress update right
                    // as it got killed/cancelled, which would otherwise be able to run after this
                    // point and overwrite the final message back to "Prefetching...".
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            prefetchStatusLabel.setText(finalMessage);
                        }
                    });
                } catch (Exception e) {
                    final String errorMessage = "Prefetch error: " + rootErrorMessage(e);
                    logActivity("Board Wizard: prefetch error - " + rootErrorMessage(e));
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            prefetchStatusLabel.setText(errorMessage);
                        }
                    });
                }
            }
        };
        worker.execute();
    }

    private void onCancelPrefetchClicked() {
        prefetchCancelled = true;
        cancelPrefetchButton.setEnabled(false);
        prefetchStatusLabel.setText("Cancelling prefetch\u2026");
        logActivity("Board Wizard: prefetch cancel requested.");
        java.util.concurrent.ExecutorService pool = currentPrefetchPool;
        if (pool != null) {
            pool.shutdownNow(); // stops anything not yet started
        }
        // Explicitly cancel every future too, not just relying on shutdownNow() -
        // with hundreds of boards and a handful of workers, most tasks are still
        // queued (never started) at the moment of cancel, and doInBackground's
        // f.get() loop would otherwise hang forever waiting on one of those if its
        // Future was never actually marked done/cancelled.
        java.util.List<java.util.concurrent.Future<?>> futuresSnapshot = currentPrefetchFutures;
        if (futuresSnapshot != null) {
            for (java.util.concurrent.Future<?> f : futuresSnapshot) {
                f.cancel(true);
            }
        }
        killAllTrackedPrefetchProcesses();

        // A single snapshot isn't enough: a task could be past the "am I cancelled?"
        // check and about to call ProcessBuilder.start() at the exact moment above -
        // this loop would see a null Process for it (hasn't launched yet), and that
        // task would then launch a brand-new, completely untracked process that never
        // gets killed. Keep sweeping until the prefetch worker itself actually
        // finishes (done() clears currentPrefetchPool), catching anything that
        // launches after the initial snapshot.
        if (cancelSweepTimer != null) {
            cancelSweepTimer.stop();
        }
        cancelSweepTimer = new javax.swing.Timer(150, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (currentPrefetchPool == null) {
                    cancelSweepTimer.stop();
                    return;
                }
                killAllTrackedPrefetchProcesses();
            }
        });
        cancelSweepTimer.start();
    }

    private void killAllTrackedPrefetchProcesses() {
        int killed = 0;
        for (java.util.concurrent.atomic.AtomicReference<Process> ref : currentPrefetchProcessRefs) {
            Process p = ref.get();
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
                killed++;
            }
        }
        if (killed > 0) {
            logActivity("Board Wizard: force-stopped " + killed + " in-flight arduino-cli process(es).");
        }
    }

    private void onPlatformSelected() {
        InstalledPlatform selected = (InstalledPlatform) platformCombo.getSelectedItem();
        boardFilterField.setText("");
        boardCombo.removeAllItems();
        clearBoardOptions();
        if (selected == null || wizardCache == null) {
            boardFilterField.setEnabled(false);
            boardCombo.setEnabled(false);
            return;
        }
        logActivity("Board Wizard: platform selected - " + selected.id);
        wizardPlatformBoards = new java.util.ArrayList<BoardEntry>(
                WizardDataService.filterBoardsByPlatform(wizardCache.boards, selected.id));
        java.util.Collections.sort(wizardPlatformBoards, new java.util.Comparator<BoardEntry>() {
            public int compare(BoardEntry a, BoardEntry b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        boardFilterField.setEnabled(!wizardPlatformBoards.isEmpty());
        boardCombo.setEnabled(!wizardPlatformBoards.isEmpty());
        String preferredFqbn = pendingRestoreBoardFqbn;
        pendingRestoreBoardFqbn = null;
        populateBoardCombo("", preferredFqbn);
        if (wizardPlatformBoards.isEmpty()) {
            wizardStatusLabel.setText("No boards found for platform " + selected.id);
        }
    }

    private void populateBoardCombo(String filterText) {
        populateBoardCombo(filterText, null);
    }

    private void populateBoardCombo(String filterText, String preferredFqbn) {
        suppressBoardComboEvents = true;
        boardCombo.removeAllItems();
        String needle = filterText.trim().toLowerCase();
        BoardEntry preferredMatch = null;
        for (BoardEntry b : wizardPlatformBoards) {
            if (needle.isEmpty() || b.name.toLowerCase().contains(needle)) {
                boardCombo.addItem(b);
                if (preferredFqbn != null && preferredFqbn.equals(b.fqbn)) {
                    preferredMatch = b;
                }
            }
        }
        if (boardCombo.getItemCount() > 0) {
            boardCombo.setSelectedItem(preferredMatch != null ? preferredMatch : boardCombo.getItemAt(0));
        }
        suppressBoardComboEvents = false;

        if (boardCombo.getItemCount() > 0) {
            onBoardSelected(); // single, guaranteed call - the listener was suppressed above
        } else {
            clearBoardOptions();
        }
    }

    private void onBoardSelected() {
        final BoardEntry selected = (BoardEntry) boardCombo.getSelectedItem();
        refreshSelectedBoardLabel();
        refreshStatusBarSketchAndBoard();
        clearBoardOptions();
        if (selected == null || wizardCache == null) {
            return;
        }
        wizardCurrentBaseFqbn = selected.fqbn;
        fqbnPreviewField.setText(selected.fqbn);
        applyFqbnButton.setEnabled(true);
        logActivity("Board Wizard: board selected - " + selected.fqbn);

        final String arduinoCli = arduinoCliField.getText().trim();
        final String configFile = configFileField.getText().trim();
        final String cacheDir = wizardCacheDirField.getText().trim();
        final WizardCacheData cacheRef = wizardCache;

        wizardStatusLabel.setText("Loading board options for " + selected.name + "\u2026");
        SwingWorker<java.util.List<BoardConfigOption>, Void> worker = new SwingWorker<java.util.List<BoardConfigOption>, Void>() {
            protected java.util.List<BoardConfigOption> doInBackground() throws Exception {
                return WizardDataService.getOrFetchBoardOptions(arduinoCli, configFile, cacheDir, cacheRef, selected.fqbn);
            }

            protected void done() {
                // Ignore stale results if a different board has since been selected.
                BoardEntry stillSelected = (BoardEntry) boardCombo.getSelectedItem();
                if (stillSelected == null || !stillSelected.fqbn.equals(selected.fqbn)) {
                    return;
                }
                try {
                    java.util.List<BoardConfigOption> options = get();
                    rebuildOptionCombos(options);
                    updateFqbnPreview();
                    wizardStatusLabel.setText(options.isEmpty()
                            ? "No menu options for this board - FQBN is complete as-is."
                            : "Loaded " + options.size() + " menu option(s).");
                } catch (Exception e) {
                    wizardStatusLabel.setText("Could not load menu options, using base FQBN only: " + rootErrorMessage(e));
                }
            }
        };
        worker.execute();
    }

    private void clearBoardOptions() {
        boardOptionsPanel.removeAll();
        boardOptionsPanel.revalidate();
        boardOptionsPanel.repaint();
        wizardOptionCombos = new java.util.ArrayList<JComboBox<BoardConfigValue>>();
        wizardOptionsForCombos = new java.util.ArrayList<BoardConfigOption>();
        fqbnPreviewField.setText(wizardCurrentBaseFqbn);
        applyFqbnButton.setEnabled(!wizardCurrentBaseFqbn.isEmpty());
    }

    private void rebuildOptionCombos(java.util.List<BoardConfigOption> options) {
        boardOptionsPanel.removeAll();
        wizardOptionCombos = new java.util.ArrayList<JComboBox<BoardConfigValue>>();
        wizardOptionsForCombos = new java.util.ArrayList<BoardConfigOption>();
        java.util.Map<String, String> restoreValues = pendingRestoreOptionValues;
        pendingRestoreOptionValues = null;
        int r = 0;
        for (BoardConfigOption opt : options) {
            if (opt.values.isEmpty()) {
                continue;
            }
            JComboBox<BoardConfigValue> combo = new JComboBox<BoardConfigValue>();
            int defaultIndex = 0;
            int preferredIndex = -1;
            String preferredValue = (restoreValues != null) ? restoreValues.get(opt.option) : null;
            for (int i = 0; i < opt.values.size(); i++) {
                BoardConfigValue v = opt.values.get(i);
                combo.addItem(v);
                if (v.selected) {
                    defaultIndex = i;
                }
                if (preferredValue != null && preferredValue.equals(v.value)) {
                    preferredIndex = i;
                }
            }
            combo.setSelectedIndex(preferredIndex >= 0 ? preferredIndex : defaultIndex);
            combo.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    updateFqbnPreview();
                }
            });
            wizardOptionCombos.add(combo);
            wizardOptionsForCombos.add(opt);
            r = addRow(boardOptionsPanel, r, opt.optionLabel + ":", combo);
        }
        boardOptionsPanel.revalidate();
        boardOptionsPanel.repaint();
    }

    private void updateFqbnPreview() {
        java.util.Map<String, String> selections = currentWizardOptionSelections();
        fqbnPreviewField.setText(WizardDataService.assembleFqbn(wizardCurrentBaseFqbn, wizardOptionsForCombos, selections));
    }

    /**
     * The wizard's own combo boxes are just a preview until Apply to
     * Project is actually clicked - selecting a different board here does
     * NOT touch the Project tab's real FQBN by itself. That's caused real
     * confusion more than once (building/uploading the previous board's
     * firmware without realizing the new selection was never applied), so
     * this makes the gap impossible to miss instead of just hoping people
     * remember to click Apply.
     */
    private void refreshWizardAppliedWarning() {
        String preview = fqbnPreviewField.getText().trim();
        String applied = fqbnField.getText().trim();
        if (!preview.isEmpty() && !preview.equals(applied)) {
            wizardNotAppliedWarningLabel.setText("\u26a0 Not applied yet - click \"Apply to Project\" above, or the Project tab still has a different board.");
        } else {
            wizardNotAppliedWarningLabel.setText(" ");
        }
    }

    /** Shows the board's human-readable name (from the wizard's board combo) when it matches what's actually applied, otherwise falls back to the raw FQBN - matches the same source of truth as the status bar (fqbnField), not the wizard's possibly-unapplied preview. */
    private void refreshSelectedBoardLabel() {
        selectedBoardLabel.setText(resolveBoardDisplayName(fqbnField.getText().trim()));
    }

    /** Shows the board's human-readable name (from the wizard's board combo) when it matches what's actually applied, otherwise falls back to the raw FQBN - or "-" if there's no FQBN at all. */
    private String resolveBoardDisplayName(String fqbn) {
        if (fqbn.isEmpty()) {
            return "-";
        }
        String base = baseFqbnOf(fqbn);
        Object selected = boardCombo.getSelectedItem();
        if (selected instanceof BoardEntry && base.equals(((BoardEntry) selected).fqbn)) {
            return ((BoardEntry) selected).name;
        }
        return fqbn;
    }

    /** The vendor:arch:board portion of a full FQBN, dropping any :menuOption=value,... suffix. */
    private static String baseFqbnOf(String fqbn) {
        String[] parts = fqbn.split(":", 4);
        if (parts.length < 3) {
            return fqbn;
        }
        return parts[0] + ":" + parts[1] + ":" + parts[2];
    }

    /** Resolves a friendly board name for any FQBN by searching wizardCache.boards directly - unlike resolveBoardDisplayName(), this works regardless of which board the wizard's own combo currently has selected, so it's usable for comparing two arbitrary FQBNs at once (e.g. the applied one vs. a pending wizard selection). Falls back to the base FQBN if no match is found. */
    private String friendlyBoardNameFor(String fqbn) {
        String base = baseFqbnOf(fqbn);
        if (wizardCache != null) {
            for (BoardEntry b : wizardCache.boards) {
                if (base.equals(b.fqbn)) {
                    return b.name;
                }
            }
        }
        return base;
    }

    private static String rootErrorMessage(Throwable t) {
        Throwable cause = t.getCause();
        String message = (cause != null ? cause.getMessage() : t.getMessage());
        return message == null ? t.toString() : message;
    }

    private JPanel buildDaemonTab() {
        JPanel settingsPanel = newFormPanel();
        int row = 0;

        row = addFullWidth(settingsPanel, row, daemonCheck);
        row = addRow(settingsPanel, row, "Daemon address:", daemonAddrField);
        row = addRow(settingsPanel, row, "Stale-deps policy (daemon can never prompt):", daemonStaleDepsPolicyCombo);

        row = addFullWidthComponent(settingsPanel, row, new JSeparator());

        row = addFullWidth(settingsPanel, row, connectCheck);
        row = addRow(settingsPanel, row, "Connect to address:", connectAddrField);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        panel.add(settingsPanel, BorderLayout.NORTH);

        daemonLogArea.setEditable(false);
        daemonLogArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        installEditPopup(daemonLogArea);
        panel.add(new JScrollPane(daemonLogArea), BorderLayout.CENTER);

        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBorder(new EmptyBorder(8, 0, 0, 0));
        bottomBar.add(daemonStatusLabel, BorderLayout.WEST);

        startDaemonButton.setToolTipText("Starts fastbuild -daemon in the background; click again to stop it.");
        startDaemonButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onStartOrStopDaemonClicked();
            }
        });
        connectAndBuildButton.setToolTipText("Sends one build request to a running daemon at the address above.");
        connectAndBuildButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onConnectAndBuildClicked();
            }
        });
        JButton clearDaemonLogButton = new JButton("Clear Log");
        clearDaemonLogButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                daemonLogArea.setText("");
            }
        });
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonRow.add(clearDaemonLogButton);
        buttonRow.add(startDaemonButton);
        buttonRow.add(connectAndBuildButton);
        bottomBar.add(buttonRow, BorderLayout.EAST);
        panel.add(bottomBar, BorderLayout.SOUTH);

        return panel;
    }

    private void appendDaemonLogLine(String line) {
        daemonLogArea.append(ANSI_ESCAPE_PATTERN.matcher(line).replaceAll(""));
        daemonLogArea.append("\n");
        daemonLogArea.setCaretPosition(daemonLogArea.getDocument().getLength());
    }

    // ------------------------------------------------------------------
    // Start/Stop Daemon - a long-running background process, fully
    // independent of the main build-runner's state (currentBuildProcess
    // etc.), since a daemon is meant to keep running while you do other
    // things elsewhere in the app, not lock the UI the way a one-shot
    // build does.
    // ------------------------------------------------------------------

    private void onStartOrStopDaemonClicked() {
        if (daemonProcess != null) {
            stopDaemon();
            return;
        }
        String fastbuildExe = fastbuildExeField.getText().trim();
        if (fastbuildExe.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Set the fastbuild executable in App Settings first.", "Daemon", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final String addr = daemonAddrField.getText().trim();
        if (addr.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Set a daemon address first, e.g. 127.0.0.1:9876.", "Daemon", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String policy = "refresh".equals(daemonStaleDepsPolicyCombo.getSelectedItem()) ? "refresh" : "skip";

        final java.util.List<String> command = new java.util.ArrayList<String>();
        command.add(fastbuildExe);
        command.add("-daemon");
        command.add("-daemon-addr");
        command.add(addr);
        command.add("-daemon-stale-deps-policy");
        command.add(policy);

        daemonLogArea.setText("");
        appendDaemonLogLine("Running: " + String.join(" ", command));
        appendDaemonLogLine("");
        daemonStatusLabel.setText("Starting\u2026");
        startDaemonButton.setText("Stop Daemon");
        logActivity("Daemon: starting on " + addr);

        daemonPid = null;
        SwingWorker<Integer, String> worker = new SwingWorker<Integer, String>() {
            protected Integer doInBackground() throws Exception {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                daemonProcess = process;

                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                try {
                    boolean firstLine = true;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (firstLine) {
                            firstLine = false;
                            Integer pid = parsePid(line);
                            if (pid != null) {
                                daemonPid = pid;
                            }
                        }
                        publish(line);
                    }
                } finally {
                    reader.close();
                }
                return process.waitFor();
            }

            protected void process(java.util.List<String> chunks) {
                for (String line : chunks) {
                    appendDaemonLogLine(line);
                }
            }

            protected void done() {
                daemonProcess = null;
                daemonWorker = null;
                daemonPid = null;
                startDaemonButton.setText("Start Daemon");
                try {
                    int exitCode = get();
                    appendDaemonLogLine("");
                    appendDaemonLogLine("--- Daemon stopped (exit code " + exitCode + ") ---");
                    daemonStatusLabel.setText("Stopped");
                    logActivity("Daemon stopped (exit code " + exitCode + ").");
                } catch (Exception e) {
                    appendDaemonLogLine("");
                    appendDaemonLogLine("--- Daemon error: " + rootErrorMessage(e) + " ---");
                    daemonStatusLabel.setText("Error");
                    logActivity("Daemon error: " + rootErrorMessage(e));
                }
            }
        };
        daemonWorker = worker;
        worker.execute();
        daemonStatusLabel.setText("Running on " + addr);
    }

    private void stopDaemon() {
        appendDaemonLogLine("--- Stop requested ---");
        logActivity("Daemon: stop requested.");
        daemonStatusLabel.setText("Stopping\u2026");
        Integer pid = daemonPid;
        if (pid != null) {
            // Same reasoning as the main build Cancel: the daemon spawns arduino-cli as its
            // own child while handling a request, so killing just the daemon's own PID could
            // orphan that child mid-compile - taskkill /F /T takes down the whole tree.
            try {
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid)).start();
                return;
            } catch (IOException ex) {
                appendDaemonLogLine("Could not run taskkill (" + ex.getMessage() + ") - falling back to a direct process kill.");
            }
        }
        Process p = daemonProcess;
        if (p != null) {
            p.destroyForcibly();
        }
    }

    // ------------------------------------------------------------------
    // Connect & Build - a one-shot client request to an already-running
    // daemon. Independent of the main build-runner too, so it doesn't lock
    // the rest of the app while waiting on a (possibly slow) daemon-side
    // compile, and doesn't fight over state with a daemon that might be
    // running locally at the same time.
    // ------------------------------------------------------------------

    private void onConnectAndBuildClicked() {
        if (connectProcess != null) {
            appendDaemonLogLine("--- Cancel requested ---");
            logActivity("Connect & Build: cancel requested.");
            connectProcess.destroyForcibly();
            return;
        }

        final BuildSettings settings = readSettingsFromUI();
        String missing = settings.validateRequired();
        if (missing != null) {
            JOptionPane.showMessageDialog(this, missing, "Connect & Build", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String fastbuildExe = fastbuildExeField.getText().trim();
        if (fastbuildExe.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Set the fastbuild executable in App Settings first.", "Connect & Build", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final String addr = connectAddrField.getText().trim();
        if (addr.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Set a daemon address to connect to first.", "Connect & Build", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String cacheRoot = settings.cacheRoot.trim();
        if (cacheRoot.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Set a cache root in the Project tab first.", "Connect & Build", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // -connect needs a real config FILE on disk (it just sends the path over the
        // wire) - unlike a normal build, which can pipe the config through stdin.
        File cacheDir = new File(cacheRoot);
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            JOptionPane.showMessageDialog(this,
                    "Could not create the cache root folder:\n" + cacheDir.getAbsolutePath(), "Connect & Build", JOptionPane.ERROR_MESSAGE);
            return;
        }
        final File connectConfigFile = new File(cacheDir, "fastbuild-ui-connect.config");

        final java.util.List<String> command = new java.util.ArrayList<String>();
        command.add(fastbuildExe);
        command.add("-connect");
        command.add(addr);
        command.add(connectConfigFile.getAbsolutePath());

        appendDaemonLogLine("Running: " + String.join(" ", command));
        appendDaemonLogLine("");
        final String originalButtonText = "Connect & Build\u2026";
        connectAndBuildButton.setText("Cancel");
        logActivity("Connect & Build: sending build request to " + addr);

        SwingWorker<Integer, String> worker = new SwingWorker<Integer, String>() {
            protected Integer doInBackground() throws Exception {
                // Written here (off the EDT) rather than before the worker starts, since the
                // subprocess launched right after needs this file to already exist on disk -
                // same background thread, so the ordering is naturally still correct.
                ConfigFileCodec.save(settings, connectConfigFile);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                connectProcess = process;

                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        publish(line);
                    }
                } finally {
                    reader.close();
                }
                return process.waitFor();
            }

            protected void process(java.util.List<String> chunks) {
                for (String line : chunks) {
                    appendDaemonLogLine(line);
                }
            }

            protected void done() {
                connectProcess = null;
                connectWorker = null;
                connectAndBuildButton.setText(originalButtonText);
                try {
                    int exitCode = get();
                    appendDaemonLogLine("");
                    if (exitCode == 0) {
                        appendDaemonLogLine("--- Connect & Build finished (exit code 0) ---");
                        logActivity("Connect & Build succeeded: " + settings.sketch);
                        saveSketchCacheAfterSuccessfulBuild(settings);
                    } else {
                        appendDaemonLogLine("--- Connect & Build finished (exit code " + exitCode + ") ---");
                        logActivity("Connect & Build failed (exit code " + exitCode + "): " + settings.sketch);
                    }
                } catch (Exception e) {
                    appendDaemonLogLine("--- Connect & Build error: " + rootErrorMessage(e) + " ---");
                    logActivity("Connect & Build error: " + rootErrorMessage(e));
                }
            }
        };
        connectWorker = worker;
        worker.execute();
    }

    private JPanel buildWatchTab() {
        JPanel settingsPanel = newFormPanel();
        int row = 0;

        row = addFullWidth(settingsPanel, row, watchCheck);
        row = addRow(settingsPanel, row, "Watch interval (e.g. 500ms, 2s):", watchIntervalField);

        JLabel note = new JLabel("<html>Combine with Connect (Daemon tab) to have a daemon do the actual building - "
                + "if \"Send this build to a running daemon\" is checked there, Start Watch below will add -connect "
                + "automatically instead of building locally on every change.</html>");
        row = addFullWidth(settingsPanel, row, note);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        panel.add(settingsPanel, BorderLayout.NORTH);

        watchLogArea.setEditable(false);
        watchLogArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        installEditPopup(watchLogArea);
        panel.add(new JScrollPane(watchLogArea), BorderLayout.CENTER);

        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBorder(new EmptyBorder(8, 0, 0, 0));
        bottomBar.add(watchStatusLabel, BorderLayout.WEST);

        startWatchButton.setToolTipText("Starts fastbuild -watch in the background against the current Project settings; click again to stop it.");
        startWatchButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onStartOrStopWatchClicked();
            }
        });
        JButton clearWatchLogButton = new JButton("Clear Log");
        clearWatchLogButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                watchLogArea.setText("");
            }
        });
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonRow.add(clearWatchLogButton);
        buttonRow.add(startWatchButton);
        bottomBar.add(buttonRow, BorderLayout.EAST);
        panel.add(bottomBar, BorderLayout.SOUTH);

        return panel;
    }

    private void appendWatchLogLine(String line) {
        watchLogArea.append(ANSI_ESCAPE_PATTERN.matcher(line).replaceAll(""));
        watchLogArea.append("\n");
        watchLogArea.setCaretPosition(watchLogArea.getDocument().getLength());
    }

    // ------------------------------------------------------------------
    // Start/Stop Watch - a long-running background process, same
    // reasoning as Daemon: independent of the main build-runner, since
    // watch mode is meant to keep running (rebuilding on every change)
    // while you keep using the rest of the app, not lock the UI the way
    // a one-shot build does. Unlike the daemon, this needs the full
    // Project config piped over stdin, same as a normal build, since
    // each rebuild it triggers needs to know the sketch/fqbn/etc.
    // ------------------------------------------------------------------

    private void onStartOrStopWatchClicked() {
        if (watchProcess != null) {
            stopWatch();
            return;
        }
        String fastbuildExe = fastbuildExeField.getText().trim();
        if (fastbuildExe.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Set the fastbuild executable in App Settings first.", "Watch", JOptionPane.WARNING_MESSAGE);
            return;
        }
        BuildSettings settings = readSettingsFromUI();
        String missing = settings.validateRequired();
        if (missing != null) {
            JOptionPane.showMessageDialog(this, missing, "Watch", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String interval = watchIntervalField.getText().trim();
        if (interval.isEmpty()) {
            interval = "1s";
        }
        final boolean viaConnect = connectCheck.isSelected() && !connectAddrField.getText().trim().isEmpty();
        final String connectAddr = connectAddrField.getText().trim();

        final String configText = ConfigFileCodec.write(settings);
        final java.util.List<String> command = new java.util.ArrayList<String>();
        command.add(fastbuildExe);
        command.add("-watch");
        command.add("-watch-interval");
        command.add(interval);
        if (viaConnect) {
            command.add("-connect");
            command.add(connectAddr);
        }
        command.add("-"); // read the config from stdin, same convention as a normal build

        watchLogArea.setText("");
        appendWatchLogLine("Running: " + String.join(" ", command));
        appendWatchLogLine("");
        watchStatusLabel.setText("Starting\u2026");
        startWatchButton.setText("Stop Watch");
        logActivity("Watch: starting" + (viaConnect ? " via daemon at " + connectAddr : "") + " for " + settings.sketch);

        watchPid = null;
        SwingWorker<Integer, String> worker = new SwingWorker<Integer, String>() {
            protected Integer doInBackground() throws Exception {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                watchProcess = process;

                java.io.OutputStream stdin = process.getOutputStream();
                try {
                    stdin.write(configText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } finally {
                    stdin.close();
                }

                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                try {
                    boolean firstLine = true;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (firstLine) {
                            firstLine = false;
                            Integer pid = parsePid(line);
                            if (pid != null) {
                                watchPid = pid;
                            }
                        }
                        publish(line);
                    }
                } finally {
                    reader.close();
                }
                return process.waitFor();
            }

            protected void process(java.util.List<String> chunks) {
                for (String line : chunks) {
                    appendWatchLogLine(line);
                }
            }

            protected void done() {
                watchProcess = null;
                watchWorker = null;
                watchPid = null;
                startWatchButton.setText("Start Watch");
                try {
                    int exitCode = get();
                    appendWatchLogLine("");
                    appendWatchLogLine("--- Watch stopped (exit code " + exitCode + ") ---");
                    watchStatusLabel.setText("Stopped");
                    logActivity("Watch stopped (exit code " + exitCode + ").");
                } catch (Exception e) {
                    appendWatchLogLine("");
                    appendWatchLogLine("--- Watch error: " + rootErrorMessage(e) + " ---");
                    watchStatusLabel.setText("Error");
                    logActivity("Watch error: " + rootErrorMessage(e));
                }
            }
        };
        watchWorker = worker;
        worker.execute();
        watchStatusLabel.setText("Running" + (viaConnect ? " (via daemon at " + connectAddr + ")" : ""));
    }

    private void stopWatch() {
        appendWatchLogLine("--- Stop requested ---");
        logActivity("Watch: stop requested.");
        watchStatusLabel.setText("Stopping\u2026");
        Integer pid = watchPid;
        if (pid != null) {
            // Same reasoning as the main build Cancel and the daemon's stop: watch mode
            // spawns arduino-cli as its own child on every rebuild, so killing just its
            // own PID could orphan a child mid-compile - taskkill /F /T takes the whole tree.
            try {
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid)).start();
                return;
            } catch (IOException ex) {
                appendWatchLogLine("Could not run taskkill (" + ex.getMessage() + ") - falling back to a direct process kill.");
            }
        }
        Process p = watchProcess;
        if (p != null) {
            p.destroyForcibly();
        }
    }

    private DependencyViewerPanel buildDependencyViewerTab() {
        dependencyViewerPanel = new DependencyViewerPanel(new DependencyViewerPanel.ContextProvider() {
            public String getSketchPath() {
                return sketchField.getText().trim();
            }

            public String getFqbn() {
                return fqbnField.getText().trim();
            }

            public String getConfigFilePath() {
                return configFileField.getText().trim();
            }
        });
        return dependencyViewerPanel;
    }

    private JPanel buildLogTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        hideRepeatingPathsCheck.setSelected(true);
        hideUserPathCheck.setSelected(true);
        filterBar.add(hideRepeatingPathsCheck);
        filterBar.add(hideUserPathCheck);
        wireMirroredHideFilterCheckbox(hideRepeatingPathsCheck, activityHideRepeatingPathsCheck);
        wireMirroredHideFilterCheckbox(hideUserPathCheck, activityHideUserPathCheck);

        JLabel selectedBoardCaption = new JLabel("Selected board:");
        selectedBoardCaption.setFont(selectedBoardCaption.getFont().deriveFont(Font.BOLD));
        filterBar.add(selectedBoardCaption);
        filterBar.add(selectedBoardLabel);
        refreshSelectedBoardLabel();
        fqbnField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                refreshSelectedBoardLabel();
            }

            public void removeUpdate(DocumentEvent e) {
                refreshSelectedBoardLabel();
            }

            public void changedUpdate(DocumentEvent e) {
                refreshSelectedBoardLabel();
            }
        });

        JPanel topBar = new JPanel(new BorderLayout());
        topBar.add(filterBar, BorderLayout.CENTER);
        buildTimerLabel.setFont(buildTimerLabel.getFont().deriveFont(Font.BOLD));
        buildTimerLabel.setBorder(new EmptyBorder(0, 12, 0, 4));
        topBar.add(buildTimerLabel, BorderLayout.EAST);
        panel.add(topBar, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        panel.add(logScroll, BorderLayout.CENTER);

        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBorder(new EmptyBorder(8, 0, 0, 0));
        bottomBar.add(logStatusLabel, BorderLayout.WEST);

        cancelBuildButton.setEnabled(false);
        cancelBuildButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onCancelBuildClicked();
            }
        });
        JButton saveLogAsButton = new JButton("Save Log As\u2026");
        saveLogAsButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onSaveLogAsClicked();
            }
        });
        JButton clearLogButton = new JButton("Clear Log");
        clearLogButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                clearLogDisplay();
            }
        });
        JButton copyLogButton = new JButton("Copy to Clipboard");
        copyLogButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                copyToClipboard(logArea.getText(), "build log");
            }
        });
        buildProgressBar.setStringPainted(true);
        buildProgressBar.setValue(0);
        buildProgressBar.setPreferredSize(new Dimension(120, 18));
        openSketchFolderButton.setToolTipText("Opens the current sketch's folder - handy for grabbing the exported binary right after a build.");
        openSketchFolderButton.setVisible(exportCheck.isSelected());
        openSketchFolderButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onOpenSketchFolderClicked();
            }
        });
        JPanel cancelWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        cancelWrap.add(buildProgressBar);
        cancelWrap.add(openSketchFolderButton);
        cancelWrap.add(clearLogButton);
        cancelWrap.add(copyLogButton);
        cancelWrap.add(saveLogAsButton);
        cancelWrap.add(cancelBuildButton);
        bottomBar.add(cancelWrap, BorderLayout.EAST);
        panel.add(bottomBar, BorderLayout.SOUTH);

        return panel;
    }

    /** Copies the given text to the system clipboard, reused by both logs' Copy buttons. */
    private void copyToClipboard(String text, String whatWasCopied) {
        java.awt.datatransfer.StringSelection selection = new java.awt.datatransfer.StringSelection(text);
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
        logActivity("Copied " + whatWasCopied + " to clipboard.");
    }

    private void onSaveLogAsClicked() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save Build Log");
        chooser.setSelectedFile(new File("build-log.txt"));
        int result = chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        try {
            FileWriter writer = new FileWriter(file);
            try {
                writer.write(logArea.getText());
            } finally {
                writer.close();
            }
            logStatusLabel.setText("Log saved to " + file.getAbsolutePath());
            logActivity("Saved build log to " + file.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Could not save log:\n" + ex.getMessage(), "Save Log", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------------------------------------------------
    // Build runner: launches fastbuild as a subprocess, streams its output
    // into the Build Log tab, and supports cancelling mid-build.
    // ------------------------------------------------------------------

    /**
     * Runs fastbuild. When forceUpload is true (the Upload tab's own button),
     * -upload and -port are passed as extra one-shot CLI flags regardless of
     * the Upload after build checkbox - fastbuild.go's own flag handling
     * (`if *upload { cfg.upload = true }`) only ever forces upload ON, never
     * off, so this can't accidentally suppress an upload the checkbox already
     * wants; it just guarantees one happens for this run. Nothing about the
     * checkbox or persisted settings changes - fastbuild's usual skip-if-
     * unchanged caching means this still only recompiles if something
     * actually changed, same as a normal build, then uploads.
     */
    /** The CLI flags fastbuild gets invoked with, excluding the executable path itself and the trailing config source ("-" for stdin, or a real path for the exported .bat). Shared by the in-app build and the .bat export, so they can never drift apart. */
    private static java.util.List<String> buildFastbuildFlags(BuildSettings settings, boolean forceUpload,
            boolean forceRefreshDepsIndex, boolean forceCleanBuild, boolean forceRecompile, Boolean staleDepsChoice,
            String exportConflictOverride) {
        java.util.List<String> flags = new java.util.ArrayList<String>();
        if (settings.force || forceRecompile) {
            flags.add("-force");
        }
        if (settings.clean || forceCleanBuild) {
            flags.add("-clean");
        }
        if (settings.noDeps) {
            flags.add("-no-deps");
        }
        if (settings.noToolchain) {
            flags.add("-no-toolchain");
        }
        if (settings.refreshDepsIndex || forceRefreshDepsIndex) {
            flags.add("-refresh-deps-index");
        }
        if (settings.assumeYesStaleDeps || Boolean.TRUE.equals(staleDepsChoice)) {
            flags.add("-assume-yes-stale-deps");
        }
        if (settings.skipStaleDepsRefresh || Boolean.FALSE.equals(staleDepsChoice)) {
            flags.add("-skip-stale-deps-refresh");
        }
        if (exportConflictOverride != null) {
            flags.add("-export-conflict");
            flags.add(exportConflictOverride);
        }
        if (forceUpload) {
            flags.add("-upload");
            flags.add("-port");
            flags.add(settings.port.trim());
        }
        return flags;
    }

    /**
     * Escapes cmd.exe's special characters for a literal value being embedded in an
     * echo statement - this matters in practice, since real toolchain paths commonly
     * contain literal parentheses (e.g. "C:\Program Files (x86)\...") which would
     * otherwise confuse cmd's own block-grouping parser.
     */
    private static String batchEscapeLiteral(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '^': sb.append("^^"); break;
                case '&': sb.append("^&"); break;
                case '|': sb.append("^|"); break;
                case '<': sb.append("^<"); break;
                case '>': sb.append("^>"); break;
                case '(': sb.append("^("); break;
                case ')': sb.append("^)"); break;
                case '%': sb.append("%%"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Builds a standalone .bat that reproduces the current build settings without
     * this app running - it writes its own temp config file (one echo line per
     * config key, cleaned up afterward) and invokes fastbuild directly against it,
     * using the exact same flag-assembly logic (buildFastbuildFlags) the in-app
     * build uses, so the two can never drift apart. Verbose is forced on
     * regardless of the current setting, so there's always live compiler output
     * streaming - a deliberate substitute for a progress indicator, which batch
     * scripting has no clean way to show alongside a foreground process's own
     * output without backgrounding it and losing that live streaming entirely.
     */
    private static String buildBatchScriptContent(BuildSettings settings, String fastbuildExe, String friendlyBoard) {
        java.util.List<String> flags = buildFastbuildFlags(settings, false, false, false, false, null, null);

        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\r\n");
        sb.append("setlocal\r\n");
        sb.append("title fastbuild - ").append(batchEscapeLiteral(new File(settings.sketch).getName())).append("\r\n");
        sb.append("echo ============================================================\r\n");
        sb.append("echo  fastbuild standalone build script\r\n");
        sb.append("echo  Sketch: ").append(batchEscapeLiteral(settings.sketch)).append("\r\n");
        sb.append("echo  Board:  ").append(batchEscapeLiteral(friendlyBoard)).append("\r\n");
        sb.append("echo ============================================================\r\n");
        sb.append("echo.\r\n");
        sb.append("echo Compiler output will appear below as it runs - this can take a\r\n");
        sb.append("echo while; the window isn't stuck, it's working.\r\n");
        sb.append("echo.\r\n");
        sb.append("echo To cancel: press Ctrl+C in this window.\r\n");
        sb.append("echo If something is still running afterward ^(fastbuild launches\r\n");
        sb.append("echo arduino-cli as its own child process^), open another Command\r\n");
        sb.append("echo Prompt and run: taskkill /F /T /PID ^<the PID shown just below^>\r\n");
        sb.append("echo.\r\n");
        sb.append("\r\n");
        sb.append("set \"CFG=%TEMP%\\fastbuild_standalone_%RANDOM%.config\"\r\n");

        String configText = ConfigFileCodec.write(settings);
        for (String line : configText.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            sb.append("echo ").append(batchEscapeLiteral(line)).append(">>\"%CFG%\"\r\n");
        }
        sb.append("\r\n");

        StringBuilder invokeLine = new StringBuilder();
        invokeLine.append("\"").append(fastbuildExe).append("\"");
        for (String flag : flags) {
            if (flag.startsWith("-")) {
                invokeLine.append(" ").append(flag);
            } else {
                invokeLine.append(" \"").append(flag).append("\"");
            }
        }
        invokeLine.append(" \"%CFG%\"");
        sb.append(invokeLine).append("\r\n");
        sb.append("set EXITCODE=%ERRORLEVEL%\r\n");
        sb.append("del \"%CFG%\" >nul 2>&1\r\n");
        sb.append("\r\n");
        sb.append("echo.\r\n");
        sb.append("echo ------------------------------------------------------------\r\n");
        sb.append("echo Finished ^(exit code %EXITCODE%^)\r\n");
        sb.append("echo ------------------------------------------------------------\r\n");
        sb.append("echo.\r\n");
        sb.append("echo Press Enter to close this window...\r\n");
        sb.append("pause >nul\r\n");
        sb.append("endlocal\r\n");
        return sb.toString();
    }

    private void onOpenSketchFolderClicked() {
        String sketchPath = sketchField.getText().trim();
        if (sketchPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No sketch selected yet.", "Open Sketch Folder", JOptionPane.WARNING_MESSAGE);
            return;
        }
        File sketchDir = new File(sketchPath).getParentFile();
        if (sketchDir == null || !sketchDir.isDirectory()) {
            JOptionPane.showMessageDialog(this,
                    "Could not find the sketch's folder:\n" + sketchPath, "Open Sketch Folder", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            java.awt.Desktop.getDesktop().open(sketchDir);
            logActivity("Opened sketch folder: " + sketchDir.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Could not open the folder:\n" + rootErrorMessage(ex), "Open Sketch Folder", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onExportBatchFileClicked() {
        BuildSettings settings = readSettingsFromUI();
        String missing = settings.validateRequired();
        if (missing != null) {
            JOptionPane.showMessageDialog(this, missing, "Export .bat File", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String fastbuildExe = fastbuildExeField.getText().trim();
        if (fastbuildExe.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Set the fastbuild executable in App Settings first.", "Export .bat File", JOptionPane.WARNING_MESSAGE);
            return;
        }
        File sketchFile = new File(settings.sketch);
        File sketchDir = sketchFile.getParentFile();
        if (sketchDir == null || !sketchDir.isDirectory()) {
            JOptionPane.showMessageDialog(this,
                    "Could not determine the sketch's folder.", "Export .bat File", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String baseName = sketchFile.getName();
        if (baseName.toLowerCase(java.util.Locale.ROOT).endsWith(".ino")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        File batFile = new File(sketchDir, baseName + "_build.bat");
        if (batFile.exists()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    batFile.getName() + " already exists in this sketch's folder. Replace it?",
                    "Export .bat File", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }

        settings.verbose = true; // forced on for the standalone script - see buildBatchScriptContent's note
        String friendlyBoard = resolveBoardDisplayName(settings.fqbn);
        String content = buildBatchScriptContent(settings, fastbuildExe, friendlyBoard);

        try {
            java.io.Writer writer = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(batFile), java.nio.charset.StandardCharsets.UTF_8);
            try {
                writer.write(content);
            } finally {
                writer.close();
            }
            logActivity("Exported standalone build script: " + batFile.getAbsolutePath());
            tabs.setSelectedIndex(logTabIndex);
            appendLogLine("--- Exported standalone build script: " + batFile.getAbsolutePath() + " ---");
            appendLogLine("");
            for (String line : content.split("\r\n", -1)) {
                appendLogLine(line);
            }
            JOptionPane.showMessageDialog(this,
                    "Saved to:\n" + batFile.getAbsolutePath(), "Export .bat File", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Could not write the file:\n" + ex.getMessage(), "Export .bat File", JOptionPane.ERROR_MESSAGE);
            logActivity("Failed to export standalone build script: " + ex.getMessage());
        }
    }

    private void runFastbuild(boolean forceUpload, boolean forceRefreshDepsIndex, boolean forceCleanBuild, boolean forceRecompile) {
        if (currentBuildWorker != null) {
            return; // already running - the triggering button should be disabled, but this closes the gap either way
        }
        BuildSettings settings = readSettingsFromUI();
        String missing = settings.validateRequired();
        if (missing != null) {
            JOptionPane.showMessageDialog(this, missing, "Run Build", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final String fastbuildExe = fastbuildExeField.getText().trim();
        if (fastbuildExe.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Set the fastbuild executable in App Settings first.", "Run Build", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (forceUpload && settings.port.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Set a serial port in the Upload tab first.", "Upload", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // The Serial Monitor holding a port open would make arduino-cli's own
        // upload fail to open that same port - free it up automatically rather
        // than surprise the user with an upload error that's really just this.
        boolean willUpload = forceUpload || settings.upload;
        if (willUpload && serialSession != null && !settings.port.trim().isEmpty()
                && settings.port.trim().equalsIgnoreCase(String.valueOf(serialSessionPortName))) {
            onDisconnectSerialClicked("Disconnected automatically to free " + settings.port + " for uploading.");
        }

        // fastbuild's own interactive "rebuild stale header index? [y/N]" prompt
        // never actually reaches us - stdin is a pipe (that's how the config gets
        // sent), so fastbuild detects it isn't a real console and silently keeps
        // the stale index instead of hanging. We replicate the prompt here so it
        // still feels the same as running fastbuild directly in a terminal, then
        // pass the matching one-shot flag for just this run based on the answer.
        Boolean staleDepsChoice = null;
        if (!forceRefreshDepsIndex && shouldOfferStaleDepsPrompt(settings)) {
            staleDepsChoice = askRebuildStaleHeaderIndex(settings);
        }

        // Same reasoning as the stale-deps prompt above: fastbuild's own "export
        // destination already exists - overwrite? [y/N]" prompt (when exportConflict
        // is "ask") never reaches us either, for the same piped-stdin reason - it
        // silently falls back to auto-renaming instead. We replicate the prompt here
        // too, so picking "ask" in this UI can actually ask, same as it would running
        // fastbuild directly in a terminal.
        String exportConflictOverride = null;
        if (settings.export && settings.exportConflict == BuildSettings.ExportConflict.ASK) {
            File conflictFile = findExistingExportConflictFile(settings);
            if (conflictFile != null) {
                exportConflictOverride = askExportConflictChoice(conflictFile) ? "overwrite" : "rename";
            }
        }

        final String configText = ConfigFileCodec.write(settings);
        final java.util.List<String> command = new java.util.ArrayList<String>();
        command.add(fastbuildExe);
        command.addAll(buildFastbuildFlags(settings, forceUpload, forceRefreshDepsIndex, forceCleanBuild, forceRecompile, staleDepsChoice, exportConflictOverride));
        command.add("-"); // read the config from stdin - see the README's "Quick start"

        clearLogDisplay();
        if (forceRefreshDepsIndex) {
            appendLogLine("--- Forcing a header index rebuild ---");
            appendLogLine("");
        }
        appendLogLine("Running: " + String.join(" ", command));
        appendLogLine("");
        tabs.setSelectedIndex(logTabIndex);
        setBuildRunning(true);
        currentOperationVerb = forceUpload ? "Upload" : "Build";
        logActivity((forceRefreshDepsIndex ? "Header index rebuild started: " : (forceUpload ? "Upload started: " : "Build started: ")) + settings.sketch);

        currentBuildPid = null;
        currentBuildCancelled = false;
        beginStatusBarTiming();
        SwingWorker<Integer, String> worker = new SwingWorker<Integer, String>() {
            protected Integer doInBackground() throws Exception {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                currentBuildProcess = process;

                java.io.OutputStream stdin = process.getOutputStream();
                try {
                    stdin.write(configText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } finally {
                    stdin.close();
                }

                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                try {
                    boolean firstLine = true;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (firstLine) {
                            firstLine = false;
                            Integer pid = parsePid(line);
                            if (pid != null) {
                                currentBuildPid = pid;
                            }
                        }
                        publish(line);
                    }
                } finally {
                    reader.close();
                }
                return process.waitFor();
            }

            protected void process(java.util.List<String> chunks) {
                for (String line : chunks) {
                    appendLogLine(line);
                    scanLineForCacheStatus(line);
                    scanLineForFlashRamUsage(line);
                }
            }

            protected void done() {
                setBuildRunning(false);
                currentBuildProcess = null;
                currentBuildWorker = null;
                boolean wasCancelled = currentBuildCancelled;
                String verb = forceUpload ? "Upload" : "Build";
                try {
                    int exitCode = get();
                    appendLogLine("");
                    if (wasCancelled) {
                        appendLogLine("--- " + verb + " cancelled (exit code " + exitCode + ") ---");
                        logStatusLabel.setText("Cancelled");
                        logActivity(verb + " cancelled: " + settings.sketch);
                    } else if (exitCode == 0) {
                        appendLogLine("--- " + verb + " finished (exit code 0) ---");
                        logStatusLabel.setText("Succeeded");
                        logActivity(verb + " succeeded: " + settings.sketch);
                        saveSketchCacheAfterSuccessfulBuild(settings);
                    } else {
                        appendLogLine("--- " + verb + " finished (exit code " + exitCode + ") ---");
                        logStatusLabel.setText("Failed (exit code " + exitCode + ")");
                        logActivity(verb + " failed (exit code " + exitCode + "): " + settings.sketch);
                    }
                } catch (Exception e) {
                    if (wasCancelled) {
                        appendLogLine("--- " + verb + " cancelled ---");
                        logStatusLabel.setText("Cancelled");
                        logActivity(verb + " cancelled: " + settings.sketch);
                    } else {
                        appendLogLine("--- " + verb + " error: " + rootErrorMessage(e) + " ---");
                        logStatusLabel.setText("Error");
                        logActivity(verb + " error: " + rootErrorMessage(e));
                    }
                }
                finishStatusBarTiming(true); // unconditional, and after the banner above - the process has concluded either way
                if (editorTabPanel != null && editorTabPanel.highlightErrorLines(currentBuildErrorLocations)) {
                    tabs.setSelectedIndex(explorerTabIndex);
                }
            }
        };
        currentBuildWorker = worker;
        worker.execute();
    }

    // ------------------------------------------------------------------
    // Stale header-index detection - mirrors deps.go's own staleness check
    // (signature matching aside - see shouldOfferStaleDepsPrompt's note)
    // closely enough to decide whether fastbuild would have prompted.
    // ------------------------------------------------------------------

    /**
     * True if fastbuild would have hit its interactive "rebuild stale index?"
     * prompt for this exact run (and thus silently kept the old index instead,
     * since our piped stdin can't answer it) - so the settings themselves
     * already force an answer one way or the other, deps hashing isn't even in
     * play, or the cached index isn't actually old enough to matter.
     *
     * One thing this can't replicate: fastbuild's own check also compares a
     * content signature (installed library/header state) before ever
     * considering age, and rebuilds silently with no prompt at all if that
     * signature has changed. We don't recompute that signature here - the
     * worst case from that gap is an unnecessary prompt on a run where
     * fastbuild would have rebuilt anyway regardless of your answer, which is
     * harmless, just slightly redundant.
     */
    private boolean shouldOfferStaleDepsPrompt(BuildSettings settings) {
        if (settings.assumeYesStaleDeps || settings.skipStaleDepsRefresh || settings.refreshDepsIndex || settings.noDeps) {
            return false; // already decided, one way or another, for this run
        }
        if (!settings.hashLibraryHeaders || settings.configFile.trim().isEmpty()) {
            return false; // header index isn't even in use
        }
        if (settings.depsIndexMaxAgeHours <= 0) {
            return false; // age check disabled entirely
        }
        File indexFile = headerIndexCacheFile(settings);
        if (indexFile == null || !indexFile.isFile()) {
            return false; // nothing cached yet - first build for this board just creates it, no prompt needed
        }
        Long builtAtMillis = readHeaderIndexBuiltAt(indexFile);
        if (builtAtMillis == null) {
            return false; // unreadable/unparseable - let fastbuild itself sort it out
        }
        long ageHours = (System.currentTimeMillis() - builtAtMillis) / (1000L * 60 * 60);
        return ageHours >= settings.depsIndexMaxAgeHours;
    }

    /** Yes/No dialog mirroring fastbuild's own prompt text. Returns TRUE for rebuild, FALSE for keep (including if dismissed - same default as the CLI's own [y/N]). */
    private Boolean askRebuildStaleHeaderIndex(BuildSettings settings) {
        String message = "The cached header dependency index for this board is older than "
                + settings.depsIndexMaxAgeHours + " hour(s).\n\n"
                + "Rebuild it now? This walks the platform/library directories and may take a moment.\n"
                + "Otherwise, this build will keep using the existing index.";
        Object[] options = {"Rebuild Now", "Keep As-Is"};
        int choice = JOptionPane.showOptionDialog(this, message, "Header Index Is Stale",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        boolean rebuild = (choice == 0);
        logActivity("Stale header index: " + (rebuild ? "chose to rebuild." : "kept existing index."));
        return rebuild ? Boolean.TRUE : Boolean.FALSE;
    }

    /** Same path/naming fastbuild.go uses: <cacheRoot>/header-index-<pkg>_<arch>.json. */
    private static File headerIndexCacheFile(BuildSettings settings) {
        String cacheRoot = settings.cacheRoot.trim();
        if (cacheRoot.isEmpty()) {
            return null;
        }
        return new File(cacheRoot, "header-index-" + platformCacheKey(settings.fqbn) + ".json");
    }

    /**
     * Looks for a file already sitting in the sketch's own folder that a fresh export
     * would collide with - fastbuild's own export destination naming is
     * "<sketch base name>.<ext>", but the exact extension depends on the board's
     * platform (.hex for AVR, .bin for ESP8266/ESP32, etc.), which isn't something
     * this UI can predict without knowing that platform's own build recipe. Rather
     * than guess wrong, this checks for any existing file that starts with the
     * sketch's own name and ends in one of the common build-output extensions -
     * covers the realistic cases without needing per-platform knowledge.
     */
    private static File findExistingExportConflictFile(BuildSettings settings) {
        File sketchFile = new File(settings.sketch);
        File sketchDir = sketchFile.getParentFile();
        if (sketchDir == null || !sketchDir.isDirectory()) {
            return null;
        }
        String sketchBaseName = sketchFile.getName(); // e.g. "mysketch.ino"
        File[] candidates = sketchDir.listFiles();
        if (candidates == null) {
            return null;
        }
        String[] commonExportExtensions = {".bin", ".hex", ".uf2"};
        for (File f : candidates) {
            String name = f.getName();
            if (!name.startsWith(sketchBaseName) || name.equals(sketchBaseName)) {
                continue;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            for (String ext : commonExportExtensions) {
                if (lower.endsWith(ext)) {
                    return f;
                }
            }
        }
        return null;
    }

    private boolean askExportConflictChoice(File conflictFile) {
        String message = "Export destination already exists:\n" + conflictFile.getAbsolutePath()
                + "\n\nOverwrite it? Otherwise, the export will be saved under a new name instead\n"
                + "(the existing file is never touched).";
        Object[] options = {"Overwrite", "Save As New File"};
        int choice = JOptionPane.showOptionDialog(this, message, "Export Destination Already Exists",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        boolean overwrite = (choice == 0);
        logActivity("Export conflict: " + (overwrite ? "chose to overwrite " : "chose to save as a new file instead of overwriting ")
                + conflictFile.getName());
        return overwrite;
    }

    private static String platformCacheKey(String fqbn) {
        String[] parts = fqbn.split(":", 3);
        String pkg = (parts.length >= 1 && !parts[0].isEmpty()) ? parts[0] : "unknown";
        String arch = (parts.length >= 2 && !parts[1].isEmpty()) ? parts[1] : "unknown";
        return sanitizeCacheKeyPart(pkg) + "_" + sanitizeCacheKeyPart(arch);
    }

    private static String sanitizeCacheKeyPart(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == ' ' || c == '\t') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Reads the header index cache's "builtAt" field (an RFC3339 timestamp, same as Go's time.Time JSON marshaling) as epoch millis. */
    private static Long readHeaderIndexBuiltAt(File file) {
        try {
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
            try {
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                }
            } finally {
                reader.close();
            }
            Object root = MiniJson.parse(sb.toString());
            Object builtAt = MiniJson.path(root, "builtAt");
            if (!(builtAt instanceof String)) {
                return null;
            }
            return java.time.OffsetDateTime.parse((String) builtAt).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    private void onCancelBuildClicked() {
        if (currentBuildWorker == null) {
            return;
        }
        cancelBuildButton.setEnabled(false);
        currentBuildCancelled = true;
        logStatusLabel.setText("Cancelling\u2026");
        logActivity("Cancel " + currentOperationVerb + " clicked.");
        appendLogLine("--- Cancel requested ---");

        Integer pid = currentBuildPid;
        if (pid != null) {
            // Killing fastbuild.exe alone does NOT kill its arduino-cli.exe child (or that
            // child's own compiler subprocesses) on Windows - taskkill /F /T kills the
            // whole process tree. See fastbuild's own "fastbuild PID:" comment in main().
            try {
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid)).start();
                return;
            } catch (IOException ex) {
                appendLogLine("Could not run taskkill (" + ex.getMessage() + ") - falling back to a direct process kill.");
            }
        }
        Process process = currentBuildProcess;
        if (process != null) {
            process.destroyForcibly();
        }
    }

    /** Parses fastbuild's very first stdout line, "fastbuild PID: <n>", printed specifically so a wrapper can cancel cleanly. */
    private static Integer parsePid(String firstLine) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("fastbuild PID:\\s*(\\d+)").matcher(firstLine);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Matches ANSI CSI escape sequences (e.g. color codes) - arduino-cli colors some of its output,
     *  which JTextArea can't interpret, so it shows up as literal box/control-char glyphs otherwise. */
    private static final java.util.regex.Pattern ANSI_ESCAPE_PATTERN =
            java.util.regex.Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");

    private void appendLogLine(String line) {
        String cleaned = ANSI_ESCAPE_PATTERN.matcher(line).replaceAll("");
        logRawLines.add(cleaned);
        appendColoredLine(logArea, collapseIfRepeatedCommand(filterLogLine(cleaned)) + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
        updateBuildProgressFromLine(cleaned);
        scanLineForErrorLocation(cleaned);
    }

    // ------------------------------------------------------------------
    // Error-line highlighting in the Explorer tab - mirrors the Arduino
    // IDE's "jump to the error line" behavior. Parsed from the standard
    // gcc-style diagnostic format every C/C++ compiler in the toolchain
    // uses (file:line[:col]: error|warning: message) - this is parsed from
    // the RAW streamed line, before any of the hide-path/collapsing
    // filters run, since those replace real paths with placeholders and
    // this needs the actual path on disk to open/highlight the file.
    // ------------------------------------------------------------------

    private static final java.util.regex.Pattern COMPILER_DIAGNOSTIC_PATTERN =
            java.util.regex.Pattern.compile("^(.+?):(\\d+):(?:\\d+:)?\\s*(error|warning):\\s*(.+)$");

    private final java.util.List<EditorTabPanel.ErrorLocation> currentBuildErrorLocations = new java.util.ArrayList<EditorTabPanel.ErrorLocation>();

    private void scanLineForErrorLocation(String line) {
        java.util.regex.Matcher m = COMPILER_DIAGNOSTIC_PATTERN.matcher(line);
        if (!m.matches()) {
            return;
        }
        try {
            File file = new File(m.group(1));
            int lineNumber = Integer.parseInt(m.group(2));
            boolean isError = "error".equals(m.group(3));
            currentBuildErrorLocations.add(new EditorTabPanel.ErrorLocation(file, lineNumber, isError, m.group(4)));
        } catch (NumberFormatException ex) {
            // malformed line number - skip rather than guess
        }
    }

    // ------------------------------------------------------------------
    // Build progress bar - fastbuild/arduino-cli don't print an overall
    // percentage, so this is a best-effort heuristic, not an exact
    // progress. Two sources, tried in order:
    //  1. esptool's own upload percentage ("... (42 %)") - when present,
    //     this is a real, accurate number, straight from the tool.
    //  2. arduino-cli's well-known verbose-compile stage banners, mapped
    //     to approximate milestones. These are the same banners visible
    //     in every verbose build log throughout this project (Detecting
    //     libraries used..., Linking everything together..., etc.).
    // Never moves backward within a single run - only ever the highest
    // percentage seen so far.
    // ------------------------------------------------------------------

    private static final java.util.regex.Pattern UPLOAD_PERCENT_PATTERN = java.util.regex.Pattern.compile("\\((\\d{1,3})\\s*%\\)");

    private void updateBuildProgressFromLine(String line) {
        int estimate = estimateProgressPercent(line);
        if (estimate > buildProgressBar.getValue()) {
            buildProgressBar.setValue(Math.min(100, estimate));
        }
    }

    private static int estimateProgressPercent(String line) {
        java.util.regex.Matcher m = UPLOAD_PERCENT_PATTERN.matcher(line);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                // fall through to the stage-based estimate below
            }
        }
        if (line.contains("Detecting libraries used")) {
            return 10;
        }
        if (line.contains("Generating function prototypes")) {
            return 20;
        }
        if (line.contains("Compiling sketch")) {
            return 30;
        }
        if (line.contains("Compiling libraries")) {
            return 45;
        }
        if (line.contains("Compiling core")) {
            return 65;
        }
        if (line.contains("Linking everything together")) {
            return 85;
        }
        if (line.contains("Creating BIN file") || line.contains("Creating output")) {
            return 92;
        }
        return -1;
    }

    /** Re-renders the whole log from the stored raw lines - called when either hide-paths checkbox is toggled, so it applies retroactively, not just to new lines. */
    private void rebuildLogDisplay() {
        previousCompilerCommandTokens = null; // reprocessing from the start - the collapse state must not carry over from wherever it was left
        logArea.setText(""); // clears the document; setText() on a styled document doesn't preserve per-line colors, so lines are re-inserted one at a time below instead
        for (String raw : logRawLines) {
            appendColoredLine(logArea, collapseIfRepeatedCommand(filterLogLine(raw)) + "\n");
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /** Clears both the displayed log and the raw-lines buffer it's rebuilt from - use this instead of logArea.setText("") directly. */
    private void clearLogDisplay() {
        logRawLines.clear();
        logArea.setText("");
        previousCompilerCommandTokens = null; // a new build starts fresh - nothing to compare the first line against
    }

    // ------------------------------------------------------------------
    // Collapsing repeated compiler invocations - a different problem from
    // the known-path substitution above. A verbose build repeats almost the
    // entire compiler command line (every -D, every -I, the compiler path
    // itself) for every single source file, with only the input/output
    // filenames actually changing. Rather than hand-maintain more known
    // paths (which breaks the moment a new platform/toolchain shows up),
    // this detects "same shape as the previous line, only the file
    // changed" generically, by diffing tokens - so it works for any
    // compiler, any platform, without needing to know its specifics.
    // ------------------------------------------------------------------

    private String[] previousCompilerCommandTokens;

    private String collapseIfRepeatedCommand(String filteredLine) {
        if (!hideRepeatingPathsCheck.isSelected()) {
            previousCompilerCommandTokens = null;
            return filteredLine;
        }
        if (!looksLikeToolInvocation(filteredLine)) {
            previousCompilerCommandTokens = null; // not a command line - the next one starts a fresh comparison
            return filteredLine;
        }
        String[] tokens = tokenizeCommandLine(filteredLine);
        String collapsed = null;
        if (previousCompilerCommandTokens != null && sameCommandDifferentFile(previousCompilerCommandTokens, tokens)) {
            collapsed = buildCollapsedCompileLine(tokens);
            if (collapsed == null) {
                collapsed = buildGenericCollapsedLine(previousCompilerCommandTokens, tokens);
            }
        }
        previousCompilerCommandTokens = tokens;
        return collapsed != null ? collapsed : filteredLine;
    }

    /** A generic, tool-agnostic heuristic: long line, ends in an -o <output> argument, with several -D/-I flags - true of essentially every C/C++/assembler compiler invocation regardless of platform or toolchain. */
    /**
     * Deliberately broad on purpose: this used to require -D/-I flags and an
     * -o argument, which only describes a *compile* command - it completely
     * missed archiver invocations ("tool" cru "archive" "object"), which
     * have none of those and repeat just as much (once per object file
     * going into a static library). Rather than hand-recognize every
     * possible command shape a toolchain might use, this just checks the
     * line is long enough to be worth comparing at all - the strict
     * per-token diff in sameCommandDifferentFile() is what actually
     * prevents false collapses, not this filter.
     */
    private static boolean looksLikeToolInvocation(String line) {
        if (line.length() < 60) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("\"")) {
            return true;
        }
        int firstSpace = trimmed.indexOf(' ');
        String firstToken = firstSpace >= 0 ? trimmed.substring(0, firstSpace) : trimmed;
        return firstToken.indexOf('\\') >= 0 || firstToken.indexOf('/') >= 0;
    }

    /** Splits on whitespace, keeping double-quoted segments (which may contain spaces) as single tokens. */
    private static String[] tokenizeCommandLine(String line) {
        java.util.List<String> tokens = new java.util.ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ' ' && !inQuotes) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens.toArray(new String[0]);
    }

    /** Same tool, same flags, only a small number of tokens differ (typically the input file, the -o value, and sometimes a -MF dependency-file value). Different token counts or a large number of differences means it's a genuinely different command, not a repeat. */
    private static boolean sameCommandDifferentFile(String[] previous, String[] current) {
        if (previous.length != current.length) {
            return false;
        }
        int diffCount = 0;
        for (int i = 0; i < previous.length; i++) {
            if (!previous[i].equals(current[i])) {
                diffCount++;
                if (diffCount > 4 || diffCount * 2 > previous.length) {
                    return false;
                }
            }
        }
        return diffCount > 0;
    }

    /** Extracts just the input file (the token right before -o) and the output file (right after -o), showing only their basenames. */
    private static String buildCollapsedCompileLine(String[] tokens) {
        int oIndex = -1;
        for (int i = 0; i < tokens.length; i++) {
            if ("-o".equals(tokens[i])) {
                oIndex = i;
                break;
            }
        }
        if (oIndex < 0 || oIndex + 1 >= tokens.length || oIndex - 1 < 0) {
            return null; // doesn't fit the shape we expect - safest to just show the full line instead
        }
        String inputFile = basenameOfToken(tokens[oIndex - 1]);
        String outputFile = basenameOfToken(tokens[oIndex + 1]);
        if (inputFile == null || outputFile == null) {
            return null;
        }
        return "Compiling: " + inputFile + " -> " + outputFile;
    }

    /**
     * Falls back to this when a repeated command doesn't fit the compile
     * shape (no -o argument) - archiver invocations are the common case
     * ("tool" cru "archive" "object", once per object file going into a
     * static library), recognized via "cru" since that's a standard
     * GNU/POSIX ar convention rather than anything platform-specific.
     * Anything else just shows whichever token(s) actually changed, so a
     * genuinely unrecognized tool still gets collapsed instead of quietly
     * falling through to showing the full line every time.
     */
    private static String buildGenericCollapsedLine(String[] previous, String[] current) {
        boolean looksLikeArchive = false;
        for (String token : current) {
            if ("cru".equals(token) || "rcs".equals(token) || "cr".equals(token)) {
                looksLikeArchive = true;
                break;
            }
        }
        java.util.List<String> changedBasenames = new java.util.ArrayList<String>();
        for (int i = 0; i < current.length; i++) {
            if (!previous[i].equals(current[i])) {
                String base = basenameOfToken(current[i]);
                if (base != null) {
                    changedBasenames.add(base);
                }
            }
        }
        if (changedBasenames.isEmpty()) {
            return null;
        }
        String label = looksLikeArchive ? "Archiving: " : "Repeated command, changed: ";
        return label + String.join(", ", changedBasenames);
    }

    private static String basenameOfToken(String token) {
        String unquoted = token;
        if (unquoted.length() >= 2 && unquoted.startsWith("\"") && unquoted.endsWith("\"")) {
            unquoted = unquoted.substring(1, unquoted.length() - 1);
        }
        int lastSlash = Math.max(unquoted.lastIndexOf('\\'), unquoted.lastIndexOf('/'));
        String name = lastSlash >= 0 ? unquoted.substring(lastSlash + 1) : unquoted;
        return name.isEmpty() ? null : name;
    }

    private String cachedArduinoDataDir;
    private String cachedArduinoDataDirForConfigPath;

    /**
     * The Arduino15 "data" directory (holding packages/tools/etc.), read
     * from arduino-cli.yaml and cached against the config path it came from
     * - recomputed only if that path actually changes, since this is called
     * from filterLogLine() for every single streamed line during a verbose
     * build and re-reading + re-parsing the yaml file that often would add
     * up fast.
     */
    private String arduinoDataDirForFiltering() {
        String configPath = configFileField.getText().trim();
        if (configPath.isEmpty()) {
            return null;
        }
        if (!configPath.equals(cachedArduinoDataDirForConfigPath)) {
            cachedArduinoDataDirForConfigPath = configPath;
            try {
                cachedArduinoDataDir = WizardDataService.readArduinoDataDir(configPath);
            } catch (Exception ex) {
                cachedArduinoDataDir = null;
            }
        }
        return cachedArduinoDataDir;
    }

    // ------------------------------------------------------------------
    // Platform version auto-detection - lists what's actually installed for
    // the current board's platform (<data>/packages/<pkg>/hardware/<arch>/*),
    // the same directory convention resolvePlatformDir uses on the Go side,
    // so the combo box always reflects reality rather than a hand-typed
    // guess. Sorting uses a direct port of compareVersions from deps.go, so
    // "highest installed" here always matches what fastbuild itself would
    // actually auto-select when this is left blank.
    // ------------------------------------------------------------------

    private static final String PLATFORM_VERSION_AUTO_LABEL = "(auto - highest installed)";

    private void refreshPlatformVersionOptions() {
        String fqbn = fqbnField.getText().trim();
        String vendorArch = vendorArchOf(fqbn);
        platformVersionCaption.setText(vendorArch == null ? " " : "Installed versions for " + vendorArch + ":");

        Object currentSelection = platformVersionCombo.getSelectedItem();
        String currentValue = currentSelection == null ? "" : currentSelection.toString().trim();
        if (PLATFORM_VERSION_AUTO_LABEL.equals(currentValue)) {
            currentValue = "";
        }

        java.util.List<String> versions = listInstalledPlatformVersions(fqbn);
        platformVersionCombo.removeAllItems();
        platformVersionCombo.addItem(PLATFORM_VERSION_AUTO_LABEL);
        for (String v : versions) {
            platformVersionCombo.addItem(v);
        }
        // The combo isn't editable, so setSelectedItem silently does nothing if the
        // value isn't already an item - keep a saved/pinned version selectable even
        // when it's not among what's currently detected (e.g. after switching
        // platforms, or that exact version got uninstalled), rather than quietly
        // losing it.
        if (!currentValue.isEmpty() && !versions.contains(currentValue)) {
            platformVersionCombo.addItem(currentValue);
        }
        restorePlatformVersionSelection(currentValue);
    }

    private void restorePlatformVersionSelection(String value) {
        if (value == null || value.trim().isEmpty()) {
            platformVersionCombo.setSelectedItem(PLATFORM_VERSION_AUTO_LABEL);
            return;
        }
        String trimmed = value.trim();
        boolean found = false;
        for (int i = 0; i < platformVersionCombo.getItemCount(); i++) {
            if (trimmed.equals(platformVersionCombo.getItemAt(i))) {
                found = true;
                break;
            }
        }
        if (!found) {
            // Not editable, so setSelectedItem alone would silently fail here -
            // add it explicitly first so a saved/pinned value is never silently lost,
            // regardless of whether refreshPlatformVersionOptions happened to run
            // with the right state before this was called.
            platformVersionCombo.addItem(trimmed);
        }
        platformVersionCombo.setSelectedItem(trimmed);
    }

    private String readPlatformVersionSelection() {
        Object selected = platformVersionCombo.getSelectedItem();
        String value = selected == null ? "" : selected.toString().trim();
        return PLATFORM_VERSION_AUTO_LABEL.equals(value) ? "" : value;
    }

    /** The "vendor:arch" prefix of an FQBN (e.g. "esp8266:esp8266" from "esp8266:esp8266:nodemcu:baud=..."), or null if the FQBN doesn't have at least those two segments. */
    private static String vendorArchOf(String fqbn) {
        if (fqbn == null || fqbn.trim().isEmpty()) {
            return null;
        }
        String[] parts = fqbn.trim().split(":", 3);
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        return parts[0] + ":" + parts[1];
    }

    /** Installed version folder names for the given FQBN's vendor:arch, highest first - or empty if nothing can be detected yet (no data dir known, platform not installed, etc). */
    private java.util.List<String> listInstalledPlatformVersions(String fqbn) {
        java.util.List<String> result = new java.util.ArrayList<String>();
        String vendorArch = vendorArchOf(fqbn);
        if (vendorArch == null) {
            return result;
        }
        String[] parts = vendorArch.split(":", 2);
        String dataDir = arduinoDataDirForFiltering();
        if (dataDir == null || dataDir.isEmpty()) {
            return result;
        }
        File hardwareDir = new File(new File(new File(new File(dataDir, "packages"), parts[0]), "hardware"), parts[1]);
        File[] entries = hardwareDir.listFiles();
        if (entries == null) {
            return result;
        }
        for (File f : entries) {
            if (f.isDirectory()) {
                result.add(f.getName());
            }
        }
        java.util.Collections.sort(result, new java.util.Comparator<String>() {
            public int compare(String a, String b) {
                return -compareVersions(a, b); // descending - highest installed first
            }
        });
        return result;
    }

    /**
     * Direct port of compareVersions from deps.go - compares dotted-numeric version
     * strings segment by segment, numerically (so "3.10.0" correctly sorts after
     * "3.9.0", unlike plain lexical comparison). A missing segment defaults to "0"
     * rather than empty, so "3.0" and "3.0.0" compare equal. Falls back to lexical
     * comparison for a non-numeric segment rather than failing outright.
     */
    private static int compareVersions(String a, String b) {
        String[] aParts = a.split("\\.");
        String[] bParts = b.split("\\.");
        int len = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < len; i++) {
            String aSeg = i < aParts.length ? aParts[i] : "0";
            String bSeg = i < bParts.length ? bParts[i] : "0";
            Integer aNum = tryParseInt(aSeg);
            Integer bNum = tryParseInt(bSeg);
            if (aNum != null && bNum != null) {
                if (!aNum.equals(bNum)) {
                    return aNum < bNum ? -1 : 1;
                }
                continue;
            }
            if (!aSeg.equals(bSeg)) {
                return aSeg.compareTo(bSeg);
            }
        }
        return 0;
    }

    private static Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String filterLogLine(String line) {
        String result = line;
        if (hideRepeatingPathsCheck.isSelected()) {
            result = replaceAllPaths(result, fastbuildExeField.getText().trim(), "<fastbuild-exe>");
            result = replaceAllPaths(result, arduinoCliField.getText().trim(), "<arduino-cli-exe>");
            result = replaceAllPaths(result, configFileField.getText().trim(), "<arduino-cli-yaml>");
            String cacheRoot = cacheRootField.getText().trim();
            result = replaceAllPaths(result, cacheRoot, "<cache-root>");
            String wizardCache = wizardCacheDirField.getText().trim();
            if (!wizardCache.isEmpty() && !wizardCache.equalsIgnoreCase(cacheRoot)) {
                result = replaceAllPaths(result, wizardCache, "<wizard-cache>");
            }
            File sketchDir = sketchDirOf(sketchField.getText().trim());
            if (sketchDir != null) {
                result = replaceAllPaths(result, sketchDir.getAbsolutePath(), "<sketch-dir>");
            }
            String dataDir = arduinoDataDirForFiltering();
            if (dataDir != null && !dataDir.isEmpty()) {
                // Covers every installed platform's tools (compilers, python, ctags, etc.) and
                // hardware/core files in one shot - this is where the bulk of a verbose compile
                // log's repeated noise actually lives (e.g. .../packages/esp8266/tools/xtensa-
                // lx106-elf-gcc/<version>/... and .../packages/esp8266/hardware/esp8266/<version>/...).
                result = replaceAllPaths(result, new File(dataDir, "packages").getAbsolutePath(), "<arduino15>");
            }
        }
        // Runs last, on purpose: every path above lives under the user's home folder too, so
        // swapping the home prefix out first would destroy the exact substring those more
        // specific replacements are looking for. This only mops up whatever's left afterward -
        // e.g. a library folder under Documents that isn't one of the known paths above.
        if (hideUserPathCheck.isSelected()) {
            result = replaceAllPaths(result, System.getProperty("user.home", ""), "<home>");
        }
        return result;
    }

    private static File sketchDirOf(String sketchPath) {
        if (sketchPath.isEmpty()) {
            return null;
        }
        return new File(sketchPath).getParentFile();
    }

    /** Case-insensitive literal (not regex) replacement - paths often contain characters like '\' and '(' that would otherwise need escaping. */
    /**
     * Different parts of the log spell the very same path differently: our
     * own printed lines use plain single backslashes, but arduino-cli's own
     * verbose echo of each compiler invocation double-escapes backslashes
     * when quoting each argument (`"C:\\Users\\..."`), and `-I` include
     * flags inside those same invocations often use forward slashes
     * instead. Matching only the plain form (as returned by
     * System.getProperty/File.getAbsolutePath) silently missed every
     * occurrence in the other two forms - this tries all three.
     */
    private static String replaceAllPaths(String text, String path, String placeholder) {
        if (path == null || path.trim().isEmpty()) {
            return text;
        }
        String trimmed = path.trim();
        java.util.LinkedHashSet<String> variants = new java.util.LinkedHashSet<String>();
        variants.add(trimmed);
        variants.add(trimmed.replace("\\", "\\\\"));
        variants.add(trimmed.replace('\\', '/'));
        String result = text;
        for (String variant : variants) {
            try {
                result = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(variant), java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(result).replaceAll(java.util.regex.Matcher.quoteReplacement(placeholder));
            } catch (Exception e) {
                // ignore this variant, the others may still match
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Persistent activity log - every notable action (open/save, wizard
    // steps, build start/finish, etc.), visible under whichever tab is
    // showing, not just the Build Log tab.
    // ------------------------------------------------------------------

    private static final java.text.SimpleDateFormat ACTIVITY_LOG_TIME_FORMAT = new java.text.SimpleDateFormat("HH:mm:ss");

    // ------------------------------------------------------------------
    // Persistent status bar - Sketch / Board / Cache / Build time, visible
    // under every tab, same as the Activity Log below it.
    // ------------------------------------------------------------------

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        bar.add(statusBarRow("Sketch:", statusBarSketchLabel));
        bar.add(Box.createHorizontalStrut(16));
        bar.add(statusBarRow("Board:", statusBarBoardLabel));
        bar.add(Box.createHorizontalStrut(16));
        bar.add(statusBarRow("Cache:", statusBarCacheLabel));
        bar.add(Box.createHorizontalStrut(16));
        bar.add(statusBarRow("Flash/RAM:", statusBarFlashRamLabel));
        bar.add(Box.createHorizontalGlue());

        DocumentListener refresh = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                refreshStatusBarSketchAndBoard();
            }

            public void removeUpdate(DocumentEvent e) {
                refreshStatusBarSketchAndBoard();
            }

            public void changedUpdate(DocumentEvent e) {
                refreshStatusBarSketchAndBoard();
            }
        };
        sketchField.getDocument().addDocumentListener(refresh);
        fqbnField.getDocument().addDocumentListener(refresh);
        refreshStatusBarSketchAndBoard();

        fqbnField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                refreshPlatformVersionOptions();
            }

            public void removeUpdate(DocumentEvent e) {
                refreshPlatformVersionOptions();
            }

            public void changedUpdate(DocumentEvent e) {
                refreshPlatformVersionOptions();
            }
        });
        refreshPlatformVersionOptions();

        return bar;
    }

    private static JPanel statusBarRow(String label, JLabel valueLabel) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JLabel captionLabel = new JLabel(label);
        captionLabel.setFont(captionLabel.getFont().deriveFont(Font.BOLD));
        row.add(captionLabel);
        row.add(valueLabel);
        return row;
    }

    private void refreshStatusBarSketchAndBoard() {
        String sketchPath = sketchField.getText().trim();
        statusBarSketchLabel.setText(sketchPath.isEmpty() ? "-" : new File(sketchPath).getName());
        statusBarBoardLabel.setText(resolveBoardDisplayName(fqbnField.getText().trim()));
    }

    /** Called right before a build/upload subprocess launches - resets the timing/cache-detection state for this run. */
    private void beginStatusBarTiming() {
        currentOperationStartMillis = System.currentTimeMillis();
        sawCacheHitThisRun = false;
        sawCacheMissThisRun = false;
        buildProgressBar.setValue(0);
        currentBuildErrorLocations.clear();
        if (editorTabPanel != null) {
            editorTabPanel.clearErrorHighlights();
        }
        startBuildElapsedTicker();
    }

    /** Starts (or restarts) the Build Log tab's live "elapsed so far" label, ticking once a second. */
    private void startBuildElapsedTicker() {
        if (buildElapsedTicker != null) {
            buildElapsedTicker.stop();
        }
        buildTimerLabel.setText(formatElapsedForTimerLabel(0));
        buildElapsedTicker = new javax.swing.Timer(1000, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                buildTimerLabel.setText(formatElapsedForTimerLabel(System.currentTimeMillis() - currentOperationStartMillis));
            }
        });
        buildElapsedTicker.start();
    }

    private static String formatElapsedForTimerLabel(long elapsedMs) {
        long totalSeconds = elapsedMs / 1000;
        return String.format("Running: %d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    /** Scans one streamed build-log line for fastbuild's own cache-hit/miss phrasing - see fastbuild.go's run(). */
    private void scanLineForCacheStatus(String line) {
        if (line.contains("(cache hit)")) {
            sawCacheHitThisRun = true;
        } else if (line.contains("Build succeeded in")) {
            sawCacheMissThisRun = true;
        }
    }

    // ------------------------------------------------------------------
    // Flash/RAM usage for the status bar - these two lines are printed by
    // arduino-cli itself (not fastbuild.go, which has no concept of memory
    // usage at all), in the same standard wording across every platform's
    // compile output. Neither fastbuild's own -stats summary nor anything
    // else in this UI surfaces this anywhere otherwise - it's only ever
    // visible by scrolling through the raw log - so this is worth lifting
    // into the status bar the same way Cache/Build already are.
    // ------------------------------------------------------------------

    private static final java.util.regex.Pattern FLASH_USAGE_PATTERN = java.util.regex.Pattern.compile(
            "Sketch uses ([\\d,]+) bytes \\((\\d+)%\\) of program storage space\\. Maximum is ([\\d,]+) bytes");
    private static final java.util.regex.Pattern RAM_USAGE_PATTERN = java.util.regex.Pattern.compile(
            "Global variables use ([\\d,]+) bytes \\((\\d+)%\\) of dynamic memory.*?Maximum is ([\\d,]+) bytes");

    private String lastFlashUsageText;
    private String lastRamUsageText;

    private void scanLineForFlashRamUsage(String line) {
        java.util.regex.Matcher flashMatcher = FLASH_USAGE_PATTERN.matcher(line);
        if (flashMatcher.find()) {
            lastFlashUsageText = "Flash " + formatBytesShort(flashMatcher.group(1)) + "/"
                    + formatBytesShort(flashMatcher.group(3)) + " (" + flashMatcher.group(2) + "%)";
            updateStatusBarFlashRamLabel();
            return; // a single line only ever matches one of the two patterns
        }
        java.util.regex.Matcher ramMatcher = RAM_USAGE_PATTERN.matcher(line);
        if (ramMatcher.find()) {
            lastRamUsageText = "RAM " + formatBytesShort(ramMatcher.group(1)) + "/"
                    + formatBytesShort(ramMatcher.group(3)) + " (" + ramMatcher.group(2) + "%)";
            updateStatusBarFlashRamLabel();
        }
    }

    private void updateStatusBarFlashRamLabel() {
        if (lastFlashUsageText == null && lastRamUsageText == null) {
            statusBarFlashRamLabel.setText("-");
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (lastFlashUsageText != null) {
            sb.append(lastFlashUsageText);
        }
        if (lastRamUsageText != null) {
            if (sb.length() > 0) {
                sb.append("  |  ");
            }
            sb.append(lastRamUsageText);
        }
        statusBarFlashRamLabel.setText(sb.toString());
    }

    /** Renders a raw byte count (as printed by arduino-cli, possibly with thousands separators) as a short KB/MB figure. */
    private static String formatBytesShort(String rawNumber) {
        try {
            long bytes = Long.parseLong(rawNumber.replace(",", ""));
            if (bytes >= 1024 * 1024) {
                return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
            }
            if (bytes >= 1024) {
                return String.format("%.0fKB", bytes / 1024.0);
            }
            return bytes + "B";
        } catch (NumberFormatException ex) {
            return rawNumber;
        }
    }

    /** Called whenever a build/upload concludes, regardless of outcome - updates the Cache/Build fields in the status bar, stops the elapsed-time ticker, prints the final elapsed time to the build log, and clears the ticking label. */
    private void finishStatusBarTiming(boolean fastbuildRan) {
        double elapsedSeconds = (System.currentTimeMillis() - currentOperationStartMillis) / 1000.0;
        statusBarTimeLabel.setText(String.format("%.2f s", elapsedSeconds));
        if (buildElapsedTicker != null) {
            buildElapsedTicker.stop();
            buildElapsedTicker = null;
        }
        buildProgressBar.setValue(100);
        appendLogLine(String.format("Elapsed time: %.2f s", elapsedSeconds));
        buildTimerLabel.setText(" ");
        if (!fastbuildRan) {
            statusBarCacheLabel.setText("-"); // e.g. Upload This File - no fastbuild involved, no cache concept applies
        } else if (sawCacheHitThisRun) {
            statusBarCacheLabel.setText("HIT");
        } else if (sawCacheMissThisRun) {
            statusBarCacheLabel.setText("MISS");
        } else {
            statusBarCacheLabel.setText("-"); // e.g. the build failed before reaching either log line
        }
    }

    private JComponent buildActivityLogPanel() {
        activityLogArea.setEditable(false);
        activityLogArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        installEditPopup(activityLogArea);
        JScrollPane scroll = new JScrollPane(activityLogArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        activityHideRepeatingPathsCheck.setSelected(true);
        activityHideUserPathCheck.setSelected(true);
        filterBar.add(activityHideRepeatingPathsCheck);
        filterBar.add(activityHideUserPathCheck);
        wireMirroredHideFilterCheckbox(activityHideRepeatingPathsCheck, hideRepeatingPathsCheck);
        wireMirroredHideFilterCheckbox(activityHideUserPathCheck, hideUserPathCheck);
        JButton clearActivityLogButton = new JButton("Clear Log");
        clearActivityLogButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                clearActivityLogDisplay();
            }
        });
        filterBar.add(clearActivityLogButton);
        JButton copyActivityLogButton = new JButton("Copy to Clipboard");
        copyActivityLogButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                copyToClipboard(activityLogArea.getText(), "activity log");
            }
        });
        filterBar.add(copyActivityLogButton);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(filterBar, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(100, 140));
        panel.setBorder(BorderFactory.createTitledBorder("Activity Log"));
        return panel;
    }

    /** Keeps a hide-filter checkbox pair (one in Build Log, one in Activity Log) in sync and re-filters both logs whenever either changes. */
    private void wireMirroredHideFilterCheckbox(final JCheckBox self, final JCheckBox mirror) {
        self.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                mirror.setSelected(self.isSelected()); // setSelected() doesn't fire its own ActionListener, so this is safe
                rebuildLogDisplay();
                rebuildActivityLogDisplay();
            }
        });
    }

    // Fixed defaults for now - no per-category settings UI yet, may add one later.
    private static final Color LOG_COLOR_ERROR = new Color(200, 30, 30);
    private static final Color LOG_COLOR_WARNING = new Color(190, 120, 0);
    private static final Color LOG_COLOR_SUCCESS = new Color(20, 130, 20);
    private static final Color LOG_COLOR_CACHE = new Color(90, 110, 150);

    /**
     * Classifies a log line by keyword to pick its color. Checked in this order so a
     * line matching more than one category (e.g. "cache hit" contains both "cache" and
     * counts as a success) lands on the more specific/important one: errors first, then
     * warnings, then success (including cache hits specifically), then general cache
     * info, then everything else stays the default text color.
     */
    // Word-boundary-aware and precompiled (this runs per log line) - plain substring
    // matching previously false-positived on "-Werror=return-type" (a normal compiler
    // flag present in every compile command, not an actual error) and on the ESP8266
    // board option "exception=disabled" (a config value, not a thrown exception) -
    // "exception" was dropped from the keyword list entirely rather than just given a
    // word-boundary guard, since it's a legitimate, common option name in this context
    // and genuine exception reports don't really show up in these build logs anyway.
    private static final java.util.regex.Pattern LOG_ERROR_PATTERN =
            java.util.regex.Pattern.compile("(?i)\\b(error|failed|fatal)\\b");
    private static final java.util.regex.Pattern LOG_WARNING_PATTERN =
            java.util.regex.Pattern.compile("(?i)\\bwarning\\b");
    private static final java.util.regex.Pattern LOG_SUCCESS_PATTERN =
            java.util.regex.Pattern.compile("(?i)\\b(succeeded|success|done|saved)\\b|cache hit");
    private static final java.util.regex.Pattern LOG_CACHE_PATTERN =
            java.util.regex.Pattern.compile("(?i)\\b(cache|reusing)\\b|skipping compile");

    private static Color colorForLogLine(String line) {
        if (LOG_ERROR_PATTERN.matcher(line).find()) {
            return LOG_COLOR_ERROR;
        }
        if (LOG_WARNING_PATTERN.matcher(line).find()) {
            return LOG_COLOR_WARNING;
        }
        if (LOG_SUCCESS_PATTERN.matcher(line).find()) {
            return LOG_COLOR_SUCCESS;
        }
        if (LOG_CACHE_PATTERN.matcher(line).find()) {
            return LOG_COLOR_CACHE;
        }
        return null; // default text color
    }

    /** Appends one line of text to the given styled log pane, colored by colorForLogLine(). Shared by both the Activity Log and the Build Log. */
    private void appendColoredLine(JTextPane pane, String text) {
        StyledDocument doc = pane.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        Color color = colorForLogLine(text);
        StyleConstants.setForeground(attrs, color != null ? color : pane.getForeground());
        try {
            doc.insertString(doc.getLength(), text, attrs);
        } catch (BadLocationException ex) {
            // Shouldn't happen inserting at the document's own current end.
        }
    }

    private void logActivity(String message) {
        String timestamp;
        synchronized (ACTIVITY_LOG_TIME_FORMAT) {
            timestamp = ACTIVITY_LOG_TIME_FORMAT.format(new java.util.Date());
        }
        String raw = "[" + timestamp + "] " + message;
        activityLogRawLines.add(raw);
        appendColoredLine(activityLogArea, filterLogLine(raw) + "\n");
        activityLogArea.setCaretPosition(activityLogArea.getDocument().getLength());
    }

    /** Re-renders the whole Activity Log from its stored raw lines - same idea as rebuildLogDisplay(), for the same reason. */
    private void rebuildActivityLogDisplay() {
        activityLogArea.setText(""); // clears the document; setText() on a styled document doesn't preserve per-line colors, so lines are re-inserted one at a time below instead
        for (String raw : activityLogRawLines) {
            appendColoredLine(activityLogArea, filterLogLine(raw) + "\n");
        }
        activityLogArea.setCaretPosition(activityLogArea.getDocument().getLength());
    }

    /** Clears both the displayed Activity Log and the raw-lines buffer it's rebuilt from. */
    private void clearActivityLogDisplay() {
        activityLogRawLines.clear();
        activityLogArea.setText("");
    }

    /**
     * Locks down everything except the Build Log tab while a build runs -
     * tab switching (so config options etc. can't be changed mid-build),
     * the File menu (New/Open would swap out settings mid-build), and
     * Validate/Run Build/Save Config. Cancel is the only thing left enabled.
     */
    private void setBuildRunning(boolean running) {
        tabs.setEnabled(!running);
        fileMenu.setEnabled(!running);
        settingsMenu.setEnabled(!running);
        runBuildButton.setEnabled(!running);
        uploadNowButton.setEnabled(!running);
        uploadHexFileButton.setEnabled(!running);
        forceRebuildHeaderIndexButton.setEnabled(!running);
        forceCleanRebuildButton.setEnabled(!running);
        forceRecompileButton.setEnabled(!running);
        validateButton.setEnabled(!running);
        saveConfigButton.setEnabled(!running);
        cancelBuildButton.setEnabled(running);
        if (running) {
            logStatusLabel.setText("Running\u2026");
        }
    }

    // ------------------------------------------------------------------
    // Enablement wiring (checkbox -> dependent fields)
    // ------------------------------------------------------------------

    private void wireContextMenus() {
        installEditPopup(arduinoCliField);
        installEditPopup(configFileField);
        installEditPopup(fastbuildExeField);
        installEditPopup(sketchField);
        installEditPopup(fqbnField);
        installEditPopup(logDirField);
        installEditPopup(buildPropsArea);
        if (platformVersionCombo.getEditor().getEditorComponent() instanceof JTextComponent) {
            installEditPopup((JTextComponent) platformVersionCombo.getEditor().getEditorComponent());
        }
        installEditPopup(boardFilterField);
        installEditPopup(fqbnPreviewField);
        installEditPopup(daemonAddrField);
        installEditPopup(connectAddrField);
        installEditPopup(watchIntervalField);
        installEditPopup(logArea);
    }

    private void wireEnablement() {
        saveLogCheck.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                logDirField.setEnabled(saveLogCheck.isSelected());
            }
        });
        uploadCheck.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                portField.setEnabled(uploadCheck.isSelected());
            }
        });
        exportCheck.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                exportConflictCombo.setEnabled(exportCheck.isSelected() && !alwaysReplaceOutputCheck.isSelected());
                openSketchFolderButton.setVisible(exportCheck.isSelected());
            }
        });
        alwaysReplaceOutputCheck.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (alwaysReplaceOutputCheck.isSelected()) {
                    exportConflictCombo.setSelectedItem("overwrite");
                } else {
                    exportConflictCombo.setSelectedItem("rename");
                }
                exportConflictCombo.setEnabled(exportCheck.isSelected() && !alwaysReplaceOutputCheck.isSelected());
            }
        });
        daemonCheck.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                daemonAddrField.setEnabled(daemonCheck.isSelected());
                daemonStaleDepsPolicyCombo.setEnabled(daemonCheck.isSelected());
            }
        });
        connectCheck.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                connectAddrField.setEnabled(connectCheck.isSelected());
            }
        });
        watchCheck.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                watchIntervalField.setEnabled(watchCheck.isSelected());
            }
        });
    }

    // ------------------------------------------------------------------
    // Model <-> UI synchronization
    // ------------------------------------------------------------------

    private void loadFromSettings(BuildSettings s) {
        loadingSettings = true;

        arduinoCliField.setText(s.arduinoCli);
        configFileField.setText(s.configFile);
        sketchField.setText(s.sketch);
        fqbnField.setText(s.fqbn);
        verboseCheck.setSelected(s.verbose);

        hashLibraryHeadersCheck.setSelected(s.hashLibraryHeaders);
        hashToolchainCheck.setSelected(s.hashToolchain);
        depsModeCombo.setSelectedItem(s.depsMode.configValue);
        restorePlatformVersionSelection(s.platformVersion);
        gccInjectMMDCheck.setSelected(s.gccInjectMMD);
        depsIndexMaxAgeSpinner.setValue(s.depsIndexMaxAgeHours);

        forceCheck.setSelected(s.force);
        cleanCheck.setSelected(s.clean);
        noDepsCheck.setSelected(s.noDeps);
        noToolchainCheck.setSelected(s.noToolchain);
        refreshDepsIndexCheck.setSelected(s.refreshDepsIndex);
        assumeYesStaleDepsCheck.setSelected(s.assumeYesStaleDeps);
        skipStaleDepsRefreshCheck.setSelected(s.skipStaleDepsRefresh);

        showStatsCheck.setSelected(s.showStats);
        jsonOutputCheck.setSelected(s.jsonOutput);
        saveLogCheck.setSelected(s.saveLog);
        logDirField.setText(s.logDir);
        logDirField.setEnabled(s.saveLog);
        uploadCheck.setSelected(s.upload);
        portField.getEditor().setItem(s.port);
        portField.setEnabled(s.upload);
        hexFileField.setText(s.uploadHexFile);
        exportCheck.setSelected(s.export);
        exportConflictCombo.setSelectedItem(s.exportConflict.configValue);
        alwaysReplaceOutputCheck.setSelected(s.exportConflict == BuildSettings.ExportConflict.OVERWRITE);
        exportConflictCombo.setEnabled(s.export && s.exportConflict != BuildSettings.ExportConflict.OVERWRITE);
        openSketchFolderButton.setVisible(s.export);
        buildPropsArea.setText(joinLines(s.buildProps));

        refreshWizardCacheCheck.setSelected(s.refreshWizardCache);
        wizardPrefetchCombo.setSelectedItem(s.wizardPrefetch.flagValue);
        wizardPrefetchWorkersSpinner.setValue(s.wizardPrefetchWorkers);

        daemonCheck.setSelected(s.daemon);
        daemonAddrField.setText(s.daemonAddr);
        daemonAddrField.setEnabled(s.daemon);
        daemonStaleDepsPolicyCombo.setSelectedItem(s.daemonStaleDepsPolicy.flagValue);
        daemonStaleDepsPolicyCombo.setEnabled(s.daemon);
        connectCheck.setSelected(s.connect);
        connectAddrField.setText(s.connectAddr);
        connectAddrField.setEnabled(s.connect);

        watchCheck.setSelected(s.watch);
        watchIntervalField.setText(s.watchInterval);
        watchIntervalField.setEnabled(s.watch);

        loadingSettings = false;
        refreshAppSettingsMirrors();
    }

    private BuildSettings readSettingsFromUI() {
        BuildSettings s = new BuildSettings();
        s.arduinoCli = arduinoCliField.getText().trim();
        s.sketch = sketchField.getText().trim();
        s.fqbn = fqbnField.getText().trim();
        s.configFile = configFileField.getText().trim();
        s.cacheRoot = cacheRootField.getText().trim();
        s.verbose = verboseCheck.isSelected();

        s.hashLibraryHeaders = hashLibraryHeadersCheck.isSelected();
        s.hashToolchain = hashToolchainCheck.isSelected();
        s.depsMode = "depfile".equals(depsModeCombo.getSelectedItem()) ? BuildSettings.DepsMode.DEPFILE : BuildSettings.DepsMode.REGEX;
        s.platformVersion = readPlatformVersionSelection();
        s.gccInjectMMD = gccInjectMMDCheck.isSelected();
        s.depsIndexMaxAgeHours = (Integer) depsIndexMaxAgeSpinner.getValue();

        s.force = forceCheck.isSelected();
        s.clean = cleanCheck.isSelected();
        s.noDeps = noDepsCheck.isSelected();
        s.noToolchain = noToolchainCheck.isSelected();
        s.refreshDepsIndex = refreshDepsIndexCheck.isSelected();
        s.assumeYesStaleDeps = assumeYesStaleDepsCheck.isSelected();
        s.skipStaleDepsRefresh = skipStaleDepsRefreshCheck.isSelected();

        s.showStats = showStatsCheck.isSelected();
        s.jsonOutput = jsonOutputCheck.isSelected();
        s.saveLog = saveLogCheck.isSelected();
        s.logDir = logDirField.getText().trim();
        s.upload = uploadCheck.isSelected();
        Object rawPort = portField.getEditor().getItem();
        s.port = systemPortNameOnly(rawPort == null ? "" : rawPort.toString());
        s.uploadHexFile = hexFileField.getText().trim();
        s.export = exportCheck.isSelected();
        s.exportConflict = comboToExportConflict(exportConflictCombo.getSelectedItem());
        s.buildProps = splitLines(buildPropsArea.getText());

        s.wizardCacheDir = wizardCacheDirField.getText().trim();
        s.refreshWizardCache = refreshWizardCacheCheck.isSelected();
        s.wizardPrefetch = comboToWizardPrefetch(wizardPrefetchCombo.getSelectedItem());
        s.wizardPrefetchWorkers = (Integer) wizardPrefetchWorkersSpinner.getValue();

        s.daemon = daemonCheck.isSelected();
        s.daemonAddr = daemonAddrField.getText().trim();
        s.daemonStaleDepsPolicy = "refresh".equals(daemonStaleDepsPolicyCombo.getSelectedItem())
                ? BuildSettings.DaemonStaleDepsPolicy.REFRESH : BuildSettings.DaemonStaleDepsPolicy.SKIP;
        s.connect = connectCheck.isSelected();
        s.connectAddr = connectAddrField.getText().trim();

        s.watch = watchCheck.isSelected();
        s.watchInterval = watchIntervalField.getText().trim();

        return s;
    }

    private static BuildSettings.ExportConflict comboToExportConflict(Object item) {
        if ("overwrite".equals(item)) {
            return BuildSettings.ExportConflict.OVERWRITE;
        }
        if ("rename".equals(item)) {
            return BuildSettings.ExportConflict.RENAME;
        }
        return BuildSettings.ExportConflict.ASK;
    }

    private static BuildSettings.WizardPrefetch comboToWizardPrefetch(Object item) {
        if ("full".equals(item)) {
            return BuildSettings.WizardPrefetch.FULL;
        }
        if ("off".equals(item)) {
            return BuildSettings.WizardPrefetch.OFF;
        }
        return BuildSettings.WizardPrefetch.ASK;
    }

    private static String joinLines(java.util.List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static java.util.List<String> splitLines(String text) {
        java.util.List<String> result = new java.util.ArrayList<String>();
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Layout helpers
    // ------------------------------------------------------------------

    /**
     * A form panel that tracks its scroll viewport's width instead of
     * keeping its own preferred width. Without this, a JScrollPane gives the
     * panel a horizontal scrollbar whenever the window is narrower than the
     * panel's natural width - which is what was clipping the Browse buttons
     * (they'd only fully appear once the window was wide enough, e.g. after
     * maximizing). Tracking the viewport width instead makes the
     * GridBagLayout's stretchy columns (the text fields, weightx=1.0)
     * shrink to fit, so fixed-width things like buttons stay fully visible
     * at any window size and only vertical scrolling is ever needed.
     */
    private static class FormPanel extends JPanel implements Scrollable {
        FormPanel() {
            super(new GridBagLayout());
        }

        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 96;
        }

        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Right-click context menu (Cut/Copy/Paste/Select All) for text fields
    // ------------------------------------------------------------------

    private static void installEditPopup(final JTextComponent field) {
        final JMenuItem cutItem = new JMenuItem("Cut");
        final JMenuItem copyItem = new JMenuItem("Copy");
        final JMenuItem pasteItem = new JMenuItem("Paste");
        final JMenuItem selectAllItem = new JMenuItem("Select All");

        cutItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                field.cut();
            }
        });
        copyItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                field.copy();
            }
        });
        pasteItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                field.paste();
            }
        });
        selectAllItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                field.selectAll();
            }
        });

        final JPopupMenu menu = new JPopupMenu();
        menu.add(cutItem);
        menu.add(copyItem);
        menu.add(pasteItem);
        menu.addSeparator();
        menu.add(selectAllItem);
        menu.addPopupMenuListener(new PopupMenuListener() {
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                boolean editable = field.isEditable() && field.isEnabled();
                boolean hasSelection = field.getSelectedText() != null;
                cutItem.setEnabled(editable && hasSelection);
                copyItem.setEnabled(hasSelection);
                pasteItem.setEnabled(editable);
                selectAllItem.setEnabled(field.getDocument().getLength() > 0);
            }

            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        field.setComponentPopupMenu(menu);
    }

    private static JPanel newFormPanel() {
        JPanel panel = new FormPanel();
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        return panel;
    }

    private static JPanel titled(String title) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title, TitledBorder.LEADING, TitledBorder.TOP));
        return panel;
    }

    private static JScrollPane wrapScroll(JPanel panel) {
        JScrollPane scroll = new JScrollPane(panel);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
    }

    /** A "Label: value" row combined into one component, for read-only summaries. */
    private static JPanel labelRow(String labelText, JLabel valueLabel) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JLabel label = new JLabel(labelText);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        row.add(label);
        row.add(valueLabel);
        return row;
    }

    /**
     * Adds a label + editable field row, returns the next free row index.
     */
    private static int addRow(JPanel panel, int row, String labelText, JComponent field) {
        GridBagConstraints labelGbc = new GridBagConstraints();
        labelGbc.gridx = 0;
        labelGbc.gridy = row;
        labelGbc.anchor = GridBagConstraints.WEST;
        labelGbc.insets = new Insets(4, 4, 4, 8);
        panel.add(new JLabel(labelText), labelGbc);

        GridBagConstraints fieldGbc = new GridBagConstraints();
        fieldGbc.gridx = 1;
        fieldGbc.gridy = row;
        fieldGbc.weightx = 1.0;
        fieldGbc.fill = GridBagConstraints.HORIZONTAL;
        fieldGbc.insets = new Insets(4, 4, 4, 4);
        fieldGbc.gridwidth = 2;
        panel.add(field, fieldGbc);

        return row + 1;
    }

    /**
     * Adds a label + path field + Browse button row. isDirectory picks file vs
     * folder chooser.
     */
    private int addPathRow(JPanel panel, int row, String labelText, final JTextField field, final boolean isDirectory) {
        return addPathRow(panel, row, labelText, field, isDirectory, null);
    }

    /** Same as above, but runs afterBrowse (if non-null) right after Browse sets a new path. */
    private int addPathRow(JPanel panel, int row, String labelText, final JTextField field, final boolean isDirectory,
            final Runnable afterBrowse) {
        GridBagConstraints labelGbc = new GridBagConstraints();
        labelGbc.gridx = 0;
        labelGbc.gridy = row;
        labelGbc.anchor = GridBagConstraints.WEST;
        labelGbc.insets = new Insets(4, 4, 4, 8);
        panel.add(new JLabel(labelText), labelGbc);

        GridBagConstraints fieldGbc = new GridBagConstraints();
        fieldGbc.gridx = 1;
        fieldGbc.gridy = row;
        fieldGbc.weightx = 1.0;
        fieldGbc.fill = GridBagConstraints.HORIZONTAL;
        fieldGbc.insets = new Insets(4, 4, 4, 4);
        panel.add(field, fieldGbc);

        JButton browse = new JButton("Browse\u2026");
        browse.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                JFileChooser chooser = new JFileChooser();
                if (isDirectory) {
                    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                }
                String existing = field.getText().trim();
                if (!existing.isEmpty()) {
                    File f = new File(existing);
                    if (f.getParentFile() != null && f.getParentFile().exists()) {
                        chooser.setCurrentDirectory(f.getParentFile());
                    }
                }
                int result = chooser.showOpenDialog(field.getTopLevelAncestor());
                if (result == JFileChooser.APPROVE_OPTION) {
                    field.setText(chooser.getSelectedFile().getAbsolutePath());
                    if (afterBrowse != null) {
                        afterBrowse.run();
                    }
                }
            }
        });
        GridBagConstraints buttonGbc = new GridBagConstraints();
        buttonGbc.gridx = 2;
        buttonGbc.gridy = row;
        buttonGbc.insets = new Insets(4, 4, 4, 4);
        panel.add(browse, buttonGbc);

        return row + 1;
    }

    /**
     * Adds a component spanning the full row width (e.g. a checkbox or a note
     * label).
     */
    private static int addFullWidth(JPanel panel, int row, JComponent component) {
        return addFullWidthComponent(panel, row, component);
    }

    private static int addFullWidthComponent(JPanel panel, int row, JComponent component) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(4, 4, 4, 4);
        panel.add(component, gbc);
        return row + 1;
    }

    private static void addVerticalGlue(JPanel panel, int row) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 3;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        panel.add(Box.createVerticalGlue(), gbc);
    }
}
