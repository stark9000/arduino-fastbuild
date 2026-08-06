package fastbuilduix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON value-tree parser/writer - handles the full JSON grammar
 * (objects, arrays, strings, numbers, booleans, null), unlike
 * AppSettingsStore's parser which only handles flat string maps. This one is
 * used for:
 *   - parsing `arduino-cli board details --json`'s config_options
 *   - reading/writing board-wizard-cache.json (which needs nested
 *     objects/arrays, and must stay byte-compatible with what fastbuild's
 *     own Go -configure-board writes/reads)
 *
 * Still no third-party library - just a bit more of the grammar than
 * AppSettingsStore needed.
 *
 * Values are represented as plain Java objects:
 *   JSON object -> LinkedHashMap<String, Object>   (insertion order preserved)
 *   JSON array  -> ArrayList<Object>
 *   JSON string -> String
 *   JSON number -> Double
 *   JSON true/false -> Boolean
 *   JSON null   -> null
 */
final class MiniJson {

    private MiniJson() {
    }

    static class JsonParseException extends Exception {
        JsonParseException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    static Object parse(String text) throws JsonParseException {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.parseValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new JsonParseException("unexpected trailing content after JSON value");
        }
        return value;
    }

    /** Convenience: fetch a value nested by object keys / array indices, or null if any step misses. */
    @SuppressWarnings("unchecked")
    static Object path(Object root, Object... keysOrIndices) {
        Object current = root;
        for (Object step : keysOrIndices) {
            if (current == null) {
                return null;
            }
            if (step instanceof String) {
                if (!(current instanceof Map)) {
                    return null;
                }
                current = ((Map<String, Object>) current).get(step);
            } else if (step instanceof Integer) {
                if (!(current instanceof List)) {
                    return null;
                }
                List<Object> list = (List<Object>) current;
                int idx = (Integer) step;
                if (idx < 0 || idx >= list.size()) {
                    return null;
                }
                current = list.get(idx);
            } else {
                return null;
            }
        }
        return current;
    }

    private static class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
            this.pos = 0;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        char peek() throws JsonParseException {
            if (pos >= text.length()) {
                throw new JsonParseException("unexpected end of JSON input");
            }
            return text.charAt(pos);
        }

        Object parseValue() throws JsonParseException {
            skipWhitespace();
            char c = peek();
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't' || c == 'f') {
                return parseBoolean();
            }
            if (text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            if (c == '-' || Character.isDigit(c)) {
                return parseNumber();
            }
            throw new JsonParseException("unexpected character '" + c + "' at position " + pos);
        }

        Map<String, Object> parseObject() throws JsonParseException {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            pos++; // consume '{'
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new JsonParseException("expected a string key at position " + pos);
                }
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') {
                    throw new JsonParseException("expected ':' at position " + pos);
                }
                pos++;
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    break;
                }
                throw new JsonParseException("expected ',' or '}' at position " + pos);
            }
            return result;
        }

        List<Object> parseArray() throws JsonParseException {
            List<Object> result = new ArrayList<Object>();
            pos++; // consume '['
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return result;
            }
            while (true) {
                Object value = parseValue();
                result.add(value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    break;
                }
                throw new JsonParseException("expected ',' or ']' at position " + pos);
            }
            return result;
        }

        String parseString() throws JsonParseException {
            if (peek() != '"') {
                throw new JsonParseException("expected '\"' at position " + pos);
            }
            StringBuilder sb = new StringBuilder();
            pos++; // consume opening quote
            while (true) {
                if (pos >= text.length()) {
                    throw new JsonParseException("unterminated string");
                }
                char c = text.charAt(pos);
                if (c == '"') {
                    pos++;
                    return sb.toString();
                }
                if (c == '\\') {
                    pos++;
                    if (pos >= text.length()) {
                        throw new JsonParseException("unterminated escape sequence");
                    }
                    char esc = text.charAt(pos);
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
                        if (pos + 4 >= text.length()) {
                            throw new JsonParseException("invalid unicode escape");
                        }
                        String hex = text.substring(pos + 1, pos + 5);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                    } else {
                        throw new JsonParseException("invalid escape character '\\" + esc + "'");
                    }
                    pos++;
                } else {
                    sb.append(c);
                    pos++;
                }
            }
        }

        Boolean parseBoolean() throws JsonParseException {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new JsonParseException("expected 'true' or 'false' at position " + pos);
        }

        Double parseNumber() throws JsonParseException {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (pos < text.length() && text.charAt(pos) == '.') {
                pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            String numText = text.substring(start, pos);
            try {
                return Double.parseDouble(numText);
            } catch (NumberFormatException e) {
                throw new JsonParseException("invalid number '" + numText + "'");
            }
        }
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Serializes a value tree (Map/List/String/Number/Boolean/null) with 2-space indentation. */
    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb, 0);
        sb.append("\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object value, StringBuilder sb, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue());
        } else if (value instanceof Number) {
            writeNumber((Number) value, sb);
        } else if (value instanceof Map) {
            writeObject((Map<String, Object>) value, sb, indent);
        } else if (value instanceof List) {
            writeArray((List<Object>) value, sb, indent);
        } else {
            // Fallback - shouldn't happen if callers stick to the documented types.
            writeString(String.valueOf(value), sb);
        }
    }

    private static void writeObject(Map<String, Object> map, StringBuilder sb, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        int n = map.size();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            indent(sb, indent + 1);
            writeString(entry.getKey(), sb);
            sb.append(": ");
            writeValue(entry.getValue(), sb, indent + 1);
            i++;
            if (i < n) {
                sb.append(",");
            }
            sb.append("\n");
        }
        indent(sb, indent);
        sb.append("}");
    }

    private static void writeArray(List<Object> list, StringBuilder sb, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(sb, indent + 1);
            writeValue(list.get(i), sb, indent + 1);
            if (i < list.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        indent(sb, indent);
        sb.append("]");
    }

    private static void writeNumber(Number n, StringBuilder sb) {
        double d = n.doubleValue();
        if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
        } else {
            sb.append(d);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
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
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
    }
}
