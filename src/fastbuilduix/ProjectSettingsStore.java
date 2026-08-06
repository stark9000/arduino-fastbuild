package fastbuilduix;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves/loads the *entire* BuildSettings (every tab, not just App Settings)
 * to a single JSON file in the app's working directory - no file-chooser
 * dialog, always the same fixed location, unlike File > Save/Save As which
 * still writes the real fastbuild ".config" key=value format for actual
 * interop with the fastbuild CLI. This file is purely a UI convenience: "what
 * was I working on last" persisted automatically, independent of whether
 * you've ever explicitly saved a named .config file.
 */
public final class ProjectSettingsStore {

    private ProjectSettingsStore() {
    }

    public static final String DEFAULT_FILE_NAME = "fastbuild-ui-project-settings.json";

    public static File defaultFile() {
        return new File(System.getProperty("user.dir", "."), DEFAULT_FILE_NAME);
    }

    public static BuildSettings load(File file) throws IOException, MiniJson.JsonParseException {
        if (!file.exists()) {
            return new BuildSettings();
        }
        String text = readFile(file);
        Object root = MiniJson.parse(text);
        if (!(root instanceof Map)) {
            return new BuildSettings();
        }
        return fromJson((Map<String, Object>) root);
    }

    public static void save(BuildSettings s, File file) throws IOException {
        String json = MiniJson.write(toJson(s));
        FileWriter writer = new FileWriter(file);
        try {
            writer.write(json);
        } finally {
            writer.close();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toJson(BuildSettings s) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("arduinoCli", s.arduinoCli);
        m.put("sketch", s.sketch);
        m.put("fqbn", s.fqbn);
        m.put("configFile", s.configFile);
        m.put("cacheRoot", s.cacheRoot);
        m.put("buildProps", new ArrayList<Object>(s.buildProps));
        m.put("verbose", s.verbose);
        m.put("hashLibraryHeaders", s.hashLibraryHeaders);
        m.put("hashToolchain", s.hashToolchain);
        m.put("depsMode", s.depsMode.configValue);
        m.put("platformVersion", s.platformVersion);
        m.put("gccInjectMMD", s.gccInjectMMD);
        m.put("depsIndexMaxAgeHours", s.depsIndexMaxAgeHours);
        m.put("showStats", s.showStats);
        m.put("jsonOutput", s.jsonOutput);
        m.put("saveLog", s.saveLog);
        m.put("logDir", s.logDir);
        m.put("force", s.force);
        m.put("clean", s.clean);
        m.put("upload", s.upload);
        m.put("port", s.port);
        m.put("export", s.export);
        m.put("exportConflict", s.exportConflict.configValue);
        m.put("noDeps", s.noDeps);
        m.put("noToolchain", s.noToolchain);
        m.put("refreshDepsIndex", s.refreshDepsIndex);
        m.put("assumeYesStaleDeps", s.assumeYesStaleDeps);
        m.put("skipStaleDepsRefresh", s.skipStaleDepsRefresh);
        m.put("uploadHexFile", s.uploadHexFile);
        m.put("wizardCacheDir", s.wizardCacheDir);
        m.put("refreshWizardCache", s.refreshWizardCache);
        m.put("wizardPrefetch", s.wizardPrefetch.flagValue);
        m.put("wizardPrefetchWorkers", s.wizardPrefetchWorkers);
        m.put("daemon", s.daemon);
        m.put("daemonAddr", s.daemonAddr);
        m.put("daemonStaleDepsPolicy", s.daemonStaleDepsPolicy.flagValue);
        m.put("connect", s.connect);
        m.put("connectAddr", s.connectAddr);
        m.put("watch", s.watch);
        m.put("watchInterval", s.watchInterval);
        return m;
    }

    @SuppressWarnings("unchecked")
    static BuildSettings fromJson(Map<String, Object> m) {
        BuildSettings s = new BuildSettings();
        s.arduinoCli = str(m, "arduinoCli", s.arduinoCli);
        s.sketch = str(m, "sketch", s.sketch);
        s.fqbn = str(m, "fqbn", s.fqbn);
        s.configFile = str(m, "configFile", s.configFile);
        s.cacheRoot = str(m, "cacheRoot", s.cacheRoot);

        s.buildProps = new ArrayList<String>();
        Object rawProps = m.get("buildProps");
        if (rawProps instanceof List) {
            for (Object p : (List<Object>) rawProps) {
                if (p != null) {
                    s.buildProps.add(String.valueOf(p));
                }
            }
        }

        s.verbose = bool(m, "verbose", s.verbose);
        s.hashLibraryHeaders = bool(m, "hashLibraryHeaders", s.hashLibraryHeaders);
        s.hashToolchain = bool(m, "hashToolchain", s.hashToolchain);
        s.depsMode = "depfile".equals(m.get("depsMode")) ? BuildSettings.DepsMode.DEPFILE : BuildSettings.DepsMode.REGEX;
        s.platformVersion = str(m, "platformVersion", s.platformVersion);
        s.gccInjectMMD = bool(m, "gccInjectMMD", s.gccInjectMMD);
        s.depsIndexMaxAgeHours = intVal(m, "depsIndexMaxAgeHours", s.depsIndexMaxAgeHours);
        s.showStats = bool(m, "showStats", s.showStats);
        s.jsonOutput = bool(m, "jsonOutput", s.jsonOutput);
        s.saveLog = bool(m, "saveLog", s.saveLog);
        s.logDir = str(m, "logDir", s.logDir);
        s.force = bool(m, "force", s.force);
        s.clean = bool(m, "clean", s.clean);
        s.upload = bool(m, "upload", s.upload);
        s.port = str(m, "port", s.port);
        s.export = bool(m, "export", s.export);

        Object ec = m.get("exportConflict");
        if ("overwrite".equals(ec)) {
            s.exportConflict = BuildSettings.ExportConflict.OVERWRITE;
        } else if ("rename".equals(ec)) {
            s.exportConflict = BuildSettings.ExportConflict.RENAME;
        } else {
            s.exportConflict = BuildSettings.ExportConflict.ASK;
        }

        s.noDeps = bool(m, "noDeps", s.noDeps);
        s.noToolchain = bool(m, "noToolchain", s.noToolchain);
        s.refreshDepsIndex = bool(m, "refreshDepsIndex", s.refreshDepsIndex);
        s.assumeYesStaleDeps = bool(m, "assumeYesStaleDeps", s.assumeYesStaleDeps);
        s.skipStaleDepsRefresh = bool(m, "skipStaleDepsRefresh", s.skipStaleDepsRefresh);
        s.uploadHexFile = str(m, "uploadHexFile", s.uploadHexFile);

        s.wizardCacheDir = str(m, "wizardCacheDir", s.wizardCacheDir);
        s.refreshWizardCache = bool(m, "refreshWizardCache", s.refreshWizardCache);
        Object wp = m.get("wizardPrefetch");
        if ("full".equals(wp)) {
            s.wizardPrefetch = BuildSettings.WizardPrefetch.FULL;
        } else if ("off".equals(wp)) {
            s.wizardPrefetch = BuildSettings.WizardPrefetch.OFF;
        } else {
            s.wizardPrefetch = BuildSettings.WizardPrefetch.ASK;
        }
        s.wizardPrefetchWorkers = intVal(m, "wizardPrefetchWorkers", s.wizardPrefetchWorkers);

        s.daemon = bool(m, "daemon", s.daemon);
        s.daemonAddr = str(m, "daemonAddr", s.daemonAddr);
        s.daemonStaleDepsPolicy = "refresh".equals(m.get("daemonStaleDepsPolicy"))
                ? BuildSettings.DaemonStaleDepsPolicy.REFRESH : BuildSettings.DaemonStaleDepsPolicy.SKIP;
        s.connect = bool(m, "connect", s.connect);
        s.connectAddr = str(m, "connectAddr", s.connectAddr);
        s.watch = bool(m, "watch", s.watch);
        s.watchInterval = str(m, "watchInterval", s.watchInterval);

        return s;
    }

    private static String str(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static boolean bool(Map<String, Object> m, String key, boolean fallback) {
        Object v = m.get(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    private static int intVal(Map<String, Object> m, String key, int fallback) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    private static String readFile(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new FileReader(f));
        try {
            char[] buf = new char[4096];
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
