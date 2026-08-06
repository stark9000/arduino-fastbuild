package fastbuilduix;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches (live or cached) the data the Board Wizard needs, and assembles the
 * final FQBN from selected menu options. This is the Java equivalent of
 * board_wizard.go's text/JSON parsing plus board_wizard_cache.go's
 * signature-based cache - written to produce and consume the *same*
 * board-wizard-cache.json shape fastbuild's own -configure-board uses, so
 * either tool warms the cache for the other.
 *
 * Every method that shells out to arduino-cli is blocking and must only be
 * called from a background thread (SwingWorker), never the EDT.
 */
final class WizardDataService {

    private WizardDataService() {
    }

    // ------------------------------------------------------------------
    // arduino-cli text output parsing
    // ------------------------------------------------------------------

    /**
     * Parses `arduino-cli core list`'s plain-text table:
     *   ID                       Installed  Latest     Name
     *   esp8266:esp8266          3.0.2      3.0.2      ESP8266 Boards (3.0.2)
     */
    static List<InstalledPlatform> parseCoreList(String output) {
        List<InstalledPlatform> result = new ArrayList<InstalledPlatform>();
        Pattern linePattern = Pattern.compile("^(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(.+)$");
        String[] lines = output.split("\r?\n", -1);
        boolean firstLine = true;
        for (String line : lines) {
            if (firstLine) {
                firstLine = false;
                continue; // header row
            }
            if (line.trim().isEmpty()) {
                continue;
            }
            Matcher m = linePattern.matcher(line);
            if (!m.matches()) {
                continue;
            }
            result.add(new InstalledPlatform(m.group(1), m.group(4)));
        }
        return result;
    }

    /**
     * Parses `arduino-cli board listall`'s plain-text table:
     *   Board Name              FQBN
     *   Arduino MKR FOX 1200    arduino:samd:mkrfox1200
     * FQBNs never contain spaces, so the last whitespace-delimited token is
     * reliably the FQBN regardless of how many words are in the board name.
     */
    static List<BoardEntry> parseBoardListAll(String output) {
        List<BoardEntry> result = new ArrayList<BoardEntry>();
        String[] lines = output.split("\r?\n", -1);
        boolean firstLine = true;
        for (String line : lines) {
            if (firstLine) {
                firstLine = false;
                continue; // header row
            }
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 2 || (fields.length == 1 && fields[0].isEmpty())) {
                continue;
            }
            String fqbn = fields[fields.length - 1];
            String name = line;
            int idx = name.lastIndexOf(fqbn);
            if (idx >= 0 && idx + fqbn.length() == name.length()) {
                name = name.substring(0, idx);
            }
            name = name.trim();
            result.add(new BoardEntry(name, fqbn));
        }
        return result;
    }

    /** Narrows an already-fetched board list to just the ones belonging to platformId. Pure, no I/O. */
    static List<BoardEntry> filterBoardsByPlatform(List<BoardEntry> all, String platformId) {
        String prefix = platformId + ":";
        List<BoardEntry> result = new ArrayList<BoardEntry>();
        for (BoardEntry b : all) {
            if (b.fqbn.startsWith(prefix)) {
                result.add(b);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Live arduino-cli calls
    // ------------------------------------------------------------------

    static List<InstalledPlatform> fetchInstalledPlatforms(String arduinoCli, String configFile)
            throws ArduinoCliRunner.CliException {
        String output = ArduinoCliRunner.run(arduinoCli, configFile, "core", "list");
        return parseCoreList(output);
    }

    static List<BoardEntry> fetchAllBoards(String arduinoCli, String configFile) throws ArduinoCliRunner.CliException {
        String output = ArduinoCliRunner.run(arduinoCli, configFile, "board", "listall");
        return parseBoardListAll(output);
    }

    @SuppressWarnings("unchecked")
    static List<BoardConfigOption> fetchBoardConfigOptions(String arduinoCli, String configFile, String baseFqbn)
            throws ArduinoCliRunner.CliException, MiniJson.JsonParseException {
        return fetchBoardConfigOptions(arduinoCli, configFile, baseFqbn, null);
    }

    /** Same as above, but if processHolder is non-null, the actual arduino-cli subprocess is exposed through it so a caller can forcibly kill it (e.g. on cancel) - see ArduinoCliRunner.run's matching overload for why plain thread interruption doesn't work here. */
    @SuppressWarnings("unchecked")
    static List<BoardConfigOption> fetchBoardConfigOptions(String arduinoCli, String configFile, String baseFqbn,
            java.util.concurrent.atomic.AtomicReference<Process> processHolder)
            throws ArduinoCliRunner.CliException, MiniJson.JsonParseException {
        String output = ArduinoCliRunner.run(arduinoCli, configFile, processHolder, "board", "details", "--fqbn", baseFqbn, "--json");
        Object root = MiniJson.parse(output);
        Object rawOptions = MiniJson.path(root, "config_options");
        List<BoardConfigOption> result = new ArrayList<BoardConfigOption>();
        if (rawOptions instanceof List) {
            for (Object item : (List<Object>) rawOptions) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> optMap = (Map<String, Object>) item;
                String option = asString(optMap.get("option"));
                String optionLabel = asString(optMap.get("option_label"));
                List<BoardConfigValue> values = new ArrayList<BoardConfigValue>();
                Object rawValues = optMap.get("values");
                if (rawValues instanceof List) {
                    for (Object v : (List<Object>) rawValues) {
                        if (!(v instanceof Map)) {
                            continue;
                        }
                        Map<String, Object> valMap = (Map<String, Object>) v;
                        String value = asString(valMap.get("value"));
                        String valueLabel = asString(valMap.get("value_label"));
                        boolean selected = Boolean.TRUE.equals(valMap.get("selected"));
                        values.add(new BoardConfigValue(value, valueLabel, selected));
                    }
                }
                result.add(new BoardConfigOption(option, optionLabel, values));
            }
        }
        return result;
    }

    private static String asString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    // ------------------------------------------------------------------
    // arduino-cli.yaml line-scan (mirrors readArduinoDirs in deps.go - data dir only)
    // ------------------------------------------------------------------

    static String readArduinoDataDir(String configFileYamlPath) {
        if (configFileYamlPath == null || configFileYamlPath.trim().isEmpty()) {
            return null;
        }
        File f = new File(configFileYamlPath);
        if (!f.isFile()) {
            return null;
        }
        Pattern dataPattern = Pattern.compile("^\\s*data:\\s*\"?([^\"]+?)\"?\\s*$");
        try {
            BufferedReader reader = new BufferedReader(new FileReader(f));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher m = dataPattern.matcher(line);
                    if (m.matches()) {
                        return m.group(1).trim();
                    }
                }
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Disk-based signature (mirrors computeInstalledPlatformsSignature)
    // ------------------------------------------------------------------

    /** Fingerprints installed platforms from <dataDir>/packages/*&#47;hardware/*&#47;* plus boards.txt/platform.txt mtimes. */
    static String computeInstalledPlatformsSignature(String dataDir) {
        if (dataDir == null || dataDir.trim().isEmpty()) {
            return null;
        }
        File packagesDir = new File(dataDir, "packages");
        File[] pkgDirs = packagesDir.listFiles();
        if (pkgDirs == null) {
            return null;
        }
        List<String> entries = new ArrayList<String>();
        for (File pkgDir : pkgDirs) {
            if (!pkgDir.isDirectory()) {
                continue;
            }
            File hardwareDir = new File(pkgDir, "hardware");
            File[] archDirs = hardwareDir.listFiles();
            if (archDirs == null) {
                continue;
            }
            for (File archDir : archDirs) {
                if (!archDir.isDirectory()) {
                    continue;
                }
                File[] versionDirs = archDir.listFiles();
                if (versionDirs == null) {
                    continue;
                }
                for (File versionDir : versionDirs) {
                    if (!versionDir.isDirectory()) {
                        continue;
                    }
                    StringBuilder entry = new StringBuilder(relativize(dataDir, versionDir));
                    for (String fname : new String[]{"boards.txt", "platform.txt"}) {
                        File f = new File(versionDir, fname);
                        if (f.isFile()) {
                            entry.append("|").append(fname).append("@").append(rfc3339Utc(f.lastModified()));
                        }
                    }
                    entries.add(entry.toString());
                }
            }
        }
        if (entries.isEmpty()) {
            return null;
        }
        Collections.sort(entries);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String e : entries) {
                digest.update(e.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** Matches Go's info.ModTime().UTC().Format(time.RFC3339) - UTC, second precision, no fractional seconds. */
    private static String rfc3339Utc(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    private static String relativize(String baseDir, File target) {
        String basePath = new File(baseDir).getAbsolutePath().replace('\\', '/');
        String targetPath = target.getAbsolutePath().replace('\\', '/');
        if (targetPath.startsWith(basePath)) {
            String rel = targetPath.substring(basePath.length());
            while (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            return rel;
        }
        return targetPath;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Cache file (board-wizard-cache.json) - load/save + resolve
    // ------------------------------------------------------------------

    static File cacheFile(String cacheDir) {
        return new File(cacheDir, "board-wizard-cache.json");
    }

    static class ResolveResult {
        final WizardCacheData data;
        final boolean fromCache;

        ResolveResult(WizardCacheData data, boolean fromCache) {
            this.data = data;
            this.fromCache = fromCache;
        }
    }

    /**
     * Loads platforms+boards from the shared cache if its signature still
     * matches the installed platforms on disk, otherwise fetches live via
     * arduino-cli and (when a signature is available) saves a fresh cache.
     * forceRefresh always takes the live path, same as -refresh-wizard-cache.
     */
    static ResolveResult resolvePlatformsAndBoards(String arduinoCli, String configFile, String cacheDir,
            boolean forceRefresh) throws ArduinoCliRunner.CliException {
        File cacheFilePath = cacheFile(cacheDir);
        String dataDir = readArduinoDataDir(configFile);
        String signature = dataDir != null ? computeInstalledPlatformsSignature(dataDir) : null;

        if (!forceRefresh && signature != null) {
            WizardCacheData existing = loadCache(cacheFilePath);
            if (existing != null && signature.equals(existing.signature) && !existing.platforms.isEmpty()) {
                return new ResolveResult(existing, true);
            }
        }

        List<InstalledPlatform> platforms = fetchInstalledPlatforms(arduinoCli, configFile);
        List<BoardEntry> boards = fetchAllBoards(arduinoCli, configFile);

        WizardCacheData fresh = new WizardCacheData();
        fresh.signature = signature == null ? "" : signature;
        fresh.platforms = platforms;
        fresh.boards = boards;
        fresh.boardOptions = new LinkedHashMap<String, List<BoardConfigOption>>();
        fresh.complete = boards.isEmpty();
        if (signature != null) {
            saveCache(cacheFilePath, fresh);
        }
        return new ResolveResult(fresh, false);
    }

    /** Returns cached board options for baseFqbn if present, otherwise fetches live and updates the cache. */
    static List<BoardConfigOption> getOrFetchBoardOptions(String arduinoCli, String configFile, String cacheDir,
            WizardCacheData cache, String baseFqbn) throws ArduinoCliRunner.CliException, MiniJson.JsonParseException {
        List<BoardConfigOption> cached = cache.boardOptions.get(baseFqbn);
        if (cached != null) {
            return cached;
        }
        List<BoardConfigOption> fetched = fetchBoardConfigOptions(arduinoCli, configFile, baseFqbn);
        cache.boardOptions.put(baseFqbn, fetched);
        cache.complete = cache.boardOptions.size() >= cache.boards.size();
        if (cache.signature != null && !cache.signature.isEmpty()) {
            saveCache(cacheFile(cacheDir), cache);
        }
        return fetched;
    }

    /** Joins the base FQBN with "option=value" pairs for each option, defaulting to arduino-cli's own selected value. */
    static String assembleFqbn(String baseFqbn, List<BoardConfigOption> options, Map<String, String> chosenValueByOption) {
        if (options == null || options.isEmpty()) {
            return baseFqbn;
        }
        List<String> parts = new ArrayList<String>();
        for (BoardConfigOption opt : options) {
            if (opt.values.isEmpty()) {
                continue;
            }
            String chosen = chosenValueByOption.get(opt.option);
            if (chosen == null) {
                chosen = defaultValue(opt);
            }
            parts.add(opt.option + "=" + chosen);
        }
        if (parts.isEmpty()) {
            return baseFqbn;
        }
        StringBuilder sb = new StringBuilder(baseFqbn).append(":");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    static String defaultValue(BoardConfigOption opt) {
        for (BoardConfigValue v : opt.values) {
            if (v.selected) {
                return v.value;
            }
        }
        return opt.values.isEmpty() ? "" : opt.values.get(0).value;
    }

    @SuppressWarnings("unchecked")
    static WizardCacheData loadCache(File cacheFile) {
        if (!cacheFile.isFile()) {
            return null;
        }
        try {
            String text = readFile(cacheFile);
            Object root = MiniJson.parse(text);
            if (!(root instanceof Map)) {
                return null;
            }
            Map<String, Object> map = (Map<String, Object>) root;

            WizardCacheData data = new WizardCacheData();
            data.signature = asString(map.get("signature"));
            data.complete = Boolean.TRUE.equals(map.get("complete"));

            Object rawPlatforms = map.get("platforms");
            if (rawPlatforms instanceof List) {
                for (Object item : (List<Object>) rawPlatforms) {
                    if (item instanceof Map) {
                        Map<String, Object> pm = (Map<String, Object>) item;
                        data.platforms.add(new InstalledPlatform(asString(pm.get("ID")), asString(pm.get("Name"))));
                    }
                }
            }

            Object rawBoards = map.get("boards");
            if (rawBoards instanceof List) {
                for (Object item : (List<Object>) rawBoards) {
                    if (item instanceof Map) {
                        Map<String, Object> bm = (Map<String, Object>) item;
                        data.boards.add(new BoardEntry(asString(bm.get("Name")), asString(bm.get("FQBN"))));
                    }
                }
            }

            Object rawBoardOptions = map.get("boardOptions");
            if (rawBoardOptions instanceof Map) {
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) rawBoardOptions).entrySet()) {
                    List<BoardConfigOption> options = new ArrayList<BoardConfigOption>();
                    if (entry.getValue() instanceof List) {
                        for (Object optItem : (List<Object>) entry.getValue()) {
                            if (!(optItem instanceof Map)) {
                                continue;
                            }
                            Map<String, Object> optMap = (Map<String, Object>) optItem;
                            List<BoardConfigValue> values = new ArrayList<BoardConfigValue>();
                            Object rawValues = optMap.get("values");
                            if (rawValues instanceof List) {
                                for (Object v : (List<Object>) rawValues) {
                                    if (!(v instanceof Map)) {
                                        continue;
                                    }
                                    Map<String, Object> vm = (Map<String, Object>) v;
                                    values.add(new BoardConfigValue(asString(vm.get("value")), asString(vm.get("value_label")),
                                            Boolean.TRUE.equals(vm.get("selected"))));
                                }
                            }
                            options.add(new BoardConfigOption(asString(optMap.get("option")), asString(optMap.get("option_label")), values));
                        }
                    }
                    data.boardOptions.put(entry.getKey(), options);
                }
            }
            return data;
        } catch (Exception e) {
            return null; // corrupt/unreadable cache - safe to fall through to a live fetch
        }
    }

    static void saveCache(File cacheFile, WizardCacheData data) {
        try {
            File dir = cacheFile.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("signature", data.signature);
            root.put("generatedAt", Instant.now().toString());

            List<Object> platformsJson = new ArrayList<Object>();
            for (InstalledPlatform p : data.platforms) {
                Map<String, Object> pm = new LinkedHashMap<String, Object>();
                pm.put("ID", p.id);
                pm.put("Name", p.name);
                platformsJson.add(pm);
            }
            root.put("platforms", platformsJson);

            List<Object> boardsJson = new ArrayList<Object>();
            for (BoardEntry b : data.boards) {
                Map<String, Object> bm = new LinkedHashMap<String, Object>();
                bm.put("Name", b.name);
                bm.put("FQBN", b.fqbn);
                boardsJson.add(bm);
            }
            root.put("boards", boardsJson);

            Map<String, Object> boardOptionsJson = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, List<BoardConfigOption>> entry : data.boardOptions.entrySet()) {
                List<Object> optionsJson = new ArrayList<Object>();
                for (BoardConfigOption opt : entry.getValue()) {
                    Map<String, Object> om = new LinkedHashMap<String, Object>();
                    om.put("option", opt.option);
                    om.put("option_label", opt.optionLabel);
                    List<Object> valuesJson = new ArrayList<Object>();
                    for (BoardConfigValue v : opt.values) {
                        Map<String, Object> vm = new LinkedHashMap<String, Object>();
                        vm.put("value", v.value);
                        vm.put("value_label", v.valueLabel);
                        vm.put("selected", v.selected);
                        valuesJson.add(vm);
                    }
                    om.put("values", valuesJson);
                    optionsJson.add(om);
                }
                boardOptionsJson.put(entry.getKey(), optionsJson);
            }
            root.put("boardOptions", boardOptionsJson);
            root.put("complete", data.complete);

            String json = MiniJson.write(root);
            File tmp = new File(dir, cacheFile.getName() + ".tmp");
            writeFile(tmp, json);
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.delete();
                tmp.renameTo(cacheFile);
            }
        } catch (Exception e) {
            // Best-effort, same as saveWizardCache on the Go side - never fatal.
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

    private static void writeFile(File f, String content) throws IOException {
        FileWriter writer = new FileWriter(f);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }
}
