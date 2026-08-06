package fastbuilduix;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes AppSettings as a small JSON file, so the Arduino CLI /
 * arduino-cli.yaml paths configured on the App Settings tab are remembered
 * automatically the next time the UI starts - no third-party JSON library,
 * just a minimal parser/writer for a flat {"key": "value", ...} object,
 * which is all AppSettings needs.
 */
public final class AppSettingsStore {

    private AppSettingsStore() {
    }

    /** File name used in the app's working directory (System.getProperty("user.dir")). */
    public static final String DEFAULT_FILE_NAME = "fastbuild-ui-settings.json";

    public static File defaultFile() {
        return new File(System.getProperty("user.dir", "."), DEFAULT_FILE_NAME);
    }

    /** Returns a blank AppSettings if the file doesn't exist yet - not an error. */
    public static AppSettings load(File file) throws IOException {
        AppSettings settings = new AppSettings();
        if (!file.exists()) {
            return settings;
        }
        String text = readFile(file);
        Map<String, String> values = parseFlatJsonObject(text);
        if (values.containsKey("arduinoCliPath")) {
            settings.arduinoCliPath = values.get("arduinoCliPath");
        }
        if (values.containsKey("arduinoCliYamlPath")) {
            settings.arduinoCliYamlPath = values.get("arduinoCliYamlPath");
        }
        if (values.containsKey("fastbuildExePath")) {
            settings.fastbuildExePath = values.get("fastbuildExePath");
        }
        if (values.containsKey("defaultCacheRoot")) {
            settings.defaultCacheRoot = values.get("defaultCacheRoot");
        }
        return settings;
    }

    public static void save(AppSettings settings, File file) throws IOException {
        LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
        values.put("arduinoCliPath", nullToEmpty(settings.arduinoCliPath));
        values.put("arduinoCliYamlPath", nullToEmpty(settings.arduinoCliYamlPath));
        values.put("fastbuildExePath", nullToEmpty(settings.fastbuildExePath));
        values.put("defaultCacheRoot", nullToEmpty(settings.defaultCacheRoot));
        String json = writeFlatJsonObject(values);
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            writer.write(json);
        } finally {
            writer.close();
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String readFile(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
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

    // ------------------------------------------------------------------
    // Tiny JSON support - flat {"key": "value", ...} objects only. That's
    // all AppSettings needs, so this deliberately doesn't handle nesting,
    // arrays, or numbers/booleans.
    // ------------------------------------------------------------------

    static String writeFlatJsonObject(Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        int i = 0;
        int n = values.size();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            sb.append("  ").append(quote(entry.getKey())).append(": ").append(quote(entry.getValue()));
            i++;
            if (i < n) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    static Map<String, String> parseFlatJsonObject(String text) throws IOException {
        Map<String, String> result = new LinkedHashMap<String, String>();
        int pos = skipWhitespace(text, 0);
        if (pos >= text.length() || text.charAt(pos) != '{') {
            throw new IOException("expected '{' at start of JSON object");
        }
        pos++;
        pos = skipWhitespace(text, pos);
        if (pos < text.length() && text.charAt(pos) == '}') {
            return result; // empty object
        }
        while (true) {
            pos = skipWhitespace(text, pos);
            if (pos >= text.length() || text.charAt(pos) != '"') {
                throw new IOException("expected a string key in JSON object");
            }
            int[] afterKey = new int[1];
            String key = parseQuotedString(text, pos, afterKey);
            pos = skipWhitespace(text, afterKey[0]);
            if (pos >= text.length() || text.charAt(pos) != ':') {
                throw new IOException("expected ':' after key in JSON object");
            }
            pos++;
            pos = skipWhitespace(text, pos);

            String value;
            if (pos < text.length() && text.charAt(pos) == '"') {
                int[] afterValue = new int[1];
                value = parseQuotedString(text, pos, afterValue);
                pos = afterValue[0];
            } else if (text.startsWith("null", pos)) {
                value = "";
                pos += 4;
            } else {
                throw new IOException("expected a string value in JSON object");
            }
            result.put(key, value);

            pos = skipWhitespace(text, pos);
            if (pos < text.length() && text.charAt(pos) == ',') {
                pos++;
                continue;
            }
            if (pos < text.length() && text.charAt(pos) == '}') {
                break;
            }
            throw new IOException("expected ',' or '}' in JSON object");
        }
        return result;
    }

    private static int skipWhitespace(String text, int pos) {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    /**
     * Parses a JSON string literal starting at text.charAt(startQuoteIndex) ==
     * '"'. Writes the index just past the closing quote into endIndexOut[0].
     */
    private static String parseQuotedString(String text, int startQuoteIndex, int[] endIndexOut) throws IOException {
        StringBuilder sb = new StringBuilder();
        int i = startQuoteIndex + 1;
        while (true) {
            if (i >= text.length()) {
                throw new IOException("unterminated JSON string");
            }
            char c = text.charAt(i);
            if (c == '"') {
                endIndexOut[0] = i + 1;
                return sb.toString();
            }
            if (c == '\\') {
                i++;
                if (i >= text.length()) {
                    throw new IOException("unterminated JSON escape sequence");
                }
                char esc = text.charAt(i);
                if (esc == '"') {
                    sb.append('"');
                } else if (esc == '\\') {
                    sb.append('\\');
                } else if (esc == '/') {
                    sb.append('/');
                } else if (esc == 'n') {
                    sb.append('\n');
                } else if (esc == 'r') {
                    sb.append('\r');
                } else if (esc == 't') {
                    sb.append('\t');
                } else if (esc == 'b') {
                    sb.append('\b');
                } else if (esc == 'f') {
                    sb.append('\f');
                } else if (esc == 'u') {
                    if (i + 4 >= text.length()) {
                        throw new IOException("invalid unicode escape in JSON string");
                    }
                    String hex = text.substring(i + 1, i + 5);
                    sb.append((char) Integer.parseInt(hex, 16));
                    i += 4;
                } else {
                    throw new IOException("invalid escape character in JSON string: \\" + esc);
                }
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
