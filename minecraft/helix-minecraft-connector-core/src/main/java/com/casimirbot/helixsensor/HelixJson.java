package com.casimirbot.helixsensor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HelixJson {
    private HelixJson() {}

    public static String stringify(Object value) {
        StringBuilder builder = new StringBuilder();
        write(builder, value, false);
        return builder.toString();
    }

    public static String stringifyIncludingNulls(Object value) {
        StringBuilder builder = new StringBuilder();
        write(builder, value, true);
        return builder.toString();
    }

    public static Object parse(String json) {
        return new Parser(json).parse();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        return value instanceof List<?> ? (List<Object>) value : List.of();
    }

    private static void write(
        StringBuilder builder,
        Object value,
        boolean includeNullMapValues
    ) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String text) {
            writeString(builder, text);
        } else if (value instanceof Float || value instanceof Double) {
            writeFloatingNumber(builder, (Number) value);
        } else if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
        } else if (value instanceof Map<?, ?> map) {
            builder.append('{');
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            boolean first = true;
            for (Map.Entry<?, ?> entry : entries) {
                if (entry.getValue() == null && !includeNullMapValues) continue;
                if (!first) builder.append(',');
                first = false;
                writeString(builder, String.valueOf(entry.getKey()));
                builder.append(':');
                write(builder, entry.getValue(), includeNullMapValues);
            }
            builder.append('}');
        } else if (value instanceof Collection<?> collection) {
            builder.append('[');
            boolean first = true;
            for (Object entry : collection) {
                if (!first) builder.append(',');
                first = false;
                write(builder, entry, includeNullMapValues);
            }
            builder.append(']');
        } else {
            writeString(builder, String.valueOf(value));
        }
    }

    /**
     * Mirrors the finite-number representation used by JSON.stringify closely
     * enough for cross-language canonical hashes. Java's Double.toString keeps
     * values such as 0.0 and 3.0E7, while JavaScript normalizes those to 0 and
     * 30000000 before hashing the parsed contract. The wire value is unchanged;
     * only the canonical JSON spelling is normalized.
     */
    private static void writeFloatingNumber(StringBuilder builder, Number value) {
        double number = value.doubleValue();
        if (!Double.isFinite(number)) {
            builder.append("null");
            return;
        }
        if (number == 0.0D) {
            builder.append('0');
            return;
        }
        BigDecimal decimal = new BigDecimal(value.toString()).stripTrailingZeros();
        double magnitude = Math.abs(number);
        String encoded = magnitude >= 1.0E-6D && magnitude < 1.0E21D
            ? decimal.toPlainString()
            : decimal.toString().replace('E', 'e');
        builder.append(encoded);
    }

    private static void writeString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) builder.append(String.format("\\u%04x", (int) c));
                    else builder.append(c);
                }
            }
        }
        builder.append('"');
    }

    private static final class Parser {
        private final String text;
        private int index;

        Parser(String text) {
            this.text = text == null ? "" : text;
        }

        Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= text.length()) return null;
            char c = text.charAt(index);
            if (c == '"') return parseString();
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (text.startsWith("true", index)) {
                index += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", index)) {
                index += 5;
                return Boolean.FALSE;
            }
            if (text.startsWith("null", index)) {
                index += 4;
                return null;
            }
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> result = new LinkedHashMap<>();
            index++;
            skipWhitespace();
            if (peek('}')) {
                index++;
                return result;
            }
            while (index < text.length()) {
                String key = parseString();
                skipWhitespace();
                if (peek(':')) index++;
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    skipWhitespace();
                    continue;
                }
                if (peek('}')) {
                    index++;
                    break;
                }
            }
            return result;
        }

        private List<Object> parseArray() {
            List<Object> result = new ArrayList<>();
            index++;
            skipWhitespace();
            if (peek(']')) {
                index++;
                return result;
            }
            while (index < text.length()) {
                result.add(parseValue());
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    continue;
                }
                if (peek(']')) {
                    index++;
                    break;
                }
            }
            return result;
        }

        private String parseString() {
            StringBuilder builder = new StringBuilder();
            if (peek('"')) index++;
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') break;
                if (c == '\\' && index < text.length()) {
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        case '/' -> builder.append('/');
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> {
                            String hex = text.substring(index, Math.min(index + 4, text.length()));
                            index = Math.min(index + 4, text.length());
                            builder.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> builder.append(escaped);
                    }
                } else {
                    builder.append(c);
                }
            }
            return builder.toString();
        }

        private Number parseNumber() {
            int start = index;
            while (index < text.length()) {
                char c = text.charAt(index);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    index++;
                } else {
                    break;
                }
            }
            String raw = text.substring(start, index);
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) return Double.parseDouble(raw);
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        private boolean peek(char expected) {
            return index < text.length() && text.charAt(index) == expected;
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        }
    }
}
