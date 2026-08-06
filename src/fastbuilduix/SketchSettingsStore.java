package fastbuilduix;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers everything needed to rebuild the exact same sketch again without
 * re-entering it: the full BuildSettings, plus the Board Wizard's selection
 * (which platform/board/menu-options were picked - not just the resulting
 * FQBN string), since re-deriving those from a bare FQBN isn't reliable.
 *
 * Saved to a fixed file name sitting right next to the sketch itself (same
 * directory), so the same sketch always finds its own settings regardless of
 * which computer or working directory the UI is run from - unlike
 * ProjectSettingsStore's single fixed-location file, which only remembers
 * "whatever was last open" for THIS app instance.
 */
final class SketchSettingsStore {

    private SketchSettingsStore() {
    }

    static final String CACHE_FILE_NAME = "fastbuild-ui-cache.json";

    /** The cache file that lives alongside the given sketch, or null if sketchPath doesn't point anywhere sensible. */
    static File cacheFileFor(String sketchPath) {
        if (sketchPath == null || sketchPath.trim().isEmpty()) {
            return null;
        }
        File sketchFile = new File(sketchPath.trim());
        File parent = sketchFile.getParentFile();
        if (parent == null) {
            return null;
        }
        return new File(parent, CACHE_FILE_NAME);
    }

    static class Loaded {
        final BuildSettings settings;
        final String platformId;
        final String boardFqbn;
        final Map<String, String> optionValues;
        final String lastFlashUsageText;
        final String lastRamUsageText;

        Loaded(BuildSettings settings, String platformId, String boardFqbn, Map<String, String> optionValues,
                String lastFlashUsageText, String lastRamUsageText) {
            this.settings = settings;
            this.platformId = platformId;
            this.boardFqbn = boardFqbn;
            this.optionValues = optionValues;
            this.lastFlashUsageText = lastFlashUsageText;
            this.lastRamUsageText = lastRamUsageText;
        }
    }

    @SuppressWarnings("unchecked")
    static Loaded load(File file) throws IOException, MiniJson.JsonParseException {
        String text = readFile(file);
        Object root = MiniJson.parse(text);
        if (!(root instanceof Map)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) root;

        Object rawSettings = map.get("buildSettings");
        BuildSettings settings = (rawSettings instanceof Map)
                ? ProjectSettingsStore.fromJson((Map<String, Object>) rawSettings)
                : new BuildSettings();

        String platformId = null;
        String boardFqbn = null;
        Map<String, String> optionValues = new LinkedHashMap<String, String>();

        Object rawWizard = map.get("wizardSelection");
        if (rawWizard instanceof Map) {
            Map<String, Object> wizardMap = (Map<String, Object>) rawWizard;
            Object pid = wizardMap.get("platformId");
            if (pid != null && !String.valueOf(pid).isEmpty()) {
                platformId = String.valueOf(pid);
            }
            Object bfqbn = wizardMap.get("boardFqbn");
            if (bfqbn != null && !String.valueOf(bfqbn).isEmpty()) {
                boardFqbn = String.valueOf(bfqbn);
            }
            Object rawValues = wizardMap.get("optionValues");
            if (rawValues instanceof Map) {
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) rawValues).entrySet()) {
                    if (entry.getValue() != null) {
                        optionValues.put(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                }
            }
        }

        // A cache hit means fastbuild reused the exact same binary as last time (that's
        // what "cache hit" means), so the flash/RAM figures from that last real compile are
        // still 100% accurate on a cache-hit run, not stale - arduino-cli just never gets
        // invoked to reprint them. Remembering them here means a cache hit on a fresh app
        // launch can still show real numbers immediately, instead of "-" until some future
        // real compile happens to occur.
        String lastFlashUsageText = null;
        String lastRamUsageText = null;
        Object rawUsage = map.get("lastKnownUsage");
        if (rawUsage instanceof Map) {
            Map<String, Object> usageMap = (Map<String, Object>) rawUsage;
            Object flash = usageMap.get("flash");
            if (flash != null && !String.valueOf(flash).isEmpty()) {
                lastFlashUsageText = String.valueOf(flash);
            }
            Object ram = usageMap.get("ram");
            if (ram != null && !String.valueOf(ram).isEmpty()) {
                lastRamUsageText = String.valueOf(ram);
            }
        }

        return new Loaded(settings, platformId, boardFqbn, optionValues, lastFlashUsageText, lastRamUsageText);
    }

    static void save(BuildSettings settings, String platformId, String boardFqbn,
            Map<String, String> optionValues, String lastFlashUsageText, String lastRamUsageText, File file) throws IOException {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("buildSettings", ProjectSettingsStore.toJson(settings));

        Map<String, Object> wizardMap = new LinkedHashMap<String, Object>();
        wizardMap.put("platformId", platformId == null ? "" : platformId);
        wizardMap.put("boardFqbn", boardFqbn == null ? "" : boardFqbn);
        Map<String, Object> valuesMap = new LinkedHashMap<String, Object>();
        if (optionValues != null) {
            for (Map.Entry<String, String> entry : optionValues.entrySet()) {
                valuesMap.put(entry.getKey(), entry.getValue());
            }
        }
        wizardMap.put("optionValues", valuesMap);
        root.put("wizardSelection", wizardMap);

        Map<String, Object> usageMap = new LinkedHashMap<String, Object>();
        usageMap.put("flash", lastFlashUsageText == null ? "" : lastFlashUsageText);
        usageMap.put("ram", lastRamUsageText == null ? "" : lastRamUsageText);
        root.put("lastKnownUsage", usageMap);

        String json = MiniJson.write(root);
        FileWriter writer = new FileWriter(file);
        try {
            writer.write(json);
        } finally {
            writer.close();
        }
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
