package city;

import it.unisa.dia.gas.jpbc.*;
import it.unisa.dia.gas.plaf.jpbc.pairing.PairingFactory;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import rabe.*;

/** Explicit, versioned DTOs. Secret scalar export is opt-in and never used for enrollment. */
public final class Wire {
  public static String enc(Element e) {
    return e == null ? null : Base64.getEncoder().encodeToString(e.toBytes());
  }

  public static Element dec(Pairing p, Object value, boolean gt) {
    if (value == null) return null;
    byte[] bytes = Base64.getDecoder().decode((String) value);
    Field<?> f = gt ? p.getGT() : p.getG1();
    if (bytes.length != f.getLengthInBytes())
      throw new IllegalArgumentException("Invalid group element length");
    return f.newElementFromBytes(bytes).getImmutable();
  }

  static List<Object> encs(Element[] es) {
    List<Object> a = new ArrayList<>();
    for (Element e : es) a.add(enc(e));
    return a;
  }

  static Element[] decs(Pairing p, Object v, int n) {
    List<Object> a = Json.list(v);
    if (a.size() != n) throw new IllegalArgumentException("Invalid element vector size");
    Element[] es = new Element[n];
    for (int i = 0; i < n; i++) es[i] = dec(p, a.get(i), false);
    return es;
  }

  static List<Object> encsWithIdentity(Element[] es) {
    List<Object> a = new ArrayList<>();
    for (Element e : es) a.add(e == null || e.isOne() ? null : enc(e));
    return a;
  }

  static Element[] decsWithIdentity(Pairing p, Object v, int n) {
    List<Object> a = Json.list(v);
    if (a.size() != n) throw new IllegalArgumentException("Invalid element vector size");
    Element[] es = new Element[n];
    for (int i = 0; i < n; i++) es[i] = decG1WithIdentity(p, a.get(i));
    return es;
  }

  static Element decG1WithIdentity(Pairing p, Object value) {
    if (value == null) return p.getG1().newOneElement().getImmutable();
    byte[] bytes = Base64.getDecoder().decode((String) value);
    boolean zero = bytes.length == p.getG1().getLengthInBytes();
    for (byte b : bytes) zero &= b == 0;
    // Older JARs wrote the JPBC identity as an all-zero byte string.  It
    // decodes to a non-identity object, so recognize that legacy form.
    return zero ? p.getG1().newOneElement().getImmutable() : dec(p, value, false);
  }

  public static Map<String, Object> context(Scheme s, String id) {
    List<Object> U = new ArrayList<>();
    for (Element[] es : s.U) U.add(encs(es));
    return Json.obj(
        "schema", 1, "id", id, "n", s.n, "u", s.u, "g", enc(s.g), "Z", enc(s.Z), "h", enc(s.h), "v",
        enc(s.v), "O", enc(s.O), "Y", enc(s.Y), "A", encs(s.A), "B", encs(s.B), "K", encs(s.K), "E",
        encs(s.E), "U", U);
  }

  public static Scheme context(Map<String, Object> m, Path params, byte[][][][] cross) {
    if (Json.num(m, "schema") != 1) throw new IllegalArgumentException("Unsupported schema");
    int n = Json.num(m, "n"), u = Json.num(m, "u");
    if (n < 1 || n > 256 || u < 5 || u > 50) throw new IllegalArgumentException("CRS limits");
    Pairing p = PairingFactory.getPairing(params.toString());
    Element[] globals = {
      dec(p, m.get("g"), false),
      dec(p, m.get("Z"), true),
      dec(p, m.get("h"), false),
      dec(p, m.get("v"), false),
      dec(p, m.get("O"), false),
      dec(p, m.get("Y"), false)
    };
    Element[][] slots = {
      decs(p, m.get("A"), n), decs(p, m.get("B"), n), decs(p, m.get("K"), n), decs(p, m.get("E"), n)
    };
    List<Object> us = Json.list(m.get("U"));
    if (us.size() != n) throw new IllegalArgumentException("U dimensions");
    Element[][] U = new Element[n][];
    for (int i = 0; i < n; i++) U[i] = decs(p, us.get(i), u);
    return new Scheme(params.toString(), n, u, globals, slots, U, cross);
  }

  public static Map<String, Object> key(Scheme.Keys k, boolean secret) {
    Map<String, Object> m =
        Json.obj("T", enc(k.T), "Q", enc(k.Q), "Z", enc(k.Z), "V", encs(k.V), "R", encs(k.R));
    if (secret) {
      m.put("r", k.r.toString());
      m.put("q", k.q.toString());
      m.put("z", k.z.toString());
    }
    return m;
  }

  public static Scheme.Keys key(Scheme s, Map<String, Object> m, boolean secret) {
    return new Scheme.Keys(
        secret ? new BigInteger(Json.str(m, "r")) : null,
        secret ? new BigInteger(Json.str(m, "q")) : null,
        secret ? new BigInteger(Json.str(m, "z")) : null,
        dec(s.pairing, m.get("T"), false),
        dec(s.pairing, m.get("Q"), false),
        dec(s.pairing, m.get("Z"), false),
        decs(s.pairing, m.get("V"), s.n),
        decs(s.pairing, m.get("R"), s.n));
  }

  public static Map<String, Object> registry(Scheme.Registry r) {
    List<Object> w = new ArrayList<>();
    for (Element[] es : r.What) w.add(encsWithIdentity(es));
    List<Object> attributes = new ArrayList<>();
    for (Set<Integer> values : r.attributes) attributes.add(new ArrayList<>(values));
    return Json.obj(
        "T",
        enc(r.T),
        "Q",
        enc(r.Q),
        "V",
        encsWithIdentity(r.V),
        "R",
        encsWithIdentity(r.R),
        "Uhat",
        encsWithIdentity(r.Uhat),
        "Zkeys",
        encsWithIdentity(r.Zkeys),
        "Tkeys",
        encsWithIdentity(r.Tkeys),
        "What",
        w,
        "attributes",
        attributes);
  }

  public static Scheme.Registry registry(Scheme s, Map<String, Object> m) {
    List<Set<Integer>> sets = new ArrayList<>();
    for (Object x : Json.list(m.get("attributes"))) sets.add(ints(x, s.u));
    if (sets.size() != s.n) throw new IllegalArgumentException("Attributes dimensions");
    Scheme.Registry r = new Scheme.Registry(s.n, s.u, sets);
    r.T = dec(s.pairing, m.get("T"), false);
    r.Q = dec(s.pairing, m.get("Q"), false);
    Element[][] targets = {r.V, r.R, r.Uhat, r.Zkeys, r.Tkeys};
    String[] names = {"V", "R", "Uhat", "Zkeys", "Tkeys"};
    for (int j = 0; j < names.length; j++) {
      Element[] es = decsWithIdentity(s.pairing, m.get(names[j]), targets[j].length);
      System.arraycopy(es, 0, targets[j], 0, es.length);
    }
    List<Object> w = Json.list(m.get("What"));
    if (w.size() != s.n) throw new IllegalArgumentException("What dimensions");
    for (int i = 0; i < s.n; i++) r.What[i] = decsWithIdentity(s.pairing, w.get(i), s.u);
    return r;
  }

  public static Set<Integer> ints(Object o, int max) {
    Set<Integer> set = new LinkedHashSet<>();
    for (Object a : Json.list(o)) {
      int v = ((Number) a).intValue();
      if (v < 0 || v >= max) throw new IllegalArgumentException("Index range");
      set.add(v);
    }
    return set;
  }

  public static Map<String, Object> policy(Policy p) {
    List<Object> rows = new ArrayList<>();
    for (BigInteger[] row : p.matrix) {
      List<Object> a = new ArrayList<>();
      for (BigInteger v : row) a.add(v.toString());
      rows.add(a);
    }
    // Cloud reads DTOs directly from memory; JSON arrays must already be Lists.
    List<Integer> labels = new ArrayList<>(p.rho.length);
    for (int label : p.rho) labels.add(label);
    return Json.obj("matrix", rows, "rho", labels);
  }

  public static Policy policy(Map<String, Object> m, int u) {
    List<Object> rows = Json.list(m.get("matrix")), labels = Json.list(m.get("rho"));
    if (rows.size() < 1 || rows.size() > 50 || rows.size() != labels.size())
      throw new IllegalArgumentException("Policy row limit");
    BigInteger[][] matrix = new BigInteger[rows.size()][];
    int[] rho = new int[rows.size()];
    for (int i = 0; i < rho.length; i++) {
      List<Object> row = Json.list(rows.get(i));
      if (row.size() < 1 || row.size() > 50) throw new IllegalArgumentException("Policy columns");
      matrix[i] = new BigInteger[row.size()];
      for (int j = 0; j < row.size(); j++) {
        String v = (String) row.get(j);
        if (v.length() > 100) throw new IllegalArgumentException("Coefficient limit");
        matrix[i][j] = new BigInteger(v);
      }
      rho[i] = ((Number) labels.get(i)).intValue();
      if (rho[i] < 0 || rho[i] >= u) throw new IllegalArgumentException("Unknown attribute");
    }
    return new Policy(matrix, rho);
  }

  public static Map<String, Object> cipher(Scheme.Ciphertext c) {
    Map<String, Object> x4 = new LinkedHashMap<>();
    for (Map.Entry<Integer, Element> e : c.X4.entrySet())
      // JPBC Type-A cannot reliably round-trip a G1 identity through
      // bytes.  Keep identity X4 terms explicit as JSON null; cipher()
      // below recreates the field identity on the receiving node.
      x4.put(e.getKey().toString(), e.getValue().isOne() ? null : enc(e.getValue()));
    List<Integer> claimed = new ArrayList<>(c.claimed);
    return Json.obj(
        "sender",
        c.sender,
        "policy",
        policy(c.policy),
        "claimed",
        claimed,
        "C1",
        enc(c.C1),
        "C2",
        enc(c.C2),
        "C3",
        encs(c.C3),
        "C4",
        enc(c.C4),
        "X1",
        enc(c.X1),
        "X2",
        enc(c.X2),
        "X3",
        enc(c.X3),
        "X4",
        x4,
        "X5",
        enc(c.X5));
  }

  public static Scheme.Ciphertext cipher(Scheme s, Map<String, Object> m) {
    Scheme.Ciphertext c = new Scheme.Ciphertext();
    c.sender = Json.num(m, "sender");
    if (c.sender < 0 || c.sender >= s.n) throw new IllegalArgumentException("Sender range");
    c.policy = policy(Json.map(m.get("policy")), s.u);
    c.claimed = ints(m.get("claimed"), s.u);
    c.C1 = dec(s.pairing, m.get("C1"), true);
    c.C2 = dec(s.pairing, m.get("C2"), false);
    c.C3 = decs(s.pairing, m.get("C3"), c.policy.rho.length);
    c.C4 = dec(s.pairing, m.get("C4"), false);
    c.X1 = dec(s.pairing, m.get("X1"), false);
    c.X2 = dec(s.pairing, m.get("X2"), false);
    c.X3 = dec(s.pairing, m.get("X3"), false);
    c.X5 = dec(s.pairing, m.get("X5"), false);
    c.X4 = new HashMap<>();
    for (Map.Entry<String, Object> e : Json.map(m.get("X4")).entrySet()) {
      int a = Integer.parseInt(e.getKey());
      if (!c.claimed.contains(a)) throw new IllegalArgumentException("Unclaimed X4");
      c.X4.put(a, decG1WithIdentity(s.pairing, e.getValue()));
    }
    if (c.X4.size() != c.claimed.size()) throw new IllegalArgumentException("Missing X4");
    return c;
  }

  public static Map<String, Object> update(Scheme.UpdateKey k) {
    return Json.obj(
        "owner",
        k.owner,
        "target",
        k.target,
        "uk",
        enc(k.uk),
        "delta",
        enc(k.delta),
        "phi",
        enc(k.phi),
        "pi",
        encs(k.pi));
  }

  public static Scheme.UpdateKey update(Scheme s, Map<String, Object> m) {
    Scheme.UpdateKey k = new Scheme.UpdateKey();
    k.owner = Json.num(m, "owner");
    k.target = Json.num(m, "target");
    if (k.owner < 0 || k.owner >= s.n || k.target < 0 || k.target >= s.n)
      throw new IllegalArgumentException("Update index");
    k.uk = dec(s.pairing, m.get("uk"), false);
    k.delta = dec(s.pairing, m.get("delta"), false);
    k.phi = dec(s.pairing, m.get("phi"), false);
    // JPBC's G1 identity element is not stable through its byte encoding:
    // decoding an encoded one-element can make isOne()/isEqual() false.
    // The target slot is required to carry exactly that identity in KC,
    // so rebuild it from the pairing and decode only the non-target terms.
    List<Object> pi = Json.list(m.get("pi"));
    if (pi.size() != s.n) throw new IllegalArgumentException("Invalid update vector size");
    k.pi = new Element[s.n];
    for (int i = 0; i < s.n; i++)
      k.pi[i] =
          i == k.target
              ? s.pairing.getG1().newOneElement().getImmutable()
              : dec(s.pairing, pi.get(i), false);
    if (k.delta.isOne() || k.phi.isOne() || k.uk.isOne())
      throw new IllegalArgumentException("Identity update forbidden");
    return k;
  }

  public static Element[] ris(Scheme s, Object o) {
    return decsWithIdentity(s.pairing, o, s.n);
  }

  public static Object ris(Element[] es) {
    return encsWithIdentity(es);
  }

  public static Map<String, Object> read(Path p) throws IOException {
    return Json.map(Json.read(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)));
  }

  public static void save(Path p, Object o) throws IOException {
    Files.createDirectories(p.toAbsolutePath().getParent());
    Path temp = p.resolveSibling(p.getFileName() + ".tmp");
    Files.write(temp, Json.write(o).getBytes(StandardCharsets.UTF_8));
    try {
      Files.move(temp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, p, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public static void writeCross(Path path, Scheme s) throws IOException {
    Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
    try (DataOutputStream d =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
      d.writeInt(s.n);
      d.writeInt(s.u);
      byte[][][][] w = s.exportCrossTerms();
      for (int i = 0; i < s.n; i++)
        for (int j = 0; j < s.n; j++)
          if (i != j)
            for (int a = 0; a < s.u; a++) {
              d.writeInt(w[i][j][a].length);
              d.write(w[i][j][a]);
            }
    }
    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
  }

  public static byte[][][][] readCross(Path path, int n, int u) throws IOException {
    try (DataInputStream d =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
      if (d.readInt() != n || d.readInt() != u) throw new IOException("CRS shape mismatch");
      byte[][][][] w = new byte[n][n][u][];
      for (int i = 0; i < n; i++)
        for (int j = 0; j < n; j++)
          if (i != j)
            for (int a = 0; a < u; a++) {
              int size = d.readInt();
              if (size < 1 || size > 4096) throw new IOException("CRS element size");
              w[i][j][a] = new byte[size];
              d.readFully(w[i][j][a]);
            }
      return w;
    }
  }
}
