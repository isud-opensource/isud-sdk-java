package cn.isud.sdk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置极简 JSON 解析器/序列化器 —— 零第三方依赖（Java 标准库无 JSON）。
 * 支持 object / array / string / number / boolean / null，满足 SDK 信封与请求体需求。
 */
final class Json {
    private Json() {}

    // ---------- 解析 ----------
    static Object parse(String s) {
        if (s == null) return null;
        Parser p = new Parser(s);
        Object v = p.value();
        p.skipWs();
        if (p.pos < p.s.length()) throw new IllegalArgumentException("JSON 尾部有多余内容 @ " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    static List<Object> asArr(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static int asInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try { return Integer.parseInt((String) o); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    static long asLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) {
            try { return Long.parseLong((String) o); } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }

    static Boolean asBool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof String) return Boolean.parseBoolean((String) o);
        return null;
    }

    private static final class Parser {
        final String s;
        int pos = 0;
        Parser(String s) { this.s = s; }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object value() {
            skipWs();
            if (pos >= s.length()) return null;
            char c = s.charAt(pos);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new IllegalArgumentException("JSON 解析失败 @ " + pos);
            pos += lit.length();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (pos < s.length()) {
                skipWs();
                String key = string();
                skipWs();
                if (s.charAt(pos) != ':') throw new IllegalArgumentException("期望 ':' @ " + pos);
                pos++;
                m.put(key, value());
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') { pos++; continue; }
                if (pos < s.length() && s.charAt(pos) == '}') { pos++; break; }
                throw new IllegalArgumentException("期望 ',' 或 '}' @ " + pos);
            }
            return m;
        }

        List<Object> array() {
            List<Object> a = new ArrayList<>();
            pos++; skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return a; }
            while (pos < s.length()) {
                a.add(value());
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') { pos++; continue; }
                if (pos < s.length() && s.charAt(pos) == ']') { pos++; break; }
                throw new IllegalArgumentException("期望 ',' 或 ']' @ " + pos);
            }
            return a;
        }

        String string() {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != '"')
                throw new IllegalArgumentException("期望字符串 @ " + pos);
            pos++;
            StringBuilder b = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (pos >= s.length()) break;
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            if (pos + 4 <= s.length()) {
                                b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                                pos += 4;
                            }
                            break;
                        default: b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            throw new IllegalArgumentException("字符串未闭合");
        }

        Object number() {
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) ||
                    s.charAt(pos) == '-' || s.charAt(pos) == '+' ||
                    s.charAt(pos) == '.' || s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) pos++;
            if (start == pos) throw new IllegalArgumentException("JSON 解析失败 @ " + pos);
            String num = s.substring(start, pos);
            if (num.contains(".") || num.contains("e") || num.contains("E"))
                return Double.parseDouble(num);
            try { return Long.parseLong(num); }
            catch (NumberFormatException e) { return Double.parseDouble(num); }
        }
    }

    // ---------- 序列化 ----------
    static String stringify(Object o) {
        StringBuilder b = new StringBuilder();
        write(o, b);
        return b.toString();
    }

    private static void write(Object o, StringBuilder b) {
        if (o == null) { b.append("null"); return; }
        if (o instanceof String) { quote((String) o, b); return; }
        if (o instanceof Boolean || o instanceof Number) { b.append(o); return; }
        if (o instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), b);
                b.append(':');
                write(e.getValue(), b);
            }
            b.append('}');
            return;
        }
        if (o instanceof Iterable) {
            b.append('[');
            boolean first = true;
            for (Object x : (Iterable<?>) o) {
                if (!first) b.append(',');
                first = false;
                write(x, b);
            }
            b.append(']');
            return;
        }
        if (o.getClass().isArray()) {
            b.append('[');
            int n = java.lang.reflect.Array.getLength(o);
            for (int i = 0; i < n; i++) {
                if (i > 0) b.append(',');
                write(java.lang.reflect.Array.get(o, i), b);
            }
            b.append(']');
            return;
        }
        quote(String.valueOf(o), b);
    }

    private static void quote(String s, StringBuilder b) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }
}
