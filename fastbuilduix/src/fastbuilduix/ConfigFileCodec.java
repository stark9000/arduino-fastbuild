package fastbuilduix;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes fastbuild's plain "key=value" config file format.
 *
 * This mirrors parseConfig() in fastbuild.go line for line so a file saved
 * here is byte-compatible with what the Go binary expects, and a file
 * written by fastbuild -gui (or hand-edited) round-trips cleanly through
 * this UI too:
 *
 *   - blank lines and lines starting with '#' are ignored
 *   - each remaining line is split on the FIRST '=' only
 *   - unknown keys are silently ignored (forward-compatible)
 *   - hashLibraryHeaders / hashToolchain default to true unless the value
 *     is literally "false" (NOT the usual "only true if value == true")
 *   - exportConflict / depsMode are validated against a fixed set of
 *     values and throw on anything else, including a bad depsIndexMaxAgeHours
 *   - buildProps is "|"-delimited
 */
public final class ConfigFileCodec {

    private ConfigFileCodec() {
    }

    /** Thrown when the config text fails the same validation fastbuild.go applies. */
    public static class ConfigFormatException extends Exception {
        public ConfigFormatException(String message) {
            super(message);
        }
    }

    public static BuildSettings load(File file) throws IOException, ConfigFormatException {
        BufferedReader reader = new BufferedReader(new FileReader(file));
        try {
            return parse(reader);
        } finally {
            reader.close();
        }
    }

    public static BuildSettings parse(String text) throws ConfigFormatException {
        try {
            return parse(new BufferedReader(new StringReader(text)));
        } catch (IOException e) {
            // StringReader never actually throws IOException, but parse()'s
            // signature allows for it since it also accepts file readers.
            throw new ConfigFormatException(e.getMessage());
        }
    }

    private static BuildSettings parse(BufferedReader reader) throws IOException, ConfigFormatException {
        Map<String, String> values = new LinkedHashMap<String, String>();
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            values.put(key, value);
        }

        BuildSettings cfg = new BuildSettings();
        cfg.arduinoCli = values.containsKey("arduinoCLI") ? values.get("arduinoCLI") : "";
        cfg.configFile = values.containsKey("configFile") ? values.get("configFile") : "";
        cfg.sketch = values.containsKey("sketch") ? values.get("sketch") : "";
        cfg.fqbn = values.containsKey("fqbn") ? values.get("fqbn") : "";
        cfg.cacheRoot = values.containsKey("cacheRoot") ? values.get("cacheRoot") : "";
        cfg.verbose = "true".equals(values.get("verbose"));
        // Inverted default: absent or anything other than "false" means true.
        cfg.hashLibraryHeaders = !"false".equals(values.get("hashLibraryHeaders"));
        cfg.hashToolchain = !"false".equals(values.get("hashToolchain"));
        cfg.showStats = "true".equals(values.get("showStats"));
        cfg.force = "true".equals(values.get("force"));
        cfg.clean = "true".equals(values.get("clean"));
        cfg.upload = "true".equals(values.get("upload"));
        cfg.port = values.containsKey("port") ? values.get("port") : "";
        cfg.export = "true".equals(values.get("export"));

        String exportConflictRaw = values.containsKey("exportConflict") ? values.get("exportConflict") : "";
        String exportConflictNorm = exportConflictRaw.trim().toLowerCase();
        if (exportConflictNorm.isEmpty() || "ask".equals(exportConflictNorm)) {
            cfg.exportConflict = BuildSettings.ExportConflict.ASK;
        } else if ("overwrite".equals(exportConflictNorm)) {
            cfg.exportConflict = BuildSettings.ExportConflict.OVERWRITE;
        } else if ("rename".equals(exportConflictNorm)) {
            cfg.exportConflict = BuildSettings.ExportConflict.RENAME;
        } else {
            throw new ConfigFormatException(
                    "exportConflict must be one of ask, overwrite, rename (got \"" + exportConflictRaw + "\")");
        }

        String depsModeRaw = values.containsKey("depsMode") ? values.get("depsMode") : "";
        String depsModeNorm = depsModeRaw.trim().toLowerCase();
        if (depsModeNorm.isEmpty() || "regex".equals(depsModeNorm)) {
            cfg.depsMode = BuildSettings.DepsMode.REGEX;
        } else if ("depfile".equals(depsModeNorm) || "gcc".equals(depsModeNorm)) {
            cfg.depsMode = BuildSettings.DepsMode.DEPFILE;
        } else {
            throw new ConfigFormatException(
                    "depsMode must be 'regex' or 'depfile' (or its alias 'gcc') (got \"" + depsModeRaw + "\")");
        }

        cfg.platformVersion = values.containsKey("platformVersion") ? values.get("platformVersion").trim() : "";

        cfg.gccInjectMMD = "true".equals(values.get("gccInjectMMD"));
        cfg.jsonOutput = "true".equals(values.get("jsonOutput"));
        cfg.saveLog = "true".equals(values.get("saveLog"));
        cfg.logDir = values.containsKey("logDir") ? values.get("logDir") : "";

        cfg.depsIndexMaxAgeHours = 24;
        if (values.containsKey("depsIndexMaxAgeHours")) {
            String raw = values.get("depsIndexMaxAgeHours").trim();
            int hours;
            try {
                hours = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw new ConfigFormatException("depsIndexMaxAgeHours must be an integer (got \"" + raw + "\")");
            }
            cfg.depsIndexMaxAgeHours = Math.max(hours, 0);
        }

        cfg.buildProps.clear();
        if (values.containsKey("buildProps")) {
            String raw = values.get("buildProps");
            if (!raw.isEmpty()) {
                String[] parts = raw.split("\\|", -1);
                for (String p : parts) {
                    p = p.trim();
                    if (!p.isEmpty()) {
                        cfg.buildProps.add(p);
                    }
                }
            }
        }

        // Note: fastbuild.go treats a missing arduinoCLI/sketch/fqbn as a hard
        // load error. The settings UI is deliberately more lenient so a
        // partially-filled-in config can still be opened and completed here;
        // use BuildSettings.validateRequired() before handing a config off to
        // an actual build.
        if (cfg.cacheRoot == null || cfg.cacheRoot.trim().isEmpty()) {
            cfg.cacheRoot = BuildSettings.defaultCacheRoot();
        }

        return cfg;
    }

    /** Renders settings back into fastbuild's config file text format. */
    public static String write(BuildSettings cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("# fastbuild config file - written by the fastbuild Java UI.\n");
        sb.append("\n");
        sb.append("# --- Required ---\n");
        sb.append("arduinoCLI=").append(nullToEmpty(cfg.arduinoCli)).append("\n");
        sb.append("sketch=").append(nullToEmpty(cfg.sketch)).append("\n");
        sb.append("fqbn=").append(nullToEmpty(cfg.fqbn)).append("\n");
        sb.append("\n");
        sb.append("# --- Recommended ---\n");
        sb.append("configFile=").append(nullToEmpty(cfg.configFile)).append("\n");
        sb.append("cacheRoot=").append(nullToEmpty(cfg.cacheRoot)).append("\n");
        sb.append("\n");
        sb.append("# --- Optional ---\n");
        sb.append("buildProps=").append(joinBuildProps(cfg.buildProps)).append("\n");
        sb.append("verbose=").append(cfg.verbose).append("\n");
        sb.append("hashLibraryHeaders=").append(cfg.hashLibraryHeaders).append("\n");
        sb.append("hashToolchain=").append(cfg.hashToolchain).append("\n");
        sb.append("depsMode=").append(cfg.depsMode.configValue).append("\n");
        sb.append("platformVersion=").append(nullToEmpty(cfg.platformVersion)).append("\n");
        sb.append("gccInjectMMD=").append(cfg.gccInjectMMD).append("\n");
        sb.append("depsIndexMaxAgeHours=").append(cfg.depsIndexMaxAgeHours).append("\n");
        sb.append("showStats=").append(cfg.showStats).append("\n");
        sb.append("jsonOutput=").append(cfg.jsonOutput).append("\n");
        sb.append("saveLog=").append(cfg.saveLog).append("\n");
        sb.append("logDir=").append(nullToEmpty(cfg.logDir)).append("\n");
        sb.append("force=").append(cfg.force).append("\n");
        sb.append("clean=").append(cfg.clean).append("\n");
        sb.append("upload=").append(cfg.upload).append("\n");
        sb.append("port=").append(nullToEmpty(cfg.port)).append("\n");
        sb.append("export=").append(cfg.export).append("\n");
        sb.append("exportConflict=").append(cfg.exportConflict.configValue).append("\n");
        return sb.toString();
    }

    public static void save(BuildSettings cfg, File file) throws IOException {
        FileWriter writer = new FileWriter(file);
        try {
            writer.write(write(cfg));
        } finally {
            writer.close();
        }
    }

    private static String joinBuildProps(java.util.List<String> props) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < props.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(props.get(i));
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
