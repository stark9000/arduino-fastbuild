package fastbuilduix;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory model of everything fastbuild can be configured with - both the
 * settings that live in a fastbuild ".config" file (see ConfigFileCodec) and
 * the CLI-only flags that only ever apply to a single run and are never
 * persisted (force, clean, no-deps, daemon/watch/wizard settings, etc).
 *
 * Defaults here mirror the defaults in fastbuild.go / the fastbuild.config
 * example shipped with the tool.
 */
public class BuildSettings {

    // ------------------------------------------------------------------
    // Persisted in the config file - required
    // ------------------------------------------------------------------
    public String arduinoCli = "";   // path to arduino-cli(.exe)
    public String sketch = "";       // path to the .ino sketch file
    public String fqbn = "";         // fully qualified board name

    // ------------------------------------------------------------------
    // Persisted in the config file - recommended
    // ------------------------------------------------------------------
    public String configFile = "";   // path to arduino-cli.yaml (enables dependency-aware hashing)
    public String cacheRoot = "";    // base folder for persistent build cache - blank until the UI fills in a default (App Settings' default, or fastbuild's own home-folder fallback)

    // ------------------------------------------------------------------
    // Persisted in the config file - optional
    // ------------------------------------------------------------------
    public List<String> buildProps = new ArrayList<String>(); // each becomes --build-property key=value
    public boolean verbose = true;

    public boolean hashLibraryHeaders = true; // requires configFile
    public boolean hashToolchain = true;      // requires configFile
    public DepsMode depsMode = DepsMode.REGEX;
    public String platformVersion = ""; // pin an exact installed platform version (e.g. "3.0.2") instead of auto-selecting the highest installed one - blank means auto-select
    public boolean gccInjectMMD = false;      // only relevant when depsMode == DEPFILE
    public int depsIndexMaxAgeHours = 24;     // 0 disables the age check entirely

    public boolean showStats = true;

    public boolean jsonOutput = false;
    public boolean saveLog = true;
    public String logDir = ""; // blank -> "<project>/logs"

    public boolean force = false;
    public boolean clean = false;

    public boolean upload = false;
    public String port = ""; // e.g. COM3, required if upload == true

    public boolean export = true;
    public ExportConflict exportConflict = ExportConflict.RENAME;

    // ------------------------------------------------------------------
    // CLI-only, one-shot flags. Never written to the config file - these
    // override the config for a single run only, same as passing the flag
    // on the command line.
    // ------------------------------------------------------------------
    public boolean noDeps = false;               // -no-deps
    public boolean noToolchain = false;           // -no-toolchain
    public boolean refreshDepsIndex = false;      // -refresh-deps-index
    public boolean assumeYesStaleDeps = false;    // -assume-yes-stale-deps
    public boolean skipStaleDepsRefresh = false;  // -skip-stale-deps-refresh

    // ------------------------------------------------------------------
    // UI-only. Not a fastbuild concept at all (no CLI flag, never written to
    // the config file) - just remembered here since this is already the
    // model everything else's JSON persistence (project-wide and per-sketch)
    // saves/restores wholesale.
    // ------------------------------------------------------------------
    public String uploadHexFile = ""; // path to an arbitrary already-built .hex/.bin to upload directly

    // ------------------------------------------------------------------
    // Board configuration wizard (-configure-board). Doesn't need or use a
    // config file. Its -arduino-cli / -arduino-cli-yaml flags reuse the
    // arduinoCli / configFile fields above (set once on the App Settings
    // tab) rather than keeping a second copy of the same two paths.
    // ------------------------------------------------------------------
    public String wizardCacheDir = ""; // -wizard-cache-dir, blank until the UI fills in a default - same reasoning as cacheRoot above
    public boolean refreshWizardCache = false;     // -refresh-wizard-cache
    public WizardPrefetch wizardPrefetch = WizardPrefetch.ASK; // -wizard-prefetch
    public int wizardPrefetchWorkers = 8;          // -wizard-prefetch-workers

    // ------------------------------------------------------------------
    // Daemon mode
    // ------------------------------------------------------------------
    public boolean daemon = false;                 // -daemon
    public String daemonAddr = "127.0.0.1:9876";   // -daemon-addr
    public DaemonStaleDepsPolicy daemonStaleDepsPolicy = DaemonStaleDepsPolicy.SKIP; // -daemon-stale-deps-policy
    public boolean connect = false;                // -connect (send this build to a running daemon)
    public String connectAddr = "127.0.0.1:9876";

    // ------------------------------------------------------------------
    // Watch mode
    // ------------------------------------------------------------------
    public boolean watch = false;          // -watch
    public String watchInterval = "1s";    // -watch-interval

    // ------------------------------------------------------------------
    // Enums
    // ------------------------------------------------------------------

    public enum DepsMode {
        REGEX("regex"),
        DEPFILE("depfile");

        public final String configValue;

        DepsMode(String configValue) {
            this.configValue = configValue;
        }
    }

    public enum ExportConflict {
        ASK("ask"),
        OVERWRITE("overwrite"),
        RENAME("rename");

        public final String configValue;

        ExportConflict(String configValue) {
            this.configValue = configValue;
        }
    }

    public enum WizardPrefetch {
        ASK("ask"),
        FULL("full"),
        OFF("off");

        public final String flagValue;

        WizardPrefetch(String flagValue) {
            this.flagValue = flagValue;
        }
    }

    public enum DaemonStaleDepsPolicy {
        SKIP("skip"),
        REFRESH("refresh");

        public final String flagValue;

        DaemonStaleDepsPolicy(String flagValue) {
            this.flagValue = flagValue;
        }
    }

    /** Returns the default cache root fastbuild uses when cacheRoot is blank. */
    public static String defaultCacheRoot() {
        String home = System.getProperty("user.home", "");
        return home + java.io.File.separator + ".arduino-fastbuild";
    }

    /**
     * Basic sanity check for the fields fastbuild treats as hard-required
     * (arduinoCLI, sketch, fqbn). Returns a human-readable problem
     * description, or null if everything required is present.
     */
    public String validateRequired() {
        List<String> missing = new ArrayList<String>();
        if (arduinoCli == null || arduinoCli.trim().isEmpty()) missing.add("Arduino CLI path");
        if (sketch == null || sketch.trim().isEmpty()) missing.add("Sketch (.ino) path");
        if (fqbn == null || fqbn.trim().isEmpty()) missing.add("FQBN");
        if (missing.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("Missing required field(s): ");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(missing.get(i));
        }
        return sb.toString();
    }
}
