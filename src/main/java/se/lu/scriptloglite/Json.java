package se.lu.scriptloglite;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class Json {
    final String input;
    int position;
    Json(String input) { this.input = input; }

    Object parse() {
        Object value = value();
        whitespace();
        if (position != input.length()) throw error("trailing input");
        return value;
    }

    IllegalArgumentException error(String message) {
        return new IllegalArgumentException("JSON at character " + position + ": " + message);
    }

    void whitespace() {
        while (position < input.length() && " \n\r\t".indexOf(input.charAt(position)) >= 0) position++;
    }

    boolean take(char c) {
        whitespace();
        if (position < input.length() && input.charAt(position) == c) { position++; return true; }
        return false;
    }

    void expect(char c) { if (!take(c)) throw error("expected " + c); }

    Object value() {
        whitespace();
        if (position == input.length()) throw error("missing value");
        char c = input.charAt(position);
        if (c == '"') return string();
        if (take('[')) {
            List<Object> values = new ArrayList<>();
            if (take(']')) return values;
            do { values.add(value()); } while (take(','));
            expect(']');
            return values;
        }
        if (take('{')) {
            Map<String, Object> values = new LinkedHashMap<>();
            if (take('}')) return values;
            do {
                whitespace();
                String key = string();
                expect(':');
                if (values.containsKey(key)) throw error("duplicate key " + key);
                values.put(key, value());
            } while (take(','));
            expect('}');
            return values;
        }
        for (String literal : List.of("null", "true", "false")) {
            if (input.startsWith(literal, position)) {
                position += literal.length();
                return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
        }
        Matcher matcher = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
                .matcher(input);
        matcher.region(position, input.length());
        if (!matcher.lookingAt()) throw error("invalid value");
        String number = matcher.group();
        position = matcher.end();
        // Avoid a numeric ternary: Java would promote long values to double and lose nanosecond precision.
        if (number.contains(".") || number.contains("e") || number.contains("E")) {
            double result = Double.parseDouble(number);
            if (!Double.isFinite(result)) throw error("non-finite number");
            return result;
        }
        return Long.parseLong(number);
    }

    String string() {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (position < input.length()) {
            char c = input.charAt(position++);
            if (c == '"') return result.toString();
            if (c < 32) throw error("unescaped control character");
            if (c == '\\') {
                if (position == input.length()) throw error("unfinished escape");
                c = input.charAt(position++);
                switch (c) {
                    case '"': case '\\': case '/': break;
                    case 'n': c = '\n'; break;
                    case 'r': c = '\r'; break;
                    case 't': c = '\t'; break;
                    case 'b': c = '\b'; break;
                    case 'f': c = '\f'; break;
                    case 'u':
                        if (position + 4 > input.length()) throw error("unfinished unicode escape");
                        c = (char) Integer.parseInt(input.substring(position, position + 4), 16);
                        position += 4;
                        break;
                    default: throw error("unknown escape");
                }
            }
            result.append(c);
        }
        throw error("unterminated string");
    }

    static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 32 || Character.isSurrogate(c)) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else result.append(c);
            }
        }
        return result.append('"').toString();
    }

    static String stringify(Object value, int depth) {
        if (value == null) return "null";
        if (value instanceof String) return quote((String) value);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        List<String> children = new ArrayList<>();
        boolean object = value instanceof Map;
        if (object) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                children.add(quote((String) entry.getKey()) + ": " + stringify(entry.getValue(), depth + 1));
            }
        } else {
            for (Object item : (List<?>) value) children.add(stringify(item, depth + 1));
        }
        String open = object ? "{" : "[", close = object ? "}" : "]";
        if (children.isEmpty()) return open + close;
        String indent = "  ".repeat(depth + 1);
        return open + "\n" + indent + String.join(",\n" + indent, children)
                + "\n" + "  ".repeat(depth) + close;
    }
}
