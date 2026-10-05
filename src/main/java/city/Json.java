package city;

import java.util.*;

/** Small bounded JSON codec. No Java object deserialization on the network. */
public final class Json {
  public static Map<String, Object> obj(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> map(Object o) {
    if (!(o instanceof Map)) throw new IllegalArgumentException("JSON object required");
    return (Map<String, Object>) o;
  }

  @SuppressWarnings("unchecked")
  public static List<Object> list(Object o) {
    if (!(o instanceof List)) throw new IllegalArgumentException("JSON array required");
    return (List<Object>) o;
  }

  public static String str(Map<String, Object> m, String k) {
    Object v = m.get(k);
    if (!(v instanceof String)) throw new IllegalArgumentException("Missing string: " + k);
    return (String) v;
  }

  public static int num(Map<String, Object> m, String k) {
    Object o = m.get(k);
    if (!(o instanceof Number)) throw new IllegalArgumentException("Missing number: " + k);
    long n = ((Number) o).longValue();
    if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE)
      throw new IllegalArgumentException("Number out of range");
    return (int) n;
  }

  public static String write(Object o) {
    StringBuilder b = new StringBuilder();
    emit(b, o);
    return b.toString();
  }

  private static void emit(StringBuilder b, Object o) {
    if (o == null) {
      b.append("null");
      return;
    }
    if (o instanceof String) {
      b.append('"');
      for (char c : ((String) o).toCharArray()) {
        switch (c) {
          case '"':
            b.append("\\\"");
            break;
          case '\\':
            b.append("\\\\");
            break;
          case '\n':
            b.append("\\n");
            break;
          case '\r':
            b.append("\\r");
            break;
          case '\t':
            b.append("\\t");
            break;
          default:
            if (c < 32) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
      }
      b.append('"');
      return;
    }
    if (o instanceof Number || o instanceof Boolean) {
      b.append(o);
      return;
    }
    if (o instanceof Map) {
      b.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
        if (!first) b.append(',');
        first = false;
        emit(b, e.getKey().toString());
        b.append(':');
        emit(b, e.getValue());
      }
      b.append('}');
      return;
    }
    if (o instanceof Iterable) {
      b.append('[');
      boolean first = true;
      for (Object e : (Iterable<?>) o) {
        if (!first) b.append(',');
        first = false;
        emit(b, e);
      }
      b.append(']');
      return;
    }
    if (o.getClass().isArray()) {
      b.append('[');
      for (int i = 0; i < java.lang.reflect.Array.getLength(o); i++) {
        if (i > 0) b.append(',');
        emit(b, java.lang.reflect.Array.get(o, i));
      }
      b.append(']');
      return;
    }
    throw new IllegalArgumentException("Unsupported JSON value");
  }

  public static Object read(String text) {
    Parser p = new Parser(text);
    Object o = p.value(0);
    p.ws();
    if (p.i != text.length()) throw new IllegalArgumentException("Trailing JSON");
    return o;
  }

  private static final class Parser {
    final String s;
    int i;

    Parser(String s) {
      this.s = s;
    }

    void ws() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    char pop() {
      if (i >= s.length()) throw new IllegalArgumentException("Incomplete JSON");
      return s.charAt(i++);
    }

    Object value(int depth) {
      if (depth > 80) throw new IllegalArgumentException("JSON too deep");
      ws();
      char c = pop();
      if (c == '"') return string();
      if (c == '{') {
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (i < s.length() && s.charAt(i) == '}') {
          i++;
          return m;
        }
        do {
          ws();
          if (pop() != '"') throw new IllegalArgumentException("Object key expected");
          String k = string();
          ws();
          if (pop() != ':') throw new IllegalArgumentException("Colon expected");
          if (m.containsKey(k)) throw new IllegalArgumentException("Duplicate key");
          m.put(k, value(depth + 1));
          ws();
          c = pop();
          if (c == '}') return m;
        } while (c == ',');
        throw new IllegalArgumentException("Object delimiter");
      }
      if (c == '[') {
        List<Object> a = new ArrayList<>();
        ws();
        if (i < s.length() && s.charAt(i) == ']') {
          i++;
          return a;
        }
        do {
          a.add(value(depth + 1));
          ws();
          c = pop();
          if (c == ']') return a;
        } while (c == ',');
        throw new IllegalArgumentException("Array delimiter");
      }
      i--;
      for (String word : Arrays.asList("true", "false", "null"))
        if (s.startsWith(word, i)) {
          i += word.length();
          return word.equals("null") ? null : Boolean.valueOf(word);
        }
      int start = i;
      if (i < s.length() && s.charAt(i) == '-') i++;
      while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
      if (start == i) throw new IllegalArgumentException("Invalid JSON value");
      boolean real = false;
      if (i < s.length() && s.charAt(i) == '.') {
        real = true;
        i++;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
      }
      if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
        real = true;
        i++;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
      }
      String n = s.substring(start, i);
      return real ? (Number) Double.valueOf(n) : Long.valueOf(n);
    }

    String string() {
      StringBuilder b = new StringBuilder();
      while (true) {
        char c = pop();
        if (c == '"') return b.toString();
        if (c == '\\') {
          c = pop();
          switch (c) {
            case '"':
            case '\\':
            case '/':
              b.append(c);
              break;
            case 'b':
              b.append('\b');
              break;
            case 'f':
              b.append('\f');
              break;
            case 'n':
              b.append('\n');
              break;
            case 'r':
              b.append('\r');
              break;
            case 't':
              b.append('\t');
              break;
            case 'u':
              if (i + 4 > s.length()) throw new IllegalArgumentException("Unicode escape");
              b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
              i += 4;
              break;
            default:
              throw new IllegalArgumentException("Escape");
          }
        } else {
          if (c < 32) throw new IllegalArgumentException("Control character");
          b.append(c);
        }
      }
    }
  }
}
